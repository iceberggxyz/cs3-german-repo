package com.icebergg.cs3.kkiste

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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CloudStream-Provider für **KKiste** (Filme & Serien, DataLife Engine).
 *
 * Live verifiziert am 04.09.2026:
 *  - Suche:   `/index.php?do=search&subaction=search&story={query}`
 *  - Detail:  `<article>` mit `<h1>`-Titel, Poster-`<img>`, `span[data-link]`-Streams
 *             (Filme -> meinecloud/Sirius, Serien -> Voe pro Episode).
 */
open class KkisteProvider : MainAPI() {

    companion object {
        /** Aktuelle KKiste-Domain (bei Wechsel hier aktualisieren). */
        const val BASE_URL = "https://kkiste-io.ink"

        /** DLE-Suchpfad. */
        const val SEARCH_PATH = "/index.php?do=search&subaction=search&story="

        /** Regex: Staffelnummer aus dem Titel. */
        val REGEX_STAFFEL = Regex("Staffel[\\s-]*(\\d+)", RegexOption.IGNORE_CASE)
    }

    override var name = "KKiste"
    override var mainUrl = BASE_URL

    /** DLE-Seiten liefern Filme und Serien. */
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var lang = "de"
    override val hasMainPage = false

    // ------------------------------------------------------------------
    // Suche
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl$SEARCH_PATH$encoded", referer = "$mainUrl/").document

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (card in doc.select("article")) {
            val link = card.selectFirst("h2 a[href], h3 a[href]") ?: continue
            val href = link.attr("href")
            if (href.isBlank()) continue

            val url = fixUrl(href)
            // Nur echte Detailseiten (mit numerischer ID) übernehmen.
            if (!url.matches(Regex(".*/\\d+-.*\\.html$"))) continue
            if (!seen.add(url)) continue

            val title = link.text().trim()
                .ifEmpty { card.selectFirst("h2, h3")?.text()?.trim().orEmpty() }
            if (title.isEmpty()) continue

            val img = card.selectFirst("img")
            val poster = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))
            val isSeries = REGEX_STAFFEL.containsMatchIn(title)

            results.add(
                if (isSeries) {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
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

        val title = doc.selectFirst("article h1, h1")?.text()?.trim().orEmpty()
            .ifEmpty { doc.title() }

        val posterImg = doc.selectFirst("article img[alt], img[alt*='Cover'], img[alt*='Poster']")
        val poster = fixUrlNull(posterImg?.attr("src") ?: posterImg?.attr("data-src"))

        val plot = doc.selectFirst("article p, .fdesc, .full-text")?.text()?.trim()

        val sources = collectStreamSources(doc, url)
        val isSeries = REGEX_STAFFEL.containsMatchIn(title) && sources.size > 1

        return if (isSeries) {
            val season = REGEX_STAFFEL.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodes = sources.mapIndexed { index, _ ->
                newEpisode("$url#idx=$index") {
                    this.name = "Episode ${index + 1}"
                    this.season = season
                    this.episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
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
        // `data` kann ein Fragment `#idx=N` tragen (Serien-Episoden).
        val pageUrl = data.substringBefore("#")
        val idx = data.substringAfter("#idx=", "").toIntOrNull()

        val doc = app.get(pageUrl, referer = "$mainUrl/").document
        val sources = collectStreamSources(doc, pageUrl)

        val selected = when {
            idx != null -> listOfNotNull(sources.getOrNull(idx))
            else -> sources
        }

        var found = false
        for (source in selected) {
            loadExtractor(source.url, pageUrl, subtitleCallback) { link ->
                callback.invoke(link)
            }
            found = true
        }
        return found
    }

    // ------------------------------------------------------------------
    // Stream-Quellen extrahieren
    // ------------------------------------------------------------------

    /** Datenklasse für eine Stream-Quelle (Hoster-Name + Embed-URL). */
    private data class StreamSource(val name: String, val url: String)

    /**
     * Sammelt alle Stream-Quellen einer Detailseite:
     *  1. `span[data-link]` (KKiste-Stil) – Hoster-Name aus dem Text.
     *  2. `<iframe src>` – Hoster-Name aus der Domain.
     */
    private fun collectStreamSources(doc: Document, baseUrl: String): List<StreamSource> {
        val sources = mutableListOf<StreamSource>()
        val seen = HashSet<String>()

        // KKiste liefert die Streams als `<a data-link="https://voe.sx/...">Voe</a>`
        // (pro Episode ein Eintrag). Deshalb auf jedes Element mit `data-link` prüfen.
        for (el in doc.select("[data-link]")) {
            val raw = el.attr("data-link").trim()
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) continue
            val url = fixUrl(raw)
            if (!seen.add(url)) continue

            val name = el.text().trim().replace(Regex("\\s+"), " ")
                .ifEmpty { hostNameFromUrl(url) }
            sources.add(StreamSource(name, url))
        }

        for (iframe in doc.select("iframe[src]")) {
            val raw = iframe.attr("src").trim()
            if (raw.startsWith("about:") || raw.startsWith("data:")) continue
            val url = fixUrl(raw)
            // YouTube-Trailer ausschliessen.
            if (url.contains("youtube.com") || url.contains("youtu.be")) continue
            if (!seen.add(url)) continue

            val name = hostNameFromUrl(url)
            sources.add(StreamSource(name, url))
        }

        return sources
    }

    /** Leitet einen lesbaren Hoster-Namen aus der URL-Domain ab. */
    private fun hostNameFromUrl(url: String): String {
        return runCatching {
            val host = java.net.URI(url).host ?: return@runCatching "Stream"
            host.substringBefore(".").replaceFirstChar { it.uppercaseChar() }
        }.getOrDefault("Stream")
    }
}
