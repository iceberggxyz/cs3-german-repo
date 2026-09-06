package com.icebergg.cs3.streamcloud

import android.content.Context
import com.lagradost.cloudstream3.extractors.DoodToExtractor
import com.lagradost.cloudstream3.extractors.DoodstreamCom
import com.lagradost.cloudstream3.extractors.MixDropAg
import com.lagradost.cloudstream3.extractors.Supervideo
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für HDFilme (StreamCloud.date ist inzwischen
 * ein HDFilme-Mirror). Registriert den Provider sowie die benötigten Extraktoren.
 */
@CloudstreamPlugin
class HDFilmePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilme())

        registerExtractorAPI(DoodstreamCom())
        registerExtractorAPI(SupervideoCom())
        registerExtractorAPI(SupervideoTv())
        registerExtractorAPI(Supervideo())
        registerExtractorAPI(DroploadIo())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(DoodToExtractor())
    }
}
