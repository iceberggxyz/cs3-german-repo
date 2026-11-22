package com.icebergg.cs3.sto

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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

/**
 * CloudStream-Provider für **s.to / SerienStream** (serienstream.si).
 *
 * Neue Next.js-Plattform:
 *  - Suche:   `GET /suche?q={query}`
 *  - Serie:   `/serie/{slug}`
 *  - Staffel: `/serie/{slug}/staffel-{n}` (liefert die Folgen-Links)
 *  - Folge:   `/serie/{slug}/staffel-{n}/folge-{e}` (Player/`devideosrc`)
 */
open class StoProvider : MainAPI() {

    override var mainUrl = "https://serienstream.si"
    override var name = "s.to"
    override var lang = "de"

    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val seriesRootRegex = Regex(""".*/serie/[^/]+$""")
    private val seasonRegex = Regex(""".*/staffel-(\d+)$""")
    private val episodeRegex = Regex(""".*/staffel-(\d+)/folge-(\d+)$""")

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/suche?q=$encoded", referer = "$mainUrl/").document

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href*='/serie/']")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val url = fixUrl(href)
            if (!seriesRootRegex.matches(url)) continue
            if (!seen.add(url)) continue

            val title = a.text().trim()
            if (title.isEmpty()) continue

            results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries))
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifEmpty { doc.title().substringBefore("|").trim() }
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val seasonUrls = doc.select("a[href*='/staffel-']")
            .map { fixUrl(it.attr("href")) }
            .filter { seasonRegex.matches(it) }
            .distinct()

        val episodes = mutableListOf<Episode>()
        for (seasonUrl in seasonUrls) {
            val season = seasonRegex.find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val seasonDoc = app.get(seasonUrl, referer = url).document

            val episodeUrls = seasonDoc.select("a[href*='/folge-']")
                .map { fixUrl(it.attr("href")) }
                .filter { episodeRegex.matches(it) }
                .distinct()

            for (episodeUrl in episodeUrls) {
                val match = episodeRegex.find(episodeUrl) ?: continue
                val episode = match.groupValues[2].toIntOrNull()
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = "Staffel $season Folge ${episode ?: 0}"
                        this.season = season
                        this.episode = episode
                    }
                )
            }
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
        val doc = app.get(data, referer = "$mainUrl/").document

        // Bevorzugt: devideosrc-Player auflösen.
        val devideo = doc.selectFirst("iframe[src*='devideosrc.co']")?.attr("src")
        if (!devideo.isNullOrBlank()) {
            if (DevideoSrc.load(devideo, subtitleCallback, callback)) return true
        }

        // Fallback: direkte Hoster-Links.
        var found = false
        for (el in doc.select("[data-link], a[href]")) {
            val raw = el.attr("data-link").ifBlank { el.attr("href") }
            if (!raw.startsWith("http")) continue
            loadExtractor(raw, data, subtitleCallback) { callback(it) }
            found = true
        }
        return found
    }
}
