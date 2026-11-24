package com.icebergg.cs3.streamcloud

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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder

/**
 * CloudStream-Provider für **StreamCloud** (streamcloud.date).
 *
 * Die Seite läuft auf einer Next.js/React-Server-Components-Plattform:
 *  - Suche:     `GET /suche?q={query}` (serverseitig gerendert)
 *  - Übersicht: `GET /filme` (einzige serverseitig gerenderte Liste)
 *  - Filme:     `/filme/{id-slug}`, Serie: `/serie/{id-slug}`
 *  - Player:    `iframe` von `devideosrc.co` → `/movie/{imdb}` bzw.
 *               `/custom/serial/{imdb}/{staffel}/{episode}`
 *  - Staffeln:  `<button>Staffel N<b>Episodenzahl</b></button>`
 *  - Folgentitel stehen nur für die im HTML gerenderte Staffel in
 *    `div.sc-eplista button.sc-epr > h3` („Folge 1 – Der Einstieg“).
 */
open class StreamCloudProvider : MainAPI() {

    override var mainUrl = DEFAULT_BASE
    override var name = "StreamCloud"
    override var lang = "de"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/filme" to "Filme",
    )

    private val detailRegex = Regex(""".*/(?:filme|serie)/.+""")
    private val imdbRegex = Regex("""tt\d{7,}""")
    private val serialImdbRegex = Regex("""devideosrc\.co/(?:custom/)?serial/(tt\d+)""")
    private val serialSeasonRegex = Regex("""devideosrc\.co/(?:custom/)?serial/tt\d+/(\d+)/""")
    private val folgeTitleRegex = Regex("""^Folge\s*\d+\s*[–-]\s*(.+)$""")

    private var baseResolved = false

    private suspend fun ensureBase(): String {
        if (baseResolved) return mainUrl
        for (mirror in MIRRORS) {
            val ok = runCatching {
                app.get("$mirror/filme").text.contains("/filme/")
            }.getOrDefault(false)
            if (ok) {
                mainUrl = mirror
                baseResolved = true
                println("[StreamCloud] base=$mirror")
                return mainUrl
            }
        }
        baseResolved = true
        println("[StreamCloud] no mirror reachable, fallback=$mainUrl")
        return mainUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureBase()
        val url = if (page <= 1) "$mainUrl${request.data}" else "$mainUrl${request.data}?page=$page"
        val doc = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList())

        val items = doc.select("a[href*='/filme/']").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val link = fixUrl(href)
            if (!detailRegex.matches(link)) return@mapNotNull null

            val img = a.selectFirst("img")
            val title = a.attr("title").trim()
                .ifEmpty { img?.attr("alt")?.trim().orEmpty() }
                .ifEmpty { a.text().trim() }
            if (title.isEmpty()) return@mapNotNull null

            val poster = img?.let { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }

            newMovieSearchResponse(title, link, TvType.Movie) {
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

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val url = fixUrl(href)
            if (!detailRegex.matches(url)) continue
            if (!seen.add(url)) continue

            // `title` liefert den sauberen Namen; der Linktext enthält Rating/Serie-Tag.
            val title = a.attr("title").trim().ifEmpty { a.text().trim() }
            if (title.isEmpty()) continue

            val img = a.selectFirst("img")
            val poster = img?.let { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }

            results.add(
                if (url.contains("/serie/")) {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            )
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

        val devideoIframe = doc.selectFirst("iframe[src*='devideosrc.co']")?.attr("src")

        val isSeries = url.contains("/serie/")
        if (isSeries) {
            val imdb = devideoIframe?.let { serialImdbRegex.find(it)?.groupValues?.get(1) }
                ?: imdbRegex.find(doc.html())?.value

            // Titel der im HTML gerenderten Staffel (meist Staffel 1).
            val renderedSeason = devideoIframe
                ?.let { serialSeasonRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val renderedTitles = HashMap<Int, String>()
            for (ep in doc.select("div.sc-eplista button.sc-epr, button.sc-epr")) {
                val number = ep.selectFirst("span.sc-epr-n")?.text()?.trim()?.toIntOrNull()
                    ?: continue
                val heading = ep.selectFirst("h3")?.text()?.trim().orEmpty()
                if (heading.isEmpty()) continue
                val clean = folgeTitleRegex.find(heading)?.groupValues?.get(1)?.trim()
                renderedTitles[number] = if (clean.isNullOrEmpty()) heading else clean
            }
            println("[StreamCloud] imdb=$imdb renderedSeason=$renderedSeason titles=${renderedTitles.size}")

            val episodes = mutableListOf<Episode>()
            val seen = HashSet<String>()
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
                        // Staffel-Buttons können mehrfach (mobil/desktop) im DOM stehen.
                        if (!seen.add("$season-$episode")) continue
                        val episodeUrl =
                            "https://devideosrc.co/custom/serial/$imdb/$season/$episode"

                        val realTitle = renderedTitles[episode]
                            ?.takeIf { season == renderedSeason || (renderedSeason == null && season == 1) }
                        val episodeName = if (!realTitle.isNullOrBlank()) {
                            "Folge $episode – $realTitle"
                        } else {
                            "Staffel $season Folge $episode"
                        }

                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = episodeName
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
                if (imdb != null) addImdbId(imdb)
            }
        }

        val dataUrl = devideoIframe ?: url
        val movieImdb = imdbRegex.find(doc.html())?.value
        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
            this.posterUrl = poster
            this.plot = plot
            if (movieImdb != null) addImdbId(movieImdb)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Normalfall: `data` ist bereits eine devideosrc-Player-URL.
        if (data.startsWith("https://devideosrc.co")) {
            if (DevideoSrc.load(data, subtitleCallback, callback)) return true
        }

        // Fallback: `data` ist eine Webseiten-URL → iframe suchen.
        val doc = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull()
            ?: return false
        val devideo = doc.selectFirst("iframe[src*='devideosrc.co']")?.attr("src")
        if (!devideo.isNullOrBlank() && DevideoSrc.load(devideo, subtitleCallback, callback)) {
            return true
        }

        // Fallback: Hoster-Links der Seite (inkl. VOE-Kurzlink und iframe-Kette).
        var found = false
        for (el in doc.select("[data-link], a[href]")) {
            val raw = el.attr("data-link").ifBlank { el.attr("href") }
            if (!raw.startsWith("http")) continue
            if (HosterResolver.resolve(raw, data, subtitleCallback) { callback(it) }) found = true
        }
        return found
    }

    companion object {
        private const val DEFAULT_BASE = "https://streamcloud.date"

        /**
         * Nur `streamcloud.date` liefert die echte Plattform. Weitere Kandidaten
         * (`.to`, `.cx`, `.sx`, `.eu`) sind geparkte Domains und werden durch die
         * Inhaltsprüfung in [ensureBase] aussortiert.
         */
        private val MIRRORS = listOf(
            "https://streamcloud.date",
            "https://streamcloud.to",
        )
    }
}
