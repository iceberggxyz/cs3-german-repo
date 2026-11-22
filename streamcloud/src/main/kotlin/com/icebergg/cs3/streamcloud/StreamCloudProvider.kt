package com.icebergg.cs3.streamcloud

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
import java.net.URLEncoder

/**
 * CloudStream-Provider für **StreamCloud** (streamcloud.date).
 *
 * Die Seite läuft inzwischen auf einer neuen Next.js-Plattform:
 *  - Suche:   `GET /suche?q={query}` (serverseitig gerendert)
 *  - Filme:   `/filme/{id-slug}`, Serie: `/serie/{id-slug}`
 *  - Player:  `iframe` von `devideosrc.co` → `/movie/{imdb}` bzw.
 *             `/custom/serial/{imdb}/{staffel}/{episode}`
 *  - Staffeln: `<button>Staffel N<b>Episodenzahl</b></button>`
 */
open class StreamCloudProvider : MainAPI() {

    override var mainUrl = "https://streamcloud.date"
    override var name = "StreamCloud"
    override var lang = "de"

    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val detailRegex = Regex(""".*/(?:filme|serie)/.+""")
    private val imdbRegex = Regex("""tt\d{7,}""")
    private val serialImdbRegex = Regex("""devideosrc\.co/custom/serial/(tt\d+)""")

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/suche?q=$encoded", referer = "$mainUrl/").document

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val url = fixUrl(href)
            if (!detailRegex.matches(url)) continue
            if (!seen.add(url)) continue

            val title = a.text().trim()
            if (title.isEmpty()) continue

            results.add(
                if (url.contains("/serie/")) {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries)
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie)
                }
            )
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifEmpty { doc.title().substringBefore("|").trim() }
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val devideoIframe = doc.selectFirst("iframe[src*='devideosrc.co']")?.attr("src")

        val isSeries = url.contains("/serie/")
        if (isSeries) {
            val imdb = devideoIframe?.let { serialImdbRegex.find(it)?.groupValues?.get(1) }
                ?: imdbRegex.find(doc.html())?.value

            val episodes = mutableListOf<Episode>()
            if (imdb != null) {
                for (button in doc.select("button")) {
                    // ownText() liefert "Staffel N" ohne die Episodenzahl im <b>-Kind.
                    val seasonText = button.ownText().trim()
                    if (!seasonText.contains("Staffel")) continue
                    val season = Regex("""Staffel\s*(\d+)""").find(seasonText)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    val count = button.selectFirst("b")?.text()?.toIntOrNull() ?: 0
                    if (count <= 0) continue

                    for (episode in 1..count) {
                        val episodeUrl =
                            "https://devideosrc.co/custom/serial/$imdb/$season/$episode"
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = "Staffel $season Folge $episode"
                                this.season = season
                                this.episode = episode
                            }
                        )
                    }
                }
            }

            // Fallback, falls keine Staffel-Buttons gefunden wurden.
            if (episodes.isEmpty() && !devideoIframe.isNullOrBlank()) {
                episodes.add(
                    newEpisode(devideoIframe) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val dataUrl = devideoIframe ?: url
        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
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
        // `data` ist eine devideosrc-Player-URL (Film) oder devideosrc-URL je Episode.
        return DevideoSrc.load(data, subtitleCallback, callback)
    }
}
