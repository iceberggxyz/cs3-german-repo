package com.icebergg.cs3.shared

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Extraktor für **Vinovo** (`vinovo.to`, u. a. von Movie2K und KinoKing genutzt).
 *
 * Die Seite liefert die Abspiel-URL nicht im HTML, sondern über eine kleine
 * API: Aus der Embed-Seite werden `meta[name=token]` und `video[data-base]`
 * gelesen, anschließend wird `POST /api/file/url/{file_code}` mit
 * `{recaptcha, token}` aufgerufen. Die Antwort enthält einen Stream-Token,
 * der an `{data-base}/stream/` angehängt die MP4-Quelle ergibt.
 *
 * Gemeinsame Quelle für alle Module (siehe `standardExtractors()`); bewusst
 * offset-frei als eigener Typ statt als Inline-Code in den Providern.
 */
class Vinovo : ExtractorApi() {
    override val name = "Vinovo"
    override val mainUrl = "https://vinovo.to"
    override val requiresReferer = true

    companion object {
        private val TOKEN_JSON = Regex(""""token"\s*:\s*"([^"]+)"""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fileCode = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        if (fileCode.isBlank()) return

        val document = app.get(url, referer = referer ?: "$mainUrl/").document
        val pageToken = document.selectFirst("meta[name=token]")?.attr("content").orEmpty()
        val dataBase = document.selectFirst("video[data-base]")?.attr("data-base")
            ?: document.selectFirst("video")?.attr("data-base")
            ?: return
        if (pageToken.isBlank()) return

        val body = app.post(
            "$mainUrl/api/file/url/$fileCode",
            data = mapOf("recaptcha" to "", "token" to pageToken),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to mainUrl,
            ),
            referer = url,
        ).text

        val streamToken = TOKEN_JSON.find(body)?.groupValues?.get(1) ?: return
        val streamUrl = "${dataBase.trimEnd('/')}/stream/$streamToken"

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
