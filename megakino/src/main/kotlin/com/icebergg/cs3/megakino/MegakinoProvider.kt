package com.icebergg.cs3.megakino

import com.icebergg.cs3.shared.DevideoSrc
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

/**
 * CloudStream-Provider für **MegaKino**.
 *
 * MegaKino betreibt mehrere Installationen mit unterschiedlichen Templates.
 * Beide werden hier unterstützt:
 *
 * | Template | Domain | Katalog | Stream-Quelle |
 * |---|---|---|---|
 * | `popcornie-dark` (DataLife Engine) | `megakino29.com` | `/films/`, `/serials/`, `/kinofilme/` | Iframe pro Hoster, URL in `data-src` |
 * | `megaKino` (DataLife Engine) | `1megakino.cc` | `/kinofilme-online/`, `/serienstream-deutsch/` | `span[data-link]` pro Hoster |
 *
 * Beide liefern **direkte** Hoster-Ziele (`voe.sx/e/…`, `vidara.to/e/…`,
 * `devideosrc.co/movie/tt…`, `frstream.life/e/…`) – im Gegensatz zur alten
 * `megakino.vip`, deren `watch.gxplayer.xyz`-Kette mit `security error` endet.
 *
 * Einschränkung des Mirrors: die **Suche** auf `1megakino.cc` ist durch ein
 * Cloudflare-Turnstile geschützt (HTTP 403 `Just a moment...`). Der Mirror wird
 * daher nur für Startseite, Detailseiten und Streams genutzt; die Suche liefert
 * dort eine leere Liste.
 */
open class Megakino : MainAPI() {

    private companion object {
        /** Primärinstallation (Template `popcornie-dark`). */
        const val DLE_BASE = "https://megakino29.com"

        /** Ausweichinstallation (Template `megaKino`). */
        const val ALT_BASE = "https://1megakino.cc"

        /** Detailseite im Popcornie-Template, z. B. `/films/6667-hokum.html`. */
        val DLE_DETAIL = Regex("""/(?:films|serials)/\d+-[^/]+\.html""")

        /** Detailseite im megaKino-Template, z. B. `/26096-forgotten-island-….html`. */
        val ALT_DETAIL = Regex("""/\d{4,6}-[a-z0-9-]+\.html""")

        /** Episoden-Selects tragen die ID `ep1`, `ep2`, … */
        val EP_ID = Regex("""^ep(\d+)$""")

        val YEAR = Regex("""\b(19|20)\d{2}\b""")
        val IMDB = Regex("""tt\d{6,}""")

        /** YouTube-Trailer und Werbe-Overlays, die nie ein Stream sind. */
        val NON_STREAM = listOf(
            "youtube", "youtu.be", "google", "facebook",
            "warez.cx", "vpnkostenlos", "acscdn", "doubleclick",
        )
    }

    /** Gecachte Session: Cookies (inkl. `yg_token`) und Basis-Domain. */
    private var sessionCache: Pair<Map<String, String>, String>? = null

    /** Aktuell genutzte Basis-Domain, auch für relative Poster-/Link-Pfade. */
    private var base: String? = null

    /** `true`, wenn [base] das megaKino-Template (`span[data-link]`) verwendet. */
    private var altTemplate = false

    override var name = "MegaKino"
    override var mainUrl = DLE_BASE
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)

    override val mainPage = mainPageOf(
        "films" to "Filme",
        "kinofilme" to "Kinofilme",
        "serials" to "Serien",
        "documentary" to "Dokus",
    )

    /**
     * Ermittelt einmalig Domain **und** Session-Cookies.
     *
     * `megakino29.com` schützt jede Seite mit einem Zwei-Schritt-Handshake: ohne
     * Cookie `yg_token` liefert der Server nur ein 243-Byte-Stub
     * (`fetch("/index.php?yg=token").then(() => location.replace(...))`).
     * Der Token wird deshalb vor dem ersten Seitenaufruf geholt.
     */
    private suspend fun session(): Pair<Map<String, String>, String> {
        sessionCache?.let { return it }

        val dleCookies = tokenOf(DLE_BASE)
        val dle = runCatching { app.get("$DLE_BASE/films/", cookies = dleCookies).text }.getOrNull()
        if (dle != null && dle.contains("/films/") && !isStub(dle)) {
            altTemplate = false
            base = DLE_BASE
            val s = dleCookies to DLE_BASE
            sessionCache = s
            println("[MegaKino] base=$DLE_BASE (popcornie)")
            return s
        }

        val alt = runCatching { app.get("$ALT_BASE/kinofilme-online/").text }.getOrNull()
        if (alt != null && alt.contains("data-link")) {
            altTemplate = true
            base = ALT_BASE
            val s = emptyMap<String, String>() to ALT_BASE
            sessionCache = s
            println("[MegaKino] base=$ALT_BASE (megaKino)")
            return s
        }

        println("[MegaKino] keine Domain erreichbar, Fallback=$DLE_BASE")
        altTemplate = false
        base = DLE_BASE
        val s = dleCookies to DLE_BASE
        sessionCache = s
        return s
    }

    /** Holt den `yg_token`-Cookie (Antwort ist ein leeres 204). */
    private suspend fun tokenOf(baseUrl: String): Map<String, String> =
        runCatching { app.get("$baseUrl/index.php?yg=token").cookies }.getOrDefault(emptyMap())

    /** Erneuert den Token (Gültigkeit: 15 Minuten) und liefert die neuen Cookies. */
    private suspend fun refreshToken(baseUrl: String): Map<String, String> {
        val fresh = tokenOf(baseUrl)
        if (fresh.isNotEmpty()) {
            sessionCache = fresh to baseUrl
            println("[MegaKino] token erneuert")
        }
        return fresh
    }

    /** GET über die gecachte Session, mit einem Retry bei abgelaufenem Token. */
    private suspend fun pageOf(url: String): Document? {
        var cookies = session().first
        var body = runCatching { app.get(url, cookies = cookies).text }.getOrNull()

        if (isStub(body)) {
            cookies = refreshToken(base ?: DLE_BASE)
            body = runCatching { app.get(url, cookies = cookies).text }.getOrNull()
        }

        val html = body ?: return null
        return runCatching { org.jsoup.Jsoup.parse(html, url) }.getOrNull()
    }

    /** Erkennt den Token-Stub, der ohne gültigen Cookie ausgeliefert wird. */
    private fun isStub(html: String?): Boolean =
        html == null || (html.length < 800 && html.contains("location.replace"))

    /** Katalog-Pfad je Template. */
    private fun listUrl(baseUrl: String, data: String, page: Int): String {
        val slug = if (!altTemplate) {
            when (data) {
                "kinofilme" -> "kinofilme"
                "serials" -> "serials"
                "documentary" -> "documentary"
                else -> "films"
            }
        } else {
            when (data) {
                "serials" -> "serienstream-deutsch"
                "documentary" -> "dokumentation"
                else -> "kinofilme-online"
            }
        }
        return if (page <= 1) "$baseUrl/$slug/" else "$baseUrl/$slug/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = session().second
        val url = listUrl(baseUrl, request.data, page)

        val document = pageOf(url)
            ?: return newHomePageResponse(
                list = HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false,
            )

        val items = parseCards(document)
        println("[MegaKino] $url -> ${items.size} Einträge")

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val baseUrl = session().second
        if (altTemplate) {
            println("[MegaKino] Suche auf $ALT_BASE durch Cloudflare blockiert")
            return emptyList()
        }

        val cookies = session().first
        val document = runCatching {
            app.post(
                baseUrl,
                // Die Suche erwartet das Wort unkodiert; ApiClient kodiert selbst.
                data = mapOf("do" to "search", "subaction" to "search", "story" to query),
                cookies = cookies,
            ).document
        }.getOrNull() ?: return emptyList()

        return parseCards(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = session().second
        val document = pageOf(url) ?: return null

        val title = document.selectFirst("h1, article h2")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val playerHtml = document.select("div.pmovie__player").html()
        val poster = posterOf(document, baseUrl)
        val plot = plotOf(document)
        val year = YEAR.find(document.selectFirst("article")?.text().orEmpty())?.value?.toIntOrNull()
        val imdb = IMDB.find(playerHtml)?.value
            ?: IMDB.find(document.select("a[href*=imdb]").attr("href"))?.value
        val trailer = document.selectFirst("link[itemprop=embedUrl]")?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val episodes = episodesOf(document, poster)
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addTrailer(trailer)
                imdb?.let { addImdbId(it) }
            }
        }

        val streams = collectStreams(document)
        println("[MegaKino] $url -> ${streams.size} Hoster")
        if (streams.isEmpty()) return null

        return newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addTrailer(trailer)
            imdb?.let { addImdbId(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = runCatching { parseJson<Links>(data) }.getOrNull() ?: return false

        var found = false
        for (raw in links) {
            val url = HosterResolver.normalize(raw)
            if (url.startsWith("https://devideosrc.co") || url.startsWith("https://devideosrc.com")) {
                if (DevideoSrc.load(url, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            }
            if (HosterResolver.resolve(url, null, subtitleCallback, callback)) {
                found = true
            }
        }
        println("[MegaKino] loadLinks -> ${if (found) "Quelle gefunden" else "keine Quelle"}")
        return found
    }

    // ---------------------------------------------------------------- Parsing

    /**
     * Liest Karten aus Katalog- und Suchseiten. Beide Templates verschachteln
     * `img` und `h3`/`h4` in einem Anchor, daher genügt ein gemeinsamer Parser.
     */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()

        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (href.isEmpty() || href.contains("#")) continue

            val path = pathOf(href)
            if (path.contains("/collection/")) continue

            val isSeries = path.startsWith("/serials/")
            val isDetail = if (altTemplate) {
                ALT_DETAIL.containsMatchIn(path)
            } else {
                DLE_DETAIL.containsMatchIn(path)
            }
            if (!isDetail) continue
            if (out.containsKey(path)) continue

            val title = anchor.selectFirst("h3, h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: continue

            val both = if (title.contains("Staffel", true)) TvType.TvSeries else TvType.Movie
            val type = if (isSeries) TvType.TvSeries else both
            val poster = cardPoster(anchor, base ?: DLE_BASE)

            out[path] = newMovieSearchResponse(title, absolute(href), type) {
                this.posterUrl = poster
                this.quality = SearchQuality.HD
            }
        }

        return out.values.toList()
    }

    /** Alle Hoster-Ziele einer Detailseite, unabhängig vom Template. */
    private fun collectStreams(doc: Document): List<String> {
        val out = LinkedHashSet<String>()

        // Popcornie-Template: ein Iframe pro Hoster-Zelle.
        doc.select("div.pmovie__player iframe, div.tabs-block__content iframe, iframe#film_main")
            .forEach { frame ->
                val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
                if (isStream(src)) out.add(src)
            }

        // megaKino-Template: Hoster stehen als `span[data-link]` in der Leiste.
        doc.select("span[data-link]").forEach { span ->
            val src = span.attr("data-link").trim()
            if (isStream(src)) out.add(src)
        }

        return out.toList()
    }

    /** Episoden aus den `select#ep1 … select#epN` des Serien-Players. */
    private fun episodesOf(doc: Document, poster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()

        for (select in doc.select("select")) {
            val number = EP_ID.find(select.id())?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val urls = select.select("option")
                .map { it.attr("value").trim() }
                .filter { isStream(it) }
            if (urls.isEmpty()) continue

            episodes.add(
                newEpisode(urls.toJson()) {
                    this.episode = number
                    this.posterUrl = poster
                }
            )
        }

        return episodes.sortedBy { it.episode ?: 0 }
    }

    private fun posterOf(doc: Document, baseUrl: String): String? {
        val img = doc.selectFirst("div.pmovie__poster img") ?: doc.selectFirst("article img")
        val raw: String = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }.trim()
        return absoluteOrNull(raw, baseUrl)
    }

    private fun cardPoster(anchor: org.jsoup.nodes.Element, baseUrl: String): String? {
        val img: org.jsoup.nodes.Element = anchor.selectFirst("img") ?: return null
        val raw: String = img.attr("data-src").ifBlank { img.attr("src") }.trim()
        return absoluteOrNull(raw, baseUrl)
    }

    private fun plotOf(doc: Document): String? {
        doc.selectFirst("[itemprop=description]")
            ?.attr("content")
            ?.takeIf { it.length > 40 }
            ?.let { return it }

        return doc.select("article p, div.pmovie__text")
            .map { it.text().trim() }
            .filter { it.length > 80 }
            .maxByOrNull { it.length }
    }

    private fun isStream(url: String): Boolean =
        url.startsWith("http") && NON_STREAM.none { url.contains(it, true) }

    private fun pathOf(href: String): String =
        if (href.startsWith("http")) {
            runCatching { java.net.URI(href).path.orEmpty() }.getOrDefault("")
        } else {
            href.substringBefore('?')
        }

    private fun absolute(href: String): String =
        if (href.startsWith("http")) href else (base ?: DLE_BASE) + pathOf(href)

    private fun absoluteOrNull(raw: String, baseUrl: String): String? = when {
        raw.startsWith("http") -> raw
        raw.startsWith("/") -> baseUrl + raw
        else -> null
    }

    class Links : ArrayList<String>()
}
