package com.icebergg.cs3.megakino

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

/**
 * CloudStream-Provider für **MegaKino** (Filme & Serien, DLE-basiert).
 *
 * MegaKino wechselt die TLD fast täglich. Die aktuelle Basis-Domain wird
 * deshalb über einen Redirect auf [mainUrl] ermittelt ([getActualMainUrlAndCookies]).
 */
open class Megakino : MainAPI() {
    /**
     * Getestete Mirrors (Stand 22.11.2026):
     *  - `megakino.vip` → echte Seite (HTTP 200, WordPress mit `/filme/`)
     *  - `megakino.foo` → gleiche Seite (Kanonical der `.vip`-Domain)
     *  - `megakino4.to` → nicht mehr auflösbar (`megakino.co`/`.to` sind Parkseiten)
     */
    private val MIRRORS = listOf(
        "https://megakino.vip",
        "https://megakino.foo",
    )

    private var resolvedBase: String? = null

    /** Liefert die erste Domain, die tatsächlich Filme ausliefert. */
    private suspend fun resolveBase(): String {
        resolvedBase?.let { return it }
        for (mirror in MIRRORS) {
            val html = runCatching { app.get("$mirror/filme").text }.getOrNull() ?: continue
            if (html.contains("/filme/")) {
                resolvedBase = mirror
                println("[MegaKino] base=$mirror")
                return mirror
            }
        }
        resolvedBase = mainUrl
        println("[MegaKino] no working mirror, fallback=$mainUrl")
        return mainUrl
    }

    @Deprecated("Nicht direkt verwenden – stattdessen getActualMainUrlAndCookies() nutzen.")
    override var mainUrl = "https://megakino.vip"
    override var name = "MegaKino"
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)

    override val mainPage = mainPageOf(
        "" to "Trends",
        "kinofilme" to "Kinofilme",
        "serials" to "Serien",
        "documentary" to "Dokumentationen",
    )

    private var urlCookieCache: Pair<Map<String, String>, String>? = null

    /**
     * Folgt dem Redirect auf [mainUrl], um die aktuelle Basis-Domain von
     * MegaKino zu ermitteln, und liefert zusätzlich die nötigen Cookies.
     */
    @Suppress("DEPRECATION")
    private suspend fun getActualMainUrlAndCookies(): Pair<Map<String, String>, String> {
        urlCookieCache?.let { return it }

        val base = resolveBase()
        val actualMainUrl = runCatching { app.get(base).url.trimEnd('/') }.getOrNull()
            ?: return emptyMap<String, String>() to base
        val cookies = runCatching { app.get("$actualMainUrl/index.php?yg=token").cookies }
            .getOrDefault(emptyMap())

        return cookies to actualMainUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (cookies, actualMainUrl) = getActualMainUrlAndCookies()

        val document = runCatching {
            app.get(
                "$actualMainUrl/${request.data}/page/$page",
                cookies = cookies
            ).document
        }.getOrNull() ?: return newHomePageResponse(
            list = HomePageList(request.name, emptyList(), isHorizontalImages = false),
            hasNext = false
        )
        val home = document.select("#dle-content > a").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3").text()
        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val (cookies, actualMainUrl) = getActualMainUrlAndCookies()

        val data =
            mapOf("do" to "search", "subaction" to "search", "story" to query.replace(" ", "+"))
        val response = runCatching {
            app.post(actualMainUrl, data = data, cookies = cookies.orEmpty()).document
        }.getOrNull() ?: return emptyList()

        return response.select("a.poster.grid-item").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (cookies, actualMainUrl) = getActualMainUrlAndCookies()
        val fixedUrl = runCatching { actualMainUrl + URI.create(url).rawPath }.getOrNull()
            ?: return null

        val document = runCatching {
            app.get(fixedUrl, cookies = cookies.orEmpty()).document
        }.getOrNull() ?: return null
        val title = document.selectFirst("div.page__subcols.d-flex h1")?.text() ?: "Unknown"
        val description = document.selectFirst("div.page__text")?.text()
        val poster =
            fixUrl(document.select("div.pmovie__poster.img-fit-cover img").attr("data-src"))
        val year = document.select("div.pmovie__year > span:nth-child(2)").text().toIntOrNull()
        val genres = document.selectFirst("div.pmovie__genres")?.text()
            ?.split(" / ")?.map { it.trim() } ?: emptyList()

        val trailer = document.select("link[itemprop=embedUrl]").attr("href")

        val typeTag = document.select("div.pmovie__genres").text()
        val type = if (typeTag.contains("Filme")) TvType.Movie else TvType.TvSeries
        val related = document.select("section.pmovie__related a.poster").map {
            it.toSearchResult()
        }

        return if (type == TvType.TvSeries) {
            val episodes = document.select("select[name=pmovie__select-items]")
                .filter { it.id().startsWith("ep") }
                .map {
                    val episodeNumber = it.id()
                        .takeLastWhile { c -> c.isDigit() }
                        .toIntOrNull()
                    val episodeStreamLinks = it.select("option").map { option ->
                        option.attr("value")
                    }

                    newEpisode(episodeStreamLinks.toJson()) {
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                this.recommendations = related
                addTrailer(trailer)
            }
        } else {
            val streamLinks = document.select("div.pmovie__player iframe")
                .map { it.attr("src").ifEmpty { it.attr("data-src") } }.toJson()

            newMovieLoadResponse(title, url, TvType.Movie, streamLinks) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                this.recommendations = related
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<Links>(data)
        for (link in links) {
            loadExtractor(link, subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }

    class Links : ArrayList<String>()
}
