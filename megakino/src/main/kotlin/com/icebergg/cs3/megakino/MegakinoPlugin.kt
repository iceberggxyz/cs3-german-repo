package com.icebergg.cs3.megakino

import android.content.Context
import com.lagradost.cloudstream3.extractors.DoodPmExtractor
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für MegaKino.
 * Registriert den Provider sowie die benötigten Hoster-Extraktoren.
 */
@CloudstreamPlugin
class MegakinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Megakino())
        registerExtractorAPI(Voe())
        registerExtractorAPI(DoodPmExtractor())
    }
}
