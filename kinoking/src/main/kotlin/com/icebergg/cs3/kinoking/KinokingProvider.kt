package com.icebergg.cs3.kinoking

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
 * CloudStream-Provider für **KinoKing**.
 *
 * Der Seitenaufbau ist serverseitig gerendert (Tailwind-Markup). Der Katalog
 * nutzt Karten mit `data-id`, `data-type`, `data-title` und `data-img`; die
 * Ziel-URL steckt im `onclick="trackHistoryAndGo(this, 'movie.php?id=…')"`.
 *
 *  - **Filme** (`movie.php?id=…`) enthalten die komplette Hoster-Liste als
 *    `const SERVERS = [{ "mirrors": ["https://voe.sx/e/…"] }, …]`.
 *  - **Serien** (`series.php?id=…`) enthalten `allEpisodesData = [{…}]` mit
 *    Staffel, Episodennummer und `video_links` je Episode.
 *
 * Beides steht direkt im HTML, deshalb ist weder der Ad-Blocker-Overlay noch
 * der Cloudflare-Turnstile des Players ein Hindernis.
 */
open class Kinoking : MainAPI() {
    override var mainUrl = "https://kinoking.cc"
    override var name = "KinoKing"
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "?genre=current-movies" to "Neue Filme",
        "?genre=current-series" to "Neue Serien",
        "?genre=recently-added" to "Zuletzt hinzugefügt",
        "?genre=top10-movies" to "Top 10 Filme",
        "?random&view=list" to "Zufall",
    )

    companion object {
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept-Language" to "de-DE,de;q=0.9,en;q=0.7",
        )

        private val TRACK_TARGET = Regex("""trackHistoryAndGo\(\s*this\s*,\s*'([^']+)'\s*\)""")

        private val VOE_SHORT = Regex("""^(https?://(?:www\.)?voe\.(?:sx|com|to))/([A-Za-z0-9]{6,})$""")
        private val VOE_PLAIN = Regex("""(voe\.(?:sx|com|to))/([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
        private val VIDOZA_PLAIN = Regex("""vidoza\.net/([a-z0-9]+)\.html""", RegexOption.IGNORE_CASE)
        private val TITLE_YEAR = Regex("""^(.*?)\s*\((\d{4})\)""")

        /** Hoster, die selbst keine abspielbare Quelle liefern (Aggregatoren). */
        private val AGGREGATORS = listOf(
            "meinecloud.click",
            "vidsync.xyz",
            "cinesrc.st",
            "bysezejataos.com",
            "kinoking.cc",
        )
    }

    /** Deserialisierungs-Hilfe für die als JSON gespeicherten Stream-Listen. */
    class Links : ArrayList<String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var path = request.data
        if (page > 1 && !path.contains("random")) {
            path += if (path.contains('?')) "&page=$page" else "?page=$page"
        }
        val document = app.get(
            "$mainUrl/${path.removePrefix("/")}",
            headers = HEADERS,
            referer = mainUrl,
        ).document
        val items = parseCards(document)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get(
            "$mainUrl/index.php?search=$encoded",
            headers = HEADERS,
            referer = mainUrl,
        ).document
        return parseCards(document)
    }

    /**
     * Liest die Karten des Katalogs und der Suche. Karten ohne Ziel-Handler
     * (z. B. kommende Filme) werden übersprungen.
     */
    private fun parseCards(document: Document): List<SearchResponse> =
        document.select("div[data-id][data-type]")
            .mapNotNull { card -> card.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val target = TRACK_TARGET.find(attr("onclick"))?.groupValues?.get(1)
            ?: TRACK_TARGET.find(selectFirst("[onclick*=trackHistoryAndGo]")?.attr("onclick").orEmpty())
                ?.groupValues?.get(1)
            ?: return null

        val title = attr("data-title").trim().ifBlank {
            selectFirst("p")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val posterRaw = attr("data-img").ifBlank {
            selectFirst("img[src]")?.attr("src").orEmpty()
        }

        val isSeries = attr("data-type").equals("series", ignoreCase = true)
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, fixUrl(target), type) {
            this.posterUrl = posterRaw.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = HEADERS, referer = mainUrl).document
        val html = document.outerHtml()

        return if (url.contains("series.php")) {
            loadSeries(url, document, html)
        } else {
            loadMovie(url, document, html)
        }
    }

    private suspend fun loadMovie(url: String, document: Document, html: String): LoadResponse {
        val jsonLd = scriptJson(document) { it.contains("\"@type\": \"Movie\"") }
        val ldMap = jsonLd?.let { runCatching { parseJson<Map<String, Any?>>(it) }.getOrNull() }

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeUnless { it.isBlank() }
            ?: (ldMap?.get("name") as? String)
            ?: document.title().substringBefore(" (").trim()

        val plot = (ldMap?.get("description") as? String)?.trim()
        val poster: String? = (ldMap?.get("image") as? String)?.let { fixUrl(it) }
            ?: document.selectFirst("img[src*=image.tmdb.org]")?.attr("src")?.let { fixUrl(it) }
        val year = (ldMap?.get("dateCreated") as? String)?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()

        val links = extractServerLinks(html)
        return newMovieLoadResponse(title, url, TvType.Movie, links.toJson()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun loadSeries(url: String, document: Document, html: String): LoadResponse {
        val pageTitle = document.title()
        val match = TITLE_YEAR.find(pageTitle)
        val title = match?.groupValues?.get(1)?.trim()?.takeUnless { it.isBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeUnless { it.isBlank() }
            ?: pageTitle.substringBefore(" - ").trim()
        val year = match?.groupValues?.get(2)?.toIntOrNull()

        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeUnless { it.isBlank() }
        val poster: String? = document.selectFirst("img[src*=image.tmdb.org]")?.attr("src")
            ?.let { fixUrl(it) }

        val episodesJson = extractJsonArray(html, "allEpisodesData")
        val entries = episodesJson
            ?.let { runCatching { parseJson<List<Map<String, Any?>>>(it) }.getOrNull() }
            .orEmpty()

        val episodes = entries.mapNotNull { entry ->
            val links = linksOf(entry["video_links"])
            if (links.isEmpty()) return@mapNotNull null

            val season = (entry["season_number"] as? Number)?.toInt()
            val number = (entry["episode_number"] as? Number)?.toInt()
            val name = (entry["name"] as? String)?.trim()
            val still = (entry["still_path"] as? String)?.trim()

            newEpisode(links.toJson()) {
                this.season = season
                this.episode = number
                this.name = name
                this.posterUrl = still?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    /** Sammelt alle Mirror-URLs aus `const SERVERS = [...]`. */
    private fun extractServerLinks(html: String): List<String> {
        val json = extractJsonArray(html, "const SERVERS") ?: return emptyList()
        val servers = runCatching { parseJson<List<Map<String, Any?>>>(json) }.getOrNull()
            .orEmpty()

        return servers
            .flatMap { linksOf(it["mirrors"]) }
            .filter { it.startsWith("http") }
            .filterNot { url -> AGGREGATORS.any { url.contains(it, ignoreCase = true) } }
            .distinct()
    }

    private fun linksOf(value: Any?): List<String> = when (value) {
        is String -> value
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.startsWith("http") }
        is List<*> -> value.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.startsWith("http") } }
        else -> emptyList()
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

    /**
     * Liest ein eingebettetes JSON-Array ab [marker] bis zum Ende des
     * umgebenden `<script>`-Blocks. Nötig, weil die Arrays verschachtelte
     * Arrays enthalten und ein naiver Regex zu früh abbrechen würde.
     */
    private fun extractJsonArray(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val start = html.indexOf('[', markerIndex)
        if (start < 0) return null
        val scriptEnd = html.indexOf("</script>", markerIndex).let {
            if (it < 0) html.length else it
        }
        val end = html.lastIndexOf(']', scriptEnd)
        if (end < start) return null
        return html.substring(start, end + 1)
    }

    /** Liefert den Inhalt des ersten JSON-LD-Blocks, der [predicate] erfüllt. */
    private fun scriptJson(document: Document, predicate: (String) -> Boolean): String? =
        document.select("script")
            .filter { it.attr("type").equals("application/ld+json", ignoreCase = true) }
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { predicate(it) }
}
