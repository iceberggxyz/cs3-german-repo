package com.icebergg.cs3.sto

import android.content.Context
import com.lagradost.cloudstream3.extractors.DoodstreamCom
import com.lagradost.cloudstream3.extractors.MixDropAg
import com.lagradost.cloudstream3.extractors.Supervideo
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für s.to / SerienStream (serienstream.si).
 * Registriert den Provider sowie die für die devideosrc-Hoster nötigen Extraktoren.
 */
@CloudstreamPlugin
class StoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StoProvider())

        registerExtractorAPI(DoodstreamCom())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(Supervideo())
        registerExtractorAPI(SupervideoCom())
        registerExtractorAPI(SupervideoTv())
        registerExtractorAPI(DroploadIo())
        registerExtractorAPI(DropStreamCom())
        registerExtractorAPI(SuperplayerWs())
    }
}
