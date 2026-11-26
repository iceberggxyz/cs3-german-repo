package com.icebergg.cs3.streamcloud

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für StreamCloud.
 *
 * devideosrc liefert seine Quellen als gepackten JW-Player-Config; die werden
 * direkt in [DevideoSrc] entpackt. Die zusätzlich registrierten Extraktoren
 * greifen, wenn ein Hoster doch eine ungepackte Seite ausliefert.
 */
@CloudstreamPlugin
class StreamCloudPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamCloudProvider())

        // devideosrc-Hoster (Fallback, falls kein gepackter Config vorliegt).
        registerExtractorAPI(SupervideoCom())
        registerExtractorAPI(SupervideoTv())

        // Standard-Hoster kommen aus shared/ExtractorRegistry.kt.
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
