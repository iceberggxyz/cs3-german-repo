package com.icebergg.cs3.filmpalast

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CloudStream-Provider für **Filmpalast** (filmpalast.to).
 *
 * Live verifiziert am 22.11.2026 (per DoH, da die Domain in DE DNS-gesperrt ist):
 *  - Startseite: `article.liste` mit `a[href*='/stream/']`, Cover als `img.cover`
 *  - Suche:      `GET /search/title/{query}` (URL-encoded, Pfad-Segment)
 *  - Detail:     `/stream/{slug}` – Hoster stehen direkt im HTML:
 *                `li.hostBg p.hostName` (Name) und `li.streamPlayBtn a.iconPlay` (Play-Link)
 *  - Serien:     Slug endet auf `-s{staffel}e{episode}`, z. B. `…-s03e02`
 */
open class FilmPalast : MainAPI() {

    override var mainUrl = BASE
    override var name = "Filmpalast"
    override var lang = "de"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Neu auf Filmpalast",
    )

    private val seriesRegex = Regex("""-s(\d+)e(\d+)""", RegexOption.IGNORE_CASE)
    private val seriesSuffixRegex = Regex("""(?i)-s\d+e\d+$""")

    private var baseResolved = false

    /**
     * `filmpalast.tv` liefert eine JS-Challenge, `filmpalast.to` antwortet direkt.
     * Die Prüfung stellt sicher, dass wirklich Katalog-Inhalt kommt.
     */
    private suspend fun ensureBase(): String {
        if (baseResolved) return mainUrl
        for (mirror in MIRRORS) {
            val html = runCatching { app.get("$mirror/").text }.getOrNull() ?: continue
            if (html.contains("/stream/") && html.contains("article")) {
                mainUrl = mirror
                baseResolved = true
                println("[Filmpalast] base=$mirror")
                return mainUrl
            }
        }
        baseResolved = true
        println("[Filmpalast] no working mirror, fallback=$mainUrl")
        return mainUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureBase()
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page"
        val doc = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureBase()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = runCatching {
            app.get("$mainUrl/search/title/$encoded", referer = "$mainUrl/").document
        }.getOrNull() ?: return emptyList()
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureBase()
        val doc = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()
            ?: return null

        val title = doc.selectFirst("h2.bgDark")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            .ifEmpty { doc.title().substringBefore(" Stream").trim() }
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("img[id^='s_img__']")?.attr("src")
        )
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val slug = url.substringAfterLast("/")
        val seriesMatch = seriesRegex.find(slug)

        if (seriesMatch == null) {
            // Film: Detailseite dient als data-URL, loadLinks liest die Hoster dort.
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val season = seriesMatch.groupValues[1].toIntOrNull() ?: 1
        val episode = seriesMatch.groupValues[2].toIntOrNull() ?: 1
        val baseSlug = slug.replace(seriesSuffixRegex, "")

        // Alle Episoden derselben Serie, die auf der Seite verlinkt sind.
        val episodes = mutableListOf<Episode>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href*='/stream/']")) {
            val link = normalize(a.attr("href")) ?: continue
            val linkSlug = link.substringAfterLast("/")
            if (!linkSlug.startsWith(baseSlug, ignoreCase = true)) continue
            val m = seriesRegex.find(linkSlug) ?: continue
            val s = m.groupValues[1].toIntOrNull() ?: continue
            val e = m.groupValues[2].toIntOrNull() ?: continue
            if (!seen.add("$s-$e")) continue
            episodes.add(newEpisode(link) {
                this.name = "Staffel $s Folge $e"
                this.season = s
                this.episode = e
            })
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "Staffel $season Folge $episode"
                this.season = season
                this.episode = episode
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull()
            ?: return false

        // Hostername und Play-Link stehen in getrennten <li>, aber in gleicher Reihenfolge.
        val names = doc.select("ul.currentStreamLinks li.hostBg p.hostName").map { it.text().trim() }
        val links = doc.select("ul.currentStreamLinks li.streamPlayBtn a.iconPlay")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") }

        println("[Filmpalast] page=$data hoster=${links.size}")

        var found = false
        links.forEachIndexed { index, link ->
            val label = names.getOrNull(index)?.ifBlank { null } ?: hostOf(link)
            val ok = runCatching {
                loadExtractor(link, data, subtitleCallback) { callback(it) }
            }.getOrDefault(false)
            println("[Filmpalast] hoster=$label ok=$ok url=$link")
            if (ok) found = true
        }
        return found
    }

    // ------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (article in doc.select("article.liste")) {
            val a = article.selectFirst("a[href*='/stream/']") ?: continue
            val url = normalize(a.attr("href")) ?: continue
            if (!seen.add(url)) continue

            val title = a.attr("title").trim()
                .ifEmpty { article.selectFirst("h2 a")?.text()?.trim().orEmpty() }
                .ifEmpty { a.text().trim() }
            if (title.isEmpty()) continue

            val img = article.selectFirst("img.cover") ?: article.selectFirst("img")
            val poster = fixUrlNull(
                img?.attr("src")?.ifBlank { null } ?: img?.attr("data-src")
            )

            val isSeries = seriesSuffixRegex.containsMatchIn(url.substringAfterLast("/"))
            out.add(
                if (isSeries) {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }
        return out
    }

    /** Macht `//host/stream/x`, `/stream/x` und absolute URLs vergleichbar. */
    private fun normalize(raw: String): String? {
        if (raw.isBlank()) return null
        val abs = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> fixUrl(raw)
        }
        return abs.takeIf { it.contains("/stream/") }
    }

    private fun hostOf(url: String): String =
        Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: "Hoster"

    companion object {
        private const val BASE = "https://filmpalast.to"

        /**
         * `filmpalast.tv` ist nur eine JS-Challenge (JWT via `window.location.replace`)
         * und wird deshalb nicht als Mirror verwendet.
         */
        private val MIRRORS = listOf(
            "https://filmpalast.to",
        )
    }
}
