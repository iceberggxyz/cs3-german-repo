package com.icebergg.cs3.burningseries

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CloudStream-Provider für **Burning Series** (Serien).
 *
 * Burning Series nutzt die Kinox-Plattform und wechselt die Domain sehr
 * häufig (bs.to, burning-series.co, …). Bei einem Domainwechsel muss lediglich
 * [BASE_URL] aktualisiert werden.
 *
 *  - Suche:   `GET /Search.html?q={query}`
 *  - Serie:   `GET /Stream/{Addr}.html`
 *  - Hoster:  `GET /aGET/MirrorByEpisode/?Addr={addr}&Episode={n}&Season={n}`
 */
open class BurningSeriesProvider : MainAPI() {

    companion object {
        /** Aktuelle Burning-Series-Domain (bitte bei Domainwechsel aktualisieren). */
        const val BASE_URL = "https://burning-series.io"

        /**
         * Getestete Mirrors (Stand 22.11.2026):
         *  - `burning-series.io`  → echte Seite (HTTP 200)
         *  - `burning-series.org` → Portal, leitet auf `.io` weiter
         *  - `burningseries.to`   → Portal, leitet auf `.io` weiter
         *  - `burning-series.co`  → Cloudflare 526 (ungültiges Origin-Zertifikat)
         */
        private val MIRRORS = listOf(
            "https://burning-series.io",
            "https://burning-series.org",
            "https://burningseries.to",
        )

        /** Erkennungsmerkmal einer echten Burning-Series-Seite. */
        private const val MIRROR_MARKER = "Burning Series"

        /** Suchpfad. */
        const val SEARCH_PATH = "/Search.html?q="

        /** Hoster-Endpoint. */
        const val HOSTER_PATH = "/aGET/MirrorByEpisode/"

        /** Extrahiert die interne `Addr` aus einer `/Stream/...`-URL. */
        val REGEX_STREAM_ADDR = Regex("/Stream/([^?.&]+)")
    }

    override var name = "Burning Series"
    override var mainUrl = BASE_URL

    /** Burning Series liefert in erster Linie Serien. */
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var lang = "de"
    override val hasMainPage = false

    private var baseResolved = false

    /**
     * Probiert die Mirrors durch. Wichtig: `.org` und `burningseries.to` sind
     * nur Portale, die auf `.io` verlinken – die Inhaltsprüfung erkennt das
     * daran, dass der Marker fehlt, und nimmt die nächste Domain.
     */
    private suspend fun ensureBase(): String {
        if (baseResolved) return mainUrl
        for (mirror in MIRRORS) {
            val html = runCatching { app.get("$mirror/").text }.getOrNull() ?: continue
            if (html.contains(MIRROR_MARKER)) {
                mainUrl = mirror
                baseResolved = true
                println("[BurningSeries] base=$mirror")
                return mainUrl
            }
        }
        baseResolved = true
        println("[BurningSeries] no working mirror, fallback=$mainUrl")
        return mainUrl
    }

    // ------------------------------------------------------------------
    // Suche
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        ensureBase()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl$SEARCH_PATH$encoded", referer = "$mainUrl/").document

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href*='/Stream/']")) {
            val href = a.attr("href")
            if (!href.contains("/Stream/")) continue
            if (href.contains("Episode") || href.contains("Staffel") || href.contains("Mirror")) continue

            val url = fixUrl(href)
            if (!seen.add(url)) continue

            val title = a.text().trim().ifEmpty { a.attr("title").trim() }
            if (title.isEmpty()) continue

            results.add(
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = null
                }
            )
        }
        return results
    }

    // ------------------------------------------------------------------
    // Detailseite
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1, .series-title, .Title")?.text()?.trim().orEmpty()
            .ifEmpty { doc.title() }

        val posterPath = doc.selectFirst("img[alt*='Cover']")?.attr("src")
            ?: doc.selectFirst("img[alt*='Poster']")?.attr("src")
        val poster = fixUrlNull(posterPath)

        val plot = doc.selectFirst("p.description, .seri_des")?.text()

        val episodes = parseEpisodes(doc, url)

        return if (episodes.isEmpty()) {
            // Film oder Einzelstream ohne Episodentabelle.
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    /**
     * Extrahiert Episoden. Die `data`-URL trägt die Zusatzparameter
     * `season` und `episode`, die [loadLinks] für den Hoster-Endpoint benötigt.
     */
    private fun parseEpisodes(doc: Document, seriesUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seen = HashSet<String>()

        val rows = doc.select("table.seasonEpisodesList tbody tr")
        val episodeNodes = if (rows.isNotEmpty()) {
            rows.flatMap { it.select("a[href]") }
        } else {
            doc.select("a[href*='Episode'], a[href*='Staffel']")
        }

        for (link in episodeNodes) {
            val href = link.attr("href")
            if (href.isBlank()) continue
            val episodeUrl = fixUrl(href)

            val season = Regex("Staffel-(\\d+)").find(episodeUrl)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val episodeNum = Regex("Episode-(\\d+)").find(episodeUrl)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: link.text().trim().filter { it.isDigit() }.toIntOrNull()

            val name = link.text().trim().ifEmpty { "Episode ${episodeNum ?: "?"}" }
            if (name.isEmpty()) continue

            // Datenvertrag: Serien-URL + Season + Episode als Query-Parameter.
            val data = buildEpisodeData(seriesUrl, season, episodeNum ?: 0)
            if (!seen.add(data)) continue

            episodes.add(
                newEpisode(data) {
                    this.name = name
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }
        return episodes
    }

    /** Baut die `data`-URL einer Episode (`{serie}?season=…&episode=…`). */
    private fun buildEpisodeData(seriesUrl: String, season: Int, episode: Int): String {
        val separator = if (seriesUrl.contains("?")) "&" else "?"
        return "${seriesUrl}${separator}season=$season&episode=$episode"
    }

    // ------------------------------------------------------------------
    // Stream-Links
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // `data` entspricht "{serie}?season=…&episode=…" (siehe buildEpisodeData).
        val season = Regex("[?&]season=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = Regex("[?&]episode=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val addr = REGEX_STREAM_ADDR.find(data)?.groupValues?.get(1)
            ?: return false

        val hosterUrl = buildString {
            append(mainUrl)
            append(HOSTER_PATH)
            append("?Addr=").append(URLEncoder.encode(addr, "UTF-8"))
            append("&Episode=").append(episode)
            append("&Season=").append(season)
        }

        val doc = app.get(hosterUrl, referer = data).document

        var found = false
        for (item in doc.select("a[href*='/Stream/'], a[href*='Hoster'], a[href*='/aGET/']")) {
            val hostName = item.text().trim().ifEmpty { item.attr("title").trim() }
            if (hostName.isEmpty()) continue

            val redirectUrl = fixUrl(item.attr("href"))
            loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                callback.invoke(link)
            }
            found = true
        }
        return found
    }
}
