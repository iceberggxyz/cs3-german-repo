package com.icebergg.cs3.megakino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * Zentrale Auflösung der Hoster-Kette für MegaKino.
 *
 *  1. **Direkter Hoster** – z. B. `https://voe.sx/e/abc123`. Hier genügt [loadExtractor].
 *  2. **VOE-Kurzformat** – `https://voe.sx/abc123` (ohne `/e/`). Der Extractor
 *     erwartet das Langformat, deshalb wird umgeschrieben.
 *  3. **Vorschalt-Seite** – z. B. `https://vidara.to/e/abc123`. Die Seite enthält
 *     selbst keinen Stream, sondern setzt per HTML einen iframe auf den echten
 *     Hoster. Dieser iframe wird ausgelesen und erneut aufgelöst.
 *
 * Bewusst **nicht** enthalten ist ein eigener Entschlüssler für den
 * VOE-JSON-Payload. Dafür ist der Voe-Extractor der App zuständig.
 */
object HosterResolver {

    /** VOE-Kurzlink: `voe.sx/abc123` -> `voe.sx/e/abc123`. */
    private val voeShort = Regex("""^(https?://(?:www\.)?voe\.(?:sx|com|to))/([A-Za-z0-9]{6,})$""")

    private val iframeRegex = Regex("""(?is)<iframe[^>]+src\s*=\s*["']([^"']+)["']""")

    suspend fun resolve(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = normalize(url)

        if (tryExtractor(normalized, referer, subtitleCallback, callback)) return true

        val inner = runCatching { extractIframe(normalized) }.getOrNull()
        if (inner.isNullOrBlank() || inner == normalized) {
            println("[HosterResolver] keine Quelle in $url")
            return false
        }
        println("[HosterResolver] iframe $url -> $inner")

        return tryExtractor(inner, normalized, subtitleCallback, callback)
    }

    fun normalize(url: String): String {
        voeShort.find(url)?.let { m ->
            return "${m.groupValues[1]}/e/${m.groupValues[2]}"
        }
        return url
    }

    private suspend fun tryExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = runCatching {
        loadExtractor(url, referer, subtitleCallback) { callback(it) }
    }.getOrDefault(false)

    private suspend fun extractIframe(url: String): String? {
        val host = hostOf(url)
        val html = runCatching { app.get(url, referer = "https://$host/").text }.getOrNull()
            ?: return null

        val src = iframeRegex.find(html)?.groupValues?.get(1)?.trim() ?: return null
        if (src.isBlank()) return null
        if (src.startsWith("about:") || src.startsWith("javascript:") || src.startsWith("#")) return null
        if (src.contains("google") || src.contains("facebook") || src.contains("youtube")) return null

        val abs = when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "https://$host$src"
            src.startsWith("http") -> src
            else -> "https://$host/$src"
        }
        return abs.takeIf { it != url }
    }

    private fun hostOf(url: String): String =
        Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1).orEmpty()
}
