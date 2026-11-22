package com.icebergg.cs3.kkiste

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

        /** Regex: Staffelnummer aus dem Titel. */
        val REGEX_STAFFEL = Regex("Staffel[\\s-]*(\\d+)", RegexOption.IGNORE_CASE)

        /** Detailseiten haben immer eine numerische ID: `/26355-titel....html`. */
        val DETAIL_REGEX = Regex(""".*/\d+-.*\.html$""")
    }

    override var name = "KKiste"
    override var mainUrl = BASE_URL

    /** DLE-Seiten liefern Filme und Serien. */
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var lang = "de"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/" to "Neu auf KKiste",
    )

    // ------------------------------------------------------------------
    // Startseite
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val doc = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(request.name, parseListing(doc))
    }

    /**
     * Wertet eine KKiste-Listenseite (Startseite oder Suchergebnis) aus.
     * Bevorzugt die `<article>`-Karten, fällt bei fehlender Struktur auf alle
     * Detail-Links zurück.
     */
    private fun parseListing(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        fun add(rawHref: String, rawTitle: String, poster: String?) {
            if (rawHref.isBlank()) return
            val url = fixUrl(rawHref)
            // Nur echte Detailseiten (mit numerischer ID) übernehmen.
            if (!DETAIL_REGEX.matches(url)) return
            if (!seen.add(url)) return
            val title = rawTitle.trim()
            if (title.isEmpty()) return

            out.add(
                if (REGEX_STAFFEL.containsMatchIn(title)) {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }

        val cards = doc.select("article")
        if (cards.isNotEmpty()) {
            for (card in cards) {
                val link = card.selectFirst("h2 a[href], h3 a[href]") ?: continue
                val title = link.text().trim()
                    .ifEmpty { card.selectFirst("h2, h3")?.text()?.trim().orEmpty() }
                val img = card.selectFirst("img")
                val poster = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))
                add(link.attr("href"), title, poster)
            }
        } else {
            for (a in doc.select("a[href]")) {
                val img = a.selectFirst("img")
                val poster = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))
                val title = a.attr("title").trim().ifEmpty { a.text().trim() }
                add(a.attr("href"), title, poster)
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Suche
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        // KKiste nutzt ein POST-Formular (do/subaction/story) auf die Startseite.
        val doc = runCatching {
            app.post(
                "$mainUrl/",
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query,
                ),
                referer = "$mainUrl/",
            ).document
        }.getOrNull() ?: return emptyList()

        return parseListing(doc)
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
