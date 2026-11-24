package com.icebergg.cs3.sto

import com.icebergg.cs3.shared.DevideoSrc
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

/**
 * CloudStream-Provider für **s.to / SerienStream**.
 *
 * Erkenntnisse aus dem Live-Scraping:
 *  - Die Detailseite enthält **nur die Folgen der ersten Staffel**.
 *  - Alle Staffeln stehen als Links `/serie/{slug}/staffel-{n}` auf der Detailseite.
 *  - Die Staffelseite enthält die Folgen-Links inklusive Episodentitel im
 *    `title`-Attribut: `/serie/{slug}/staffel-{n}/folge-{e}`.
 *  - Die Episodenseite injiziert den Player erst per JavaScript; im Roh-HTML
 *    steht kein iframe. Die Player-URL wird deshalb direkt gebaut:
 *    `https://devideosrc.co/serial/{imdb}/{staffel}/{folge}`.
 *
 * Die Domain ist nicht stabil: `serienstream.si` ist aktuell die gültige,
 * `serienstream.to` und `s.to` sind je nach DNS/CUII-Sperre erreichbar.
 * [ensureBase] probiert die Mirrors der Reihe nach durch.
 */
open class StoProvider : MainAPI() {

    override var mainUrl = DEFAULT_BASE
    override var name = "s.to"
    override var lang = "de"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/" to "Beliebt",
    )

    private val seriesRootRegex = Regex(""".*/serie/[^/]+$""")
    private val seasonRootRegex = Regex(""".*/staffel-(\d+)$""")
    private val episodeRegex = Regex(""".*/staffel-(\d+)/folge-(\d+)$""")
    private val imdbRegex = Regex("""tt\d{7,}""")

    /** Wird nach dem ersten erfolgreichen Mirror-Test gesetzt. */
    private var baseResolved = false

    private suspend fun ensureBase(): String {
        if (baseResolved) return mainUrl
        for (mirror in MIRRORS) {
            val ok = runCatching {
                app.get("$mirror/").text.contains("/serie/")
            }.getOrDefault(false)
            if (ok) {
                mainUrl = mirror
                baseResolved = true
                println("[s.to] base=$mirror")
                return mainUrl
            }
        }
        baseResolved = true
        println("[s.to] no mirror reachable, fallback=$mainUrl")
        return mainUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureBase()
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/?page=$page"
        val doc = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList())

        val items = doc.select("a[href*='/serie/']").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val link = fixUrl(href)
            if (!seriesRootRegex.matches(link)) return@mapNotNull null

            val title = a.attr("title").trim().ifEmpty { a.text().trim() }
            if (title.isEmpty()) return@mapNotNull null

            val img = a.selectFirst("img")
            val poster = img?.let { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }

            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureBase()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = runCatching {
            app.get("$mainUrl/suche?q=$encoded", referer = "$mainUrl/").document
        }.getOrNull() ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href*='/serie/']")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val url = fixUrl(href)
            if (!seriesRootRegex.matches(url)) continue
            if (!seen.add(url)) continue

            val title = a.attr("title").trim().ifEmpty { a.text().trim() }
            if (title.isEmpty()) continue

            val img = a.selectFirst("img")
            val poster = img?.let { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }

            results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureBase()
        val doc = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()
            ?: return null

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifEmpty { doc.title().substringBefore("|").trim() }
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val imdb = imdbRegex.find(doc.html())?.value
        println("[s.to] detail=$url imdb=$imdb")

        // Staffel-Links von der Detailseite (nur diese führen zu allen Folgen).
        val seasonUrls = doc.select("a[href*='/staffel-']")
            .map { fixUrl(it.attr("href")) }
            .filter { seasonRootRegex.matches(it) }
            .distinct()

        val episodes = mutableListOf<Episode>()
        val seen = HashSet<String>()

        for (seasonUrl in seasonUrls) {
            val seasonFromUrl = seasonRootRegex.find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()
            val seasonDoc = runCatching { app.get(seasonUrl, referer = url).document }.getOrNull()
            if (seasonDoc == null) {
                println("[s.to] season page failed: $seasonUrl")
                continue
            }

            for (a in seasonDoc.select("a[href*='/folge-']")) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                val episodeUrl = fixUrl(href)
                val match = episodeRegex.find(episodeUrl) ?: continue

                val season = match.groupValues[1].toIntOrNull() ?: seasonFromUrl ?: continue
                val episode = match.groupValues[2].toIntOrNull() ?: continue

                if (!seen.add("$season-$episode")) continue

                // Player-URL direkt bauen – der iframe entsteht auf der Webseite erst per JS.
                val data = if (imdb != null) {
                    "https://devideosrc.co/serial/$imdb/$season/$episode"
                } else {
                    episodeUrl
                }

                // Episodentitel steht im title-Attribut der Folgen-Links.
                val linkText = a.text().trim()
                val name = a.attr("title").trim()
                    .ifEmpty { if (linkText.isNotEmpty()) linkText else "Staffel $season Folge $episode" }

                episodes.add(
                    newEpisode(data) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        }

        println("[s.to] seasons=${seasonUrls.size} episodes=${episodes.size}")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            // IMDb-ID steht ohnehin schon im HTML (fuer den Player benoetigt).
            if (imdb != null) addImdbId(imdb)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Normalfall: bereits fertige devideosrc-Player-URL.
        if (data.startsWith("https://devideosrc.co")) {
            if (DevideoSrc.load(data, subtitleCallback, callback)) return true
        }

        // Fallback: data ist eine Webseiten-URL → iframe/Hoster-Links suchen.
        val doc = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull()
            ?: return false

        val devideo = doc.selectFirst("iframe[src*='devideosrc.co']")?.attr("src")
        if (!devideo.isNullOrBlank() && DevideoSrc.load(devideo, subtitleCallback, callback)) {
            return true
        }

        var found = false
        for (el in doc.select("[data-link], a[href]")) {
            val raw = el.attr("data-link").ifBlank { el.attr("href") }
            if (!raw.startsWith("http")) continue
            // HosterResolver loest VOE-Kurzlinks und Vorschalt-Seiten (iframe) auf.
            if (HosterResolver.resolve(raw, data, subtitleCallback) { callback(it) }) found = true
        }
        return found
    }

    companion object {
        private const val DEFAULT_BASE = "https://serienstream.si"
        private val MIRRORS = listOf(
            "https://serienstream.si",
            "https://serienstream.to",
            "https://s.to",
        )
    }
}
