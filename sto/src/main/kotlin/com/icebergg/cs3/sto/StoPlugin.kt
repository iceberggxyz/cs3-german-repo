package com.icebergg.cs3.sto

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für s.to / SerienStream.
 *
 * devideosrc liefert seine Quellen als gepackten JW-Player-Config; die werden
 * direkt in [DevideoSrc] entpackt. Die zusätzlich registrierten Extraktoren
 * greifen, wenn ein Hoster doch eine ungepackte Seite ausliefert.
 */
@CloudstreamPlugin
class StoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StoProvider())

        // devideosrc-Hoster (Fallback, falls kein gepackter Config vorliegt).
        registerExtractorAPI(SupervideoCom())
        registerExtractorAPI(SupervideoTv())

        // Standard-Hoster kommen aus shared/ExtractorRegistry.kt.
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
