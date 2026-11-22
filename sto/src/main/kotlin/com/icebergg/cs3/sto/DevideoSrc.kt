package com.icebergg.cs3.sto

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * Löst einen `devideosrc.co`-Player auf.
 *
 *  1. Player-Seite laden und `type`/`id`/`token` aus dem Inline-Skript lesen.
 *  2. `POST /api/embed-links` mit `{ type, id, [season], [episode], token }`.
 *  3. Hoster-Embeds über die CloudStream-Extraktoren auflösen.
 */
object DevideoSrc {

    private const val API = "https://devideosrc.co/api/embed-links"

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

        val response = runCatching {
            app.post(
                API,
                json = body,
                referer = url,
                headers = mapOf(
                    "origin" to "https://devideosrc.co",
                    "x-requested-with" to "XMLHttpRequest",
                ),
            )
        }.getOrNull() ?: return false

        val parsed = runCatching { parseJson<EmbedResponse>(response.text) }.getOrNull()
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
