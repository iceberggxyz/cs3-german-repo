package com.icebergg.cs3.streamcloud

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

/**
 * Löst einen `devideosrc.co`-Player auf.
 *
 * Es gibt zwei Player-Varianten:
 *  - **Film**: `POST /api/embed-links` mit `{ type, id, token }`; das Token steht
 *    im Inline-Skript (`JSON.stringify({...})`).
 *  - **Serie**: `POST /api/resolve` mit `{ type, id, season, episode, mode, token }`;
 *    die Werte stehen in den `data-*`-Attributen des `<main>`-Elements.
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

    suspend fun load(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (url.isBlank()) return false

        val page = runCatching { app.get(url).text }.getOrNull() ?: return false
        val doc = runCatching { Jsoup.parse(page, url) }.getOrNull()

        // Serien-Player erkennen (data-resolve-token am <main>).
        val main = doc?.selectFirst("main[data-resolve-token]")

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
            // Film-Player: Token aus dem Inline-Skript lesen.
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
        } ?: return false

        val parsed = runCatching { parseJson<EmbedResponse>(responseText) }.getOrNull()
            ?: return false

        val sources = parsed.sources ?: return false
        var found = false
        for (source in sources) {
            val link = source.url?.takeIf { it.startsWith("http") } ?: continue
            loadExtractor(link, url, subtitleCallback) { callback(it) }
            found = true
        }
        return found
    }

    private fun apiHeaders() = mapOf(
        "origin" to HOST,
        "x-requested-with" to "XMLHttpRequest",
    )

    data class EmbedResponse(
        val ok: Boolean = false,
        val type: String? = null,
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
