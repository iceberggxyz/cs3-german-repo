package com.icebergg.cs3.hdfilme

import com.icebergg.cs3.shared.DevideoSrc
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CloudStream-Provider für **HDFilme** (aktuelle Domain `hd-filme.today`;
 * `hdfilme.ceo` leitet dorthin weiter).
 *
 * Aufbau:
 *  - Katalog/Suche sind serverseitig gerendert; jeder Treffer ist ein
 *    `<a class="cv" href="/filme1/…|/serie/…" title="…">` mit Poster.
 *  - Die Detailseite ist eine Next.js-Seite. Die Quelle steht als
 *    devideosrc.co-Embed in den Flight-Daten:
 *      * Film:  `https://devideosrc.co/custom/movie/tt…`
 *      * Serie: `https://devideosrc.co/custom/serial/tt…/{season}/{episode}`
 *    Für Serien wird das Embed je Episode aus der IMDb-ID und der Episoden-
 *    Liste (`episodi`, Felder `season_number`/`episode_number`) gebaut.
 *  - Beide Embed-Formen liefern ein `<main data-resolve-token>` und werden vom
 *    gemeinsamen [DevideoSrc] aufgelöst (Player-Config entpacken -> HLS).
 */
open class Hdfilme : MainAPI() {
    override var mainUrl = "https://hd-filme.today"
    override var name = "HDFilme"
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Startseite",
        "/filme1" to "Alle Filme",
        "/serien" to "Alle Serien",
    )

    companion object {
        /** Basis für das Serien-Embed je Episode. */
        private const val DEV_SERIAL = "https://devideosrc.co/custom/serial"

        /** devideosrc-Film-Embed, wie er in den Next.js-Flight-Daten steht. */
        private val DEV_EMBED = Regex("""https://devideosrc\.co/[A-Za-z0-9/_.-]+""")
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")

        /** IMDb-ID der Detailseite (`tt…`). */
        private val IMDB = Regex("""tt\d{6,9}""")

        /**
         * Episoden-Eintrag aus den Flight-Daten. Die Anführungszeichen sind dort
         * als `\"` escaped, daher ist der Backslash optional. Der Name ist
         * optional, damit die Episoden auch ohne Titel erkannt werden.
         */
        private val EPISODE = Regex(
            """\\?"season_number\\?"\s*:\s*(\d+)\s*,\s*\\?"episode_number\\?"\s*:\s*(\d+)""" +
                """(?:,\s*\\?"name\\?"\s*:\s*\\?"([^"\\]*))?"""
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var path = request.data
        if (page > 1) path += if (path.contains('?')) "&page=$page" else "?page=$page"
        val document = app.get("$mainUrl$path").document
        return newHomePageResponse(request.name, parseCards(document))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/suche?q=$encoded").document
        return parseCards(document)
    }

    /** Liest Karten (`a.cv`); Serien-Links werden als [TvType.TvSeries] markiert. */
    private fun parseCards(document: Document): List<SearchResponse> =
        document.select("a.cv[href]").mapNotNull { card ->
            val href = card.attr("href")
            val title = card.attr("title").trim().ifBlank {
                card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (href.isBlank() || title.isBlank()) return@mapNotNull null
            val poster = card.selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val type = if (href.contains("/serie/")) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, fixUrl(href), type) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val document = response.document
        val html = response.text

        val title = document.selectFirst("h1")?.text()?.trim()?.takeUnless { it.isBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeUnless { it.isBlank() }
            ?: document.title().substringBefore("|").substringBefore(" - ").trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.trim()?.takeUnless { it.isBlank() }
        val year = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.let { YEAR.find(it)?.value?.toIntOrNull() }

        if (url.contains("/serie/")) {
            return loadSeries(url, html, title, poster, plot, year)
        }

        // Film: genau ein Embed in den Flight-Daten.
        val embed = DEV_EMBED.find(html)?.value?.replace("&", "&")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, embed) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    /**
     * Baut die Episoden aus der IMDb-ID und der `episodi`-Liste. Die eigentliche
     * Auflösung passiert je Episode in [loadLinks] über [DevideoSrc].
     */
    private suspend fun loadSeries(
        url: String,
        html: String,
        title: String,
        poster: String?,
        plot: String?,
        year: Int?,
    ): LoadResponse? {
        val imdb = IMDB.find(html)?.value ?: return null

        val episodes = EPISODE.findAll(html)
            .mapNotNull { m ->
                val season = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val number = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val name = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                newEpisode("$DEV_SERIAL/$imdb/$season/$number") {
                    this.season = season
                    this.episode = number
                    this.name = name ?: "Staffel $season Folge $number"
                    this.posterUrl = poster
                }
            }
            .distinctBy { (it.season ?: 0) to (it.episode ?: 0) }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .toList()

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        return DevideoSrc.load(data, subtitleCallback, callback)
    }
}
