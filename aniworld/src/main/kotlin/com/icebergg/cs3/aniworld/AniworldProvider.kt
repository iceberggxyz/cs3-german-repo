package com.icebergg.cs3.aniworld

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * CloudStream-Provider für **AniWorld.to** (Animes & Serien).
 *
 * Struktur (live verifiziert):
 *  - Suche:   `POST /ajax/search` mit Form-Parameter `keyword`.
 *  - Detail:  Cover unter `div.seriesCoverBox`, Episoden in
 *             `div#stream > ul`, Staffel-Inhalte unter `table.seasonEpisodesList`.
 *  - Streams: Hoster-Links in `div.hosterSiteVideo ul li` (VOE, Doodstream, …).
 */
open class AniworldProvider : MainAPI() {
    override var mainUrl = "https://aniworld.to"
    override var name = "AniWorld"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val hasMainPage = true
    override var lang = "de"

    private var baseResolved = false

    /**
     * Probiert die live getesteten Mirrors durch und setzt [mainUrl] auf die
     * erste Domain, die einen echten Katalog liefert (`div.carousel`).
     */
    private suspend fun ensureBase(): String {
        if (baseResolved) return mainUrl
        for (mirror in MIRRORS) {
            val html = runCatching { app.get("$mirror/").text }.getOrNull() ?: continue
            if (html.contains("carousel")) {
                mainUrl = mirror
                baseResolved = true
                println("[AniWorld] base=$mirror")
                return mainUrl
            }
        }
        baseResolved = true
        println("[AniWorld] no working mirror, fallback=$mainUrl")
        return mainUrl
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureBase()
        // Kein eingebauter Schutz durch die App: Fehler hier defensiv abfangen.
        val document = runCatching { app.get(mainUrl).document }.getOrNull()
            ?: return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)

        val homePageLists = document.select("div.carousel").map { ele ->
            val header = ele.selectFirst("h2")?.text() ?: return@map null

            val items = ele.select("div.coverListItem").mapNotNull {
                it.toSearchResult()
            }
            HomePageList(header, items).takeIf { items.isNotEmpty() }
        }.filterNotNull()

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureBase()
        val resp = runCatching {
            app.post(
                "$mainUrl/ajax/search",
                data = mapOf("keyword" to query),
                referer = "$mainUrl/search",
                headers = mapOf(
                    "x-requested-with" to "XMLHttpRequest"
                )
            )
        }.getOrNull() ?: return emptyList()

        val items = runCatching { resp.parsed<SearchResp>() }.getOrNull() ?: return emptyList()
        return items.filter {
            !it.link.contains("episode-") && it.link.contains("/stream")
        }.map {
            newTvSeriesSearchResponse(
                it.title?.replace(Regex("</?em>"), "") ?: "",
                fixUrl(it.link),
                TvType.TvSeries
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url).document }.getOrNull() ?: return null

        val title = document.selectFirst("div.series-title span")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
        val tags = document.select("div.genres li a").map { it.text() }
        val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
        val description = document.select("p.seri_des").text()
        val actors =
            document.select("li:contains(Schauspieler:) ul li a").map { it.select("span").text() }

        val episodes = document.select("div#stream > ul:first-child li").mapNotNull { ele ->
            val seasonLink = ele.selectFirst("a") ?: return@mapNotNull null
            val seasonNumber = seasonLink.text().toIntOrNull()
            // Eine defekte Staffelseite darf nicht die ganze Serie verhindern.
            val seasonDocument = runCatching {
                app.get(fixUrl(seasonLink.attr("href"))).document
            }.getOrNull() ?: return@mapNotNull emptyList()

            seasonDocument.select("table.seasonEpisodesList tbody tr").map { eps ->
                newEpisode(
                    fixUrl(eps.selectFirst("a")?.attr("href") ?: return@map null),
                ) {
                    this.episode = eps.selectFirst("meta[itemprop=episodeNumber]")
                        ?.attr("content")?.toIntOrNull()
                    this.name = eps.selectFirst(".seasonEpisodeTitle")?.text()
                    this.season = seasonNumber
                }
            }.filterNotNull()
        }.flatten()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.name = title
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags

            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data).document }.getOrNull() ?: return false
        document.select("div.hosterSiteVideo ul li").map {
            Triple(
                it.attr("data-lang-key"),
                it.attr("data-link-target"),
                it.select("h4").text()
            )
        }.amap { item ->
            val redirectUrl = runCatching { app.get(fixUrl(item.second)).url }.getOrNull()
            if (redirectUrl != null) {
                val lang = item.first.getLanguage(document)
                val name = "${item.third} [$lang]"

                HosterResolver.resolve(redirectUrl, data, subtitleCallback) { link ->
                        val linkWithFixedName = runBlocking {
                            newExtractorLink(
                                source = item.third,
                                name = name,
                                url = link.url
                            ) {
                                referer = link.referer
                                quality = link.quality
                                type = link.type
                                headers = link.headers
                                extractorData = link.extractorData
                            }
                        }
                        callback.invoke(linkWithFixedName)
                    }
            }
        }

        return true
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun String.getLanguage(document: Document): String? {
        return document.selectFirst("div.changeLanguageBox img[data-lang-key=$this]")
            ?.attr("title")?.removePrefix("mit")?.trim()
    }

    companion object {
        /**
         * Live getestet (23.11.2026): `.to`, `.cc` und `.cx` liefern dieselbe Seite,
         * `.sx`/`.gs`/`.cam` loesen nicht auf, `.tv`/`.ws` brechen die Verbindung ab.
         */
        private val MIRRORS = listOf(
            "https://aniworld.to",
            "https://aniworld.cc",
            "https://aniworld.cx",
        )
    }

    private class SearchResp : ArrayList<SearchItem>()

    private data class SearchItem(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )
}
