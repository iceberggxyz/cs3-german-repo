package com.icebergg.cs3.shared

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Kleiner Netzwerk-Layer für alle Provider dieses Repositories.
 *
 * Hintergrund: mehrere Quellen (KKiste, Filmpalast, s.to) stehen hinter
 * Cloudflare. Zwei Dinge helfen dort erfahrungsgemäß:
 *
 *  - ein **echter Browser-User-Agent** – CloudStream stellt dafür [USER_AGENT]
 *    bereit; ohne ihn antworten manche Zonen mit Challenge oder 403.
 *  - ein **Retry** bei 403/429/503. Der ist kein Allheilmittel gegen eine
 *    Challenge, fängt aber Rate-Limits und kurzzeitige Aussetzer ab.
 *
 * Zusätzlich wird der Antwort-Cache der App für diese Aufrufe abgeschaltet
 * (`cacheTime = 0`): ein versehentlich zwischengespeicherter Fehler- oder
 * Challenge-Body würde sonst dauerhaft ausgeliefert – genau das ist beim
 * MegaKino-Token-Handshake passiert.
 *
 * Die Funktionen liefern bei Fehlern `null` zurück, statt Exceptions zu werfen.
 * Ob `null` ein Fehler für den Nutzer ist, entscheidet der Provider (siehe
 * `ErrorLoadingException` in den Providern).
 */
object Requests {

    private const val RETRY_DELAY_MS = 500L
    private const val ATTEMPTS = 2

    /** Status, bei denen ein zweiter Versuch sinnvoll ist. */
    private val RETRYABLE = setOf(403, 429, 503)

    /** GET als Text. */
    suspend fun text(
        url: String,
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
    ): String? {
        for (attempt in 1..ATTEMPTS) {
            val result = runCatching {
                val response = app.get(
                    url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    cookies = cookies,
                    referer = referer,
                    cacheTime = 0,
                )
                val code = response.code
                if (code in RETRYABLE) {
                    println("[Requests] $url -> HTTP $code (Versuch $attempt)")
                    null
                } else {
                    response.text
                }
            }

            result.getOrNull()?.let { return it }
            result.exceptionOrNull()?.let { error ->
                println(
                    "[Requests] $url -> ${error.javaClass.simpleName}: " +
                        "${error.message} (Versuch $attempt)"
                )
            }
            if (attempt < ATTEMPTS) delay(RETRY_DELAY_MS)
        }
        return null
    }

    /** GET als geparstes HTML-Dokument. */
    suspend fun doc(
        url: String,
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
    ): Document? {
        val html = text(url, referer, cookies) ?: return null
        return runCatching { Jsoup.parse(html, url) }.getOrNull()
    }

    /** POST als geparstes HTML-Dokument (Formulardaten, z. B. DLE-Suche). */
    suspend fun postDoc(
        url: String,
        data: Map<String, String>,
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
    ): Document? {
        for (attempt in 1..ATTEMPTS) {
            val result = runCatching {
                val response = app.post(
                    url,
                    data = data,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    cookies = cookies,
                    referer = referer,
                    cacheTime = 0,
                )
                val code = response.code
                if (code in RETRYABLE) {
                    println("[Requests] POST $url -> HTTP $code (Versuch $attempt)")
                    null
                } else {
                    response.text
                }
            }

            result.getOrNull()?.let { html ->
                return runCatching { Jsoup.parse(html, url) }.getOrNull()
            }
            result.exceptionOrNull()?.let { error ->
                println(
                    "[Requests] POST $url -> ${error.javaClass.simpleName}: " +
                        "${error.message} (Versuch $attempt)"
                )
            }
            if (attempt < ATTEMPTS) delay(RETRY_DELAY_MS)
        }
        return null
    }
}
