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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CloudStream-Provider für **HDFilme** (aktuelle Domain `hd-filme.today`;
 * `hdfilme.ceo` leitet dorthin weiter).
 *
 * Aufbau:
 *  - Katalog/Suche sind serverseitig gerendert; jeder Treffer ist ein
 *    `<a class="cv" href="/filme1/{id}-{slug}-stream" title="…">` mit Poster.
 *  - Die Detailseite ist eine Next.js-Seite. Die eigentliche Quelle steht als
 *    Flight-Daten im HTML: `"src":"https://devideosrc.co/custom/movie/tt…"`.
 *  - Diese Embed-URL wird an den gemeinsamen [DevideoSrc] übergeben, der den
 *    Player-Config entpackt und die HLS-Playlist liefert.
 *
 * Serien (`/serie/…`) haben je Episode einen anderen Embed und werden hier
 * bewusst nicht angeboten ([supportedTypes] = Movie).
 */
open class Hdfilme : MainAPI() {
    override var mainUrl = "https://hd-filme.today"
    override var name = "HDFilme"
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Startseite",
        "/filme1" to "Alle Filme",
    )

    companion object {
        /** devideosrc-Embed, wie er in den Next.js-Flight-Daten steht. */
        private val DEV_EMBED = Regex("""https://devideosrc\.co/[A-Za-z0-9/_.-]+""")
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")
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

    /**
     * Liest Filmkarten (`a.cv`). Serien-Links (`/serie/…`) werden übersprungen,
     * weil dieser Provider nur Filme auflöst.
     */
    private fun parseCards(document: Document): List<SearchResponse> =
        document.select("a.cv[href]")
            .filterNot { it.attr("href").contains("/serie/") }
            .mapNotNull { card ->
                val href = card.attr("href")
                val title = card.attr("title").trim().ifBlank {
                    card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                }
                if (href.isBlank() || title.isBlank()) return@mapNotNull null
                val poster = card.selectFirst("img")?.attr("src")
                    ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val document = response.document
        val html = response.text

        // Serien liefern an dieser Stelle keinen Film-Embed -> nichts anbieten.
        val embed = DEV_EMBED.find(html)?.value?.replace("&", "&")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val title = document.selectFirst("h1")?.text()?.trim()?.takeUnless { it.isBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeUnless { it.isBlank() }
            ?: document.title().substringBefore("|").substringBefore(" - ").trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val year = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.let { YEAR.find(it)?.value?.toIntOrNull() }

        return newMovieLoadResponse(title, url, TvType.Movie, embed) {
            this.posterUrl = poster
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
