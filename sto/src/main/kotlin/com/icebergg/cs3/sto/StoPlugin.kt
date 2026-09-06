package com.icebergg.cs3.sto

import android.content.Context
import com.lagradost.cloudstream3.extractors.Urochsunloath
import com.lagradost.cloudstream3.extractors.Voe1
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für s.to / SerienStream.
 * Registriert den Provider sowie die benötigten Hoster-Extraktoren.
 */
@CloudstreamPlugin
class StoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StoProvider())
        registerExtractorAPI(Voe1())
        registerExtractorAPI(Urochsunloath())
    }
}
