package com.icebergg.cs3.streamcloud

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

/**
 * Löst einen `devideosrc.co`-Player auf.
 *
 * Es gibt zwei Player-Varianten:
 *  - **Film**: `POST /api/embed-links` mit `{ type, id, token }` aus dem Inline-Skript.
 *  - **Serie**: `POST /api/resolve` mit `{ type, id, season, episode, mode, token }`
 *    aus den `data-*`-Attributen des `<main>`-Elements.
 *
 * Die Antwort enthält ausschließlich Embed-URLs (dr0pstream.com, superplayer.ws, …).
 * Diese Seiten liefern ihren JW-Player-Config **Dean-Edwards-gepackt**
 * (`eval(function(p,a,c,k,e,d){…})`) aus. Deshalb wird hier entpackt und die
 * `master.m3u8` direkt extrahiert. Der CDN antwortet ohne `Referer` mit HTTP 403,
 * der Referer wird daher am [ExtractorLink] mitgegeben.
 */
object DevideoSrc {

    private const val HOST = "https://devideosrc.co"
    private const val EMBED_API = "$HOST/api/embed-links"

    private val bodyRegex = Regex("""JSON\.stringify\(\s*(\{.*?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val typeRegex = Regex("""type\s*:\s*['"]([^'"]+)['"]""")
    private val idRegex = Regex("""id\s*:\s*['"]([^'"]+)['"]""")
    private val tokenRegex = Regex("""token\s*:\s*['"]([^'"]+)['"]""")
    private val seasonRegex = Regex("""season\s*:\s*(\d+)""")
    private val episodeRegex = Regex("""episode\s*:\s*(\d+)""")

    /** Gepackter `eval(...)`-Block, falls [JsUnpacker] das gesamte HTML nicht erkennt. */
    private val packedRegex = Regex(
        """eval\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\.split\(\s*'\|'\s*\)\s*\)\s*\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Spielbare HLS-Playlist (Master-Playlist des CDN). */
    private val hlsRegex = Regex("""https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*""")

    /** Auflösung, sofern der Player-Config sie mitliefert. */
    private val qualityRegex = Regex("""(?:label|height)["']?\s*[:=]\s*["']?(\d{3,4})""")

    suspend fun load(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (url.isBlank()) return false

        val page = runCatching { app.get(url).text }.getOrNull() ?: return false
        val doc = runCatching { Jsoup.parse(page, url) }.getOrNull()

        val main = doc?.selectFirst("main[data-resolve-token]")
        println("[DevideoSrc] url=$url serial=${main != null}")

        val responseText = if (main != null) {
            val endpoint = main.attr("data-resolve-endpoint").ifBlank { "/api/resolve" }
            val body = mutableMapOf<String, Any>(
                "type" to main.attr("data-type").ifBlank { "tv" },
                "id" to main.attr("data-id"),
                "season" to main.attr("data-season"),
                "episode" to main.attr("data-episode"),
                "mode" to main.attr("data-mode").ifBlank { "custom" },
                "token" to main.attr("data-resolve-token"),
            )
            runCatching {
                app.post("$HOST$endpoint", json = body, referer = url, headers = apiHeaders()).text
            }.getOrNull()
        } else {
            val block = bodyRegex.find(page)?.groupValues?.get(1) ?: page
            val type = typeRegex.find(block)?.groupValues?.get(1) ?: return false
            val id = idRegex.find(block)?.groupValues?.get(1) ?: return false
            val token = tokenRegex.find(block)?.groupValues?.get(1) ?: return false

            val body = mutableMapOf<String, Any>(
                "type" to type,
                "id" to id,
                "token" to token,
            )
            seasonRegex.find(block)?.groupValues?.get(1)?.toIntOrNull()?.let { body["season"] = it }
            episodeRegex.find(block)?.groupValues?.get(1)?.toIntOrNull()?.let { body["episode"] = it }

            runCatching {
                app.post(EMBED_API, json = body, referer = url, headers = apiHeaders()).text
            }.getOrNull()
        } ?: run {
            println("[DevideoSrc] resolve request failed")
            return false
        }

        val parsed = runCatching { parseJson<EmbedResponse>(responseText) }.getOrNull()
        if (parsed == null) {
            println("[DevideoSrc] resolve JSON unreadable: ${responseText.take(300)}")
            return false
        }

        println("[DevideoSrc] ok=${parsed.ok} sources=${parsed.sources?.size ?: 0} title=${parsed.title}")
        val sources = parsed.sources ?: return false

        var found = false
        for (source in sources) {
            val link = source.url?.takeIf { it.startsWith("http") } ?: continue
            val sourceName = source.name?.takeIf { it.isNotBlank() } ?: "Hoster"
            println("[DevideoSrc] source=$sourceName url=$link")

            // 1) Direktauflösung: gepackten JW-Player-Config entpacken und m3u8 mit Referer liefern.
            val resolved = runCatching { resolveEmbed(link, sourceName, callback) }.getOrDefault(false)
            if (resolved) {
                found = true
                continue
            }

            // 2) Fallback: eingebauter Extractor (supervideo, doodstream, …).
            val ok = runCatching {
                loadExtractor(link, url, subtitleCallback) { callback(it) }
            }.getOrDefault(false)
            println("[DevideoSrc] loadExtractor ok=$ok url=$link")
            if (ok) found = true
        }
        return found
    }

    /**
     * Holt das Hoster-Embed, entpackt den JW-Player-Config und emittiert die HLS-URL
     * inklusive Referer/Origin (der CDN antwortet sonst mit HTTP 403).
     */
    private suspend fun resolveEmbed(
        embedUrl: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val referer = originOf(embedUrl) + "/"
        val html = runCatching { app.get(embedUrl, referer = referer).text }.getOrNull() ?: return false

        val isPacked = html.contains("eval(function")
        val unpacked = unpack(html)
        println("[DevideoSrc] embed=$embedUrl packed=$isPacked unpackedLen=${unpacked.length}")

        val hls = hlsRegex.find(unpacked)?.value
            ?: hlsRegex.find(html)?.value
            ?: run {
                println("[DevideoSrc] no m3u8 in embed")
                return false
            }

        val quality = qualityRegex.findAll(unpacked)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0

        println("[DevideoSrc] hls=$hls quality=$quality referer=$referer")

        callback(
            newExtractorLink(
                source = sourceName,
                name = if (quality > 0) "$sourceName ${quality}p" else sourceName,
                url = hls,
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to referer,
                    "Origin" to referer.trimEnd('/'),
                )
            }
        )
        return true
    }

    /** Entpackt Dean-Edwards- gepacktes JavaScript über den eingebauten [JsUnpacker]. */
    private fun unpack(html: String): String {
        val candidates = listOfNotNull(
            html,
            packedRegex.find(html)?.value,
        )
        for (candidate in candidates) {
            val unpacked = runCatching {
                val unpacker = JsUnpacker(candidate)
                if (unpacker.detect()) unpacker.unpack() else null
            }.getOrNull()
            if (!unpacked.isNullOrBlank() && unpacked.length > 32) {
                return unpacked.replace("&", "&")
            }
        }
        return html
    }

    private fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: HOST

    private fun apiHeaders() = mapOf(
        "origin" to HOST,
        "x-requested-with" to "XMLHttpRequest",
    )

    data class EmbedResponse(
        val ok: Boolean = false,
        val type: String? = null,
        val title: String? = null,
        val sources: List<EmbedSource>? = null,
    )

    data class EmbedSource(
        val id: String? = null,
        val name: String? = null,
        val url: String? = null,
        val embed: Boolean = false,
        val kind: String? = null,
        val rank: Int = 0,
    )
}
