package com.icebergg.cs3.movie2k

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * CloudStream-Provider für **Movie2K**.
 *
 * Der Seitenaufbau ist klassisches, serverseitig gerendertes HTML (XHTML-Tabellen).
 * Die Detailseite enthält die Hoster-Links bereits im Quelltext:
 *
 *   `<a href="https://vinovo.to/d/…" onclick="return loadMirror('…')">`
 *   `<option value="https://vinovo.to/d/…">`
 *
 * Das Seiten-JavaScript schreibt einige Hoster-Pfade vor dem Abspielen um
 * (`/d/` -> `/e/`, Voe ohne `/e/`, Vidoza `/embed-`). Diese Normalisierung
 * übernimmt [normalizeHoster], damit die Extraktoren der App greifen.
 */
open class Movie2k : MainAPI() {
    override var mainUrl = "https://movie2k.cx"
    override var name = "Movie2K"
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Kinofilme",
        "movies" to "Alle Filme",
        "tv" to "Serien",
        "tv/all" to "Alle Serien",
    )

    companion object {
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept-Language" to "de-DE,de;q=0.9,en;q=0.7",
        )

        private val MIRROR_ONCLICK = Regex("""loadMirror\(\s*['"]([^'"]+)['"]\s*\)""")
        private val VOE_SHORT = Regex("""^(https?://(?:www\.)?voe\.(?:sx|com|to))/([A-Za-z0-9]{6,})$""")
        private val VOE_PLAIN = Regex("""(voe\.(?:sx|com|to))/([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
        private val VIDOZA_PLAIN = Regex("""vidoza\.net/([a-z0-9]+)\.html""", RegexOption.IGNORE_CASE)
    }

    /** Deserialisierungs-Hilfe für die als JSON gespeicherten Stream-Listen. */
    class Links : ArrayList<String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            "$mainUrl/${request.data}",
            headers = HEADERS,
            referer = mainUrl
        ).document
        return newHomePageResponse(
            HomePageList(request.name, parseCards(document), isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get(
            "$mainUrl/search?q=$encoded",
            headers = HEADERS,
            referer = mainUrl
        ).document
        return parseCards(document)
    }

    /**
     * Pro Film ist eine Tabellenzeile die Karte: links das Poster
     * (`img[src*=image.tmdb.org]`), rechts in `h2 > a` der Titel. Unter dem Titel
     * gibt es zusätzlich einen zweiten Link „Ansehen auf …" auf dieselbe URL.
     *
     * Früher wurde jeder `/stream/`-Link einzeln ausgewertet – dadurch kam der
     * Titel aus dem einen Link, das Poster (außerhalb des `<a>`) ging verloren
     * und beide Links erzeugten dieselbe Karte. Jetzt wird pro `tr` genau eine
     * Karte gebaut, mit Poster und Titel aus derselben Zeile.
     */
    private fun parseCards(document: Document): List<SearchResponse> =
        document.select("tr")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleLink = select("a[href*='/stream/']").firstOrNull { link ->
            val text = link.text().trim()
            text.isNotBlank() &&
                !text.startsWith("Ansehen", ignoreCase = true) &&
                !text.startsWith("Stream watch", ignoreCase = true)
        } ?: return null

        val href = fixUrl(titleLink.attr("href"))
        if (!href.contains("/stream/")) return null

        val title = titleLink.text().trim().ifBlank {
            selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val posterImg = selectFirst("img[src*='image.tmdb.org']")
            ?: select("img").firstOrNull {
                val alt = it.attr("alt")
                alt.isNotBlank() && !alt.equals("Deutsch", ignoreCase = true) &&
                    !alt.equals("Logo", ignoreCase = true)
            }
        val poster: String? = posterImg?.let { img ->
            val raw = img.attr("src").ifBlank { img.attr("data-src") }
            if (raw.isBlank()) null else fixUrl(raw)
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = HEADERS, referer = mainUrl).document

        val title = document.selectFirst("#maincontentnew h1")
            ?.text()
            ?.substringBefore("Qualität")
            ?.trim()
            ?.takeUnless { it.isBlank() }
            ?: document.title().substringBefore(" online").trim()

        val plot = document.selectFirst("div.beschreibung")?.text()?.trim()
        val posterRaw =
            document.selectFirst("#maincontentnew img[src*=image.tmdb.org]")?.attr("src")
                ?: document.selectFirst("#maincontentnew img[data-src]")?.attr("data-src")
        val posterUrl: String? = if (posterRaw.isNullOrBlank()) null else fixUrl(posterRaw)

        val details = document.selectFirst("#details")?.text().orEmpty()
        val year = Regex("""Land/Jahr:\s*[^/]*/\s*(\d{4})""").find(details)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?:Jahr|Released):\s*(\d{4})""").find(details)
                ?.groupValues?.get(1)?.toIntOrNull()

        val genres = document.select("#details a[href^=/movies/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val isSeries = document.select("[data-episode-id]").isNotEmpty()
        return if (isSeries) {
            val episodes = loadEpisodes(url, document, posterUrl)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            val streamUrls = extractStreams(document.selectFirst("#movie-quality-section"))
            newMovieLoadResponse(title, url, TvType.Movie, streamUrls.toJson()) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }
    }

    /**
     * Liest alle Hoster-Ziele aus einem Bereich. Unterstützt sowohl `href`
     * als auch `option[value]` sowie den `loadMirror('…')`-Aufruf, den die
     * Seite für Serien-Episoden verwendet.
     */
    private fun extractStreams(scope: Element?): List<String> {
        if (scope == null) return emptyList()
        return scope.select("a[href], a[data-mirror], option[value]")
            .mapNotNull { el ->
                val href = el.attr("href")
                val value = el.attr("value")
                val onclick = el.attr("onclick")

                href.takeIf { it.startsWith("http") }
                    ?: value.takeIf { it.startsWith("http") }
                    ?: MIRROR_ONCLICK.find(onclick)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            }
            .filter { !it.contains("movie2k.cx") && !it.contains("imdb.com") }
            .distinct()
    }

    /**
     * Sammelt die Episoden aller Staffeln. Movie2K liefert pro Staffel eine
     * eigene Seite (`?season=N`); jede Episode ist eine Zeile mit
     * `data-episode-id` und darin liegenden Mirror-Links.
     */
    private suspend fun loadEpisodes(
        url: String,
        firstDocument: Document,
        posterUrl: String?,
    ): List<Episode> {
        val baseUrl = url.substringBefore("?")
        val seasons = firstDocument.select("#season-select option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .distinct()

        val documents = if (seasons.size <= 1) {
            listOf(firstDocument)
        } else {
            seasons.map { season ->
                runCatching {
                    app.get(
                        "$baseUrl?season=$season",
                        headers = HEADERS,
                        referer = mainUrl
                    ).document
                }.getOrDefault(firstDocument)
            }
        }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        for (document in documents) {
            val options = document.select("#episode-select option[value]")
            if (options.isNotEmpty()) {
                for (option in options) {
                    val episodeId = option.attr("value")
                    if (episodeId.isBlank() || !seen.add(episodeId)) continue

                    val row = document.selectFirst("[data-episode-id=\"$episodeId\"]") ?: continue
                    val urls = extractStreams(row)
                    if (urls.isEmpty()) continue

                    val episodeNumber = Regex("""(?i)\bE(\d+)\b""").find(option.text())
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: (episodes.size + 1)

                    episodes += newEpisode(urls.toJson()) {
                        this.episode = episodeNumber
                        this.posterUrl = posterUrl
                    }
                }
            } else {
                for (row in document.select("[data-episode-id]")) {
                    val episodeId = row.attr("data-episode-id")
                    if (episodeId.isBlank() || !seen.add(episodeId)) continue

                    val urls = extractStreams(row)
                    if (urls.isEmpty()) continue

                    val episodeNumber = Regex("""(\d+)""").find(episodeId)
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: (episodes.size + 1)

                    episodes += newEpisode(urls.toJson()) {
                        this.episode = episodeNumber
                        this.posterUrl = posterUrl
                    }
                }
            }
        }

        return episodes.sortedBy { it.episode ?: 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val urls = runCatching { parseJson<Links>(data) }.getOrNull().orEmpty()
        var found = false
        for (raw in urls) {
            val url = normalizeHoster(raw)
            val ok = runCatching {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }.getOrDefault(false)
            if (ok) found = true
        }
        return found
    }

    /**
     * Bildet die Umschreibungen des Seiten-JavaScripts nach.
     */
    private fun normalizeHoster(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("//")) url = "https:$url"

        url = url.replace("vinovo.to/d/", "vinovo.to/e/")
        url = url.replace("firestream.to/d/", "firestream.to/e/")
        url = url.replace("firestream.to/v/", "firestream.to/e/")
        url = url.replace("doodstream.com/d/", "doodstream.com/e/")
        url = url.replace("dsvplay.com/d/", "dsvplay.com/e/")

        VOE_SHORT.find(url)?.let { match ->
            url = "${match.groupValues[1]}/e/${match.groupValues[2]}"
        }
        if (url.contains("voe.") && !url.contains("/e/")) {
            url = VOE_PLAIN.replace(url) { match ->
                "${match.groupValues[1]}/e/${match.groupValues[2]}"
            }
        }

        if (url.contains("vidoza.net/") && !url.contains("vidoza.net/embed-")) {
            url = VIDOZA_PLAIN.replace(url) { match ->
                "vidoza.net/embed-${match.groupValues[1]}.html"
            }
        }

        return url
    }
}
