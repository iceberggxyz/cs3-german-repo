package com.icebergg.cs3.shared

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * Loest `meinecloud.click` auf.
 *
 * Die Seite ist ein reiner Aggregator: Sie enthaelt keine eigene Playlist,
 * sondern listet die dahinterliegenden Hoster als `._player-mirrors li` mit dem
 * jeweiligen Ziel in `data-link`. Diese Links werden erneut durch
 * [loadExtractor] geschickt.
 *
 * Vorher hat der KinoKing-Provider `meinecloud.click` als Aggregator komplett
 * verworfen und damit alle darueber erreichbaren Streams verloren. Mit diesem
 * Extraktor werden sie stattdessen aufgeloest.
 */
class MeineCloudClick : ExtractorApi() {
    override val name: String = "MeineCloud"
    override val mainUrl: String = "https://meinecloud.click"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val dom = runCatching { app.get(url, referer = referer).document }.getOrNull() ?: return
        val mirrors = dom.select("._player-mirrors li")
            .mapNotNull { it.attr("data-link").takeIf { link -> link.isNotBlank() } }
            .map { fixUrl(it) }
            .distinct()

        mirrors.amap {
            runCatching { loadExtractor(it, referer, subtitleCallback, callback) }
        }
    }
}
