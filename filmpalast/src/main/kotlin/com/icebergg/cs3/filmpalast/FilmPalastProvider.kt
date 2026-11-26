package com.icebergg.cs3.filmpalast

import com.icebergg.cs3.shared.HosterResolver
import com.icebergg.cs3.shared.Requests

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.ErrorLoadingException
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
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CloudStream-Provider für **Filmpalast** (filmpalast.to).
 *
 * Live verifiziert am 22.11.2026 (per DoH, da die Domain in DE DNS-gesperrt ist):
 *  - Startseite: `article.liste` mit `a[href*='/stream/']`; Cover als `img.cover`
 *  - Suche:      `GET /search/title/{query}`. Achtung: Die Suchkarten (`glowliste`)
 *                haben **kein Cover**, nur Sterne-Icons – und für Serien liefert die
 *                Suche **jede Episode als eigenen Treffer** (`…-s03e02`, `…-s03e01`, …).
 *                Deshalb werden Episoden hier auf die Serie zusammengefasst.
 *  - Detail:     `/stream/{slug}` – Hoster stehen direkt im HTML:
 *                `li.hostBg p.hostName` (Name) und `li.streamPlayBtn a.iconPlay` (Play-Link).
 *  - Serien:     `/stream/{serie}-s{staffel}e{episode}`; die Detailseite enthält im
 *                `div#staffelWrapper` die verlinkten Episoden (weitere Staffeln lädt
 *                die Seite per JS über `ul#staffel-nav` nach).
 */
open class FilmPalast : MainAPI() {

    override var mainUrl = BASE
    override var name = "Filmpalast"
    override var lang = "de"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Neu auf Filmpalast",
    )

    private val seriesRegex = Regex("""-s(\d+)e(\d+)""", RegexOption.IGNORE_CASE)
    private val seriesSuffixRegex = Regex("""(?i)[\s\-]*s\d+e\d+$""")

    private var baseResolved = false

    /**
     * `filmpalast.tv` liefert eine JS-Challenge, `filmpalast.to` antwortet direkt.
     */
    private suspend fun ensureBase(): String {
        if (baseResolved) return mainUrl
        for (mirror in MIRRORS) {
            val html = Requests.text("$mirror/") ?: continue
            if (html.contains("/stream/") && html.contains("article")) {
                mainUrl = mirror
                baseResolved = true
                println("[Filmpalast] base=$mirror")
                return mainUrl
            }
            println("[Filmpalast] $mirror liefert keine Stream-Liste")
        }
        baseResolved = true
        println("[Filmpalast] no working mirror, fallback=$mainUrl")
        return mainUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureBase()
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page"
        val doc = Requests.doc(url, referer = "$mainUrl/")
            ?: return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureBase()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Requests.doc("$mainUrl/search/title/$encoded", referer = "$mainUrl/")
            ?: throw ErrorLoadingException("[Filmpalast] Suche fehlgeschlagen: $mainUrl nicht erreichbar")
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureBase()
        val doc = Requests.doc(url, referer = "$mainUrl/")
            ?: throw ErrorLoadingException("[Filmpalast] Detailseite nicht erreichbar: $url")

        val rawTitle = doc.selectFirst("h2.bgDark")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            .ifEmpty { doc.title().substringBefore(" Stream").trim() }

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("img[id^='s_img__']")?.attr("src")
                ?: doc.selectFirst("img.cover")?.attr("src")
        )
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
        val imdb = Regex("""imdb\.com/title/(tt\d+)""").find(doc.html())?.groupValues?.get(1)

        val slug = url.substringAfterLast("/")
        val seriesMatch = seriesRegex.find(slug)

        if (seriesMatch == null) {
            return newMovieLoadResponse(rawTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                if (imdb != null) addImdbId(imdb)
            }
        }

        // Serientitel ohne Episoden-Suffix ("Percy Jackson … S03E01" -> "Percy Jackson …").
        val title = rawTitle.replace(seriesSuffixRegex, "").trim().ifEmpty { rawTitle }
        val baseSlug = slug.replace(Regex("(?i)-s\\d+e\\d+$"), "")

        val episodes = mutableListOf<Episode>()
        val seen = HashSet<String>()

        // 1) Verlinkte Episoden aus dem Staffel-Bereich (enthält i. d. R. alle Folgen, die
        //    ohne JS verfügbar sind).
        val container = doc.selectFirst("div#staffelWrapper") ?: doc.selectFirst("div.streamStaffelWrapper")
        val linkSource = container?.select("a[href*='/stream/']") ?: doc.select("a[href*='/stream/']")
        for (a in linkSource) {
            val link = normalize(a.attr("href")) ?: continue
            val linkSlug = link.substringAfterLast("/")
            if (!linkSlug.startsWith(baseSlug, ignoreCase = true)) continue
            val m = seriesRegex.find(linkSlug) ?: continue
            val s = m.groupValues[1].toIntOrNull() ?: continue
            val e = m.groupValues[2].toIntOrNull() ?: continue
            if (!seen.add("$s-$e")) continue
            episodes.add(newEpisode(link) {
                this.name = "Staffel $s Folge $e"
                this.season = s
                this.episode = e
            })
        }

        // 2) Angeklickte Episode immer enthalten.
        val clickedSeason = seriesMatch.groupValues[1].toIntOrNull() ?: 1
        val clickedEpisode = seriesMatch.groupValues[2].toIntOrNull() ?: 1
        if (seen.add("$clickedSeason-$clickedEpisode")) {
            episodes.add(newEpisode(url) {
                this.name = "Staffel $clickedSeason Folge $clickedEpisode"
                this.season = clickedSeason
                this.episode = clickedEpisode
            })
        }

        println("[Filmpalast] series=$baseSlug episodes=${episodes.size}")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            if (imdb != null) addImdbId(imdb)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull()
            ?: return false

        val streams = collectStreams(doc)
        println("[Filmpalast] page=$data hoster=${streams.size}")

        var found = false
        for ((label, link) in streams) {
            // HosterResolver löst VOE-Kurzlinks, Vorschalt-Seiten (Vidara) und
            // direkte Hoster einheitlich auf.
            val ok = HosterResolver.resolve(link, data, subtitleCallback) { callback(it) }
            println("[Filmpalast] hoster=$label ok=$ok url=$link")
            if (ok) found = true
        }
        return found
    }

    /**
     * Liest die Hoster aus der Detailseite.
     *
     * Jeder Hoster hat eine eigene `<ul class="currentStreamLinks">` mit
     * `p.hostName` und genau einem Play-Button. Der Button trägt je nach Hoster
     * `data-player-url` (z. B. Firestream, wird per JS in den iframe gesetzt)
     * **oder** `href` (z. B. VOE/VIDARA). Beides muss gelesen werden.
     */
    private fun collectStreams(doc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (ul in doc.select("ul.currentStreamLinks")) {
            val name = ul.selectFirst("p.hostName")?.text()?.trim().orEmpty()
            val btn = ul.selectFirst("a.iconPlay") ?: continue
            val raw = btn.attr("data-player-url").trim()
                .ifBlank { btn.attr("href").trim() }
            if (raw.isBlank() || !raw.startsWith("http")) continue
            val url = HosterResolver.normalize(raw)
            out.add((name.ifBlank { hostOf(url) }) to url)
        }
        // Fallback, falls die Seitenstruktur einmal abweicht.
        if (out.isEmpty()) {
            for (btn in doc.select("a.iconPlay")) {
                val raw = btn.attr("data-player-url").trim()
                    .ifBlank { btn.attr("href").trim() }
                if (raw.isBlank() || !raw.startsWith("http")) continue
                val url = HosterResolver.normalize(raw)
                out.add(hostOf(url) to url)
            }
        }
        return out.distinctBy { it.second }
    }


    // ------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------

    /**
     * Wertet die Karten einer Listen-/Suchseite aus.
     *
     * Serien-Episoden (`…-s03e02`) werden zu **einem** Serientreffer zusammengefasst,
     * sonst bestünde die Trefferliste nur aus Einzelfolgen.
     */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (article in doc.select("article.liste")) {
            val a = article.selectFirst("a[href*='/stream/']") ?: continue
            val url = normalize(a.attr("href")) ?: continue

            val title = a.attr("title").trim()
                .ifEmpty { article.selectFirst("h2 a")?.text()?.trim().orEmpty() }
                .ifEmpty { a.text().trim() }
            if (title.isEmpty()) continue

            val poster = posterOf(article)
            val slug = url.substringAfterLast("/")
            val m = seriesRegex.find(slug)

            if (m != null) {
                // Auf Serie reduzieren: ein Treffer pro Serie, nicht pro Episode.
                val baseSlug = slug.replace(Regex("(?i)-s\\d+e\\d+$"), "")
                if (!seen.add("series:$baseSlug")) continue
                val seriesTitle = title.replace(seriesSuffixRegex, "").trim().ifEmpty { title }
                out.add(newTvSeriesSearchResponse(seriesTitle, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } else {
                if (!seen.add("movie:$url")) continue
                out.add(newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }
        return out
    }

    /**
     * Cover eines Karten-Elements.
     *
     * Die Suchkarten enthalten **kein** Cover, dafür Sterne-Icons aus
     * `/themes/downloadarchive/`. Diese dürfen nicht als Poster durchgehen –
     * sonst zeigt die App überall Sterne statt Bildern.
     */
    private fun posterOf(article: org.jsoup.nodes.Element): String? {
        val candidates = article.select("img")
        for (img in candidates) {
            val raw = img.attr("data-src").ifBlank { img.attr("src") }
            if (raw.isBlank()) continue
            val lower = raw.lowercase()
            if (lower.contains("themes/downloadarchive")) continue
            if (lower.contains("star_on") || lower.contains("star_off")) continue
            if (lower.contains("blank.png")) continue
            return fixUrlNull(raw)
        }
        return null
    }

    /** Macht `//host/stream/x`, `/stream/x` und absolute URLs vergleichbar. */
    private fun normalize(raw: String): String? {
        if (raw.isBlank()) return null
        val abs = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> fixUrl(raw)
        }
        return abs.takeIf { it.contains("/stream/") }
    }

    private fun hostOf(url: String): String =
        Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: "Hoster"

    companion object {
        private const val BASE = "https://filmpalast.to"

        /**
         * `filmpalast.tv` ist nur eine JS-Challenge (JWT via `window.location.replace`)
         * und wird deshalb nicht als Mirror verwendet.
         */
        private val MIRRORS = listOf(
            "https://filmpalast.to",
        )
    }
}
