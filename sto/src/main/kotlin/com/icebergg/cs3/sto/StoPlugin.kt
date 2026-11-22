package com.icebergg.cs3.sto

import android.content.Context
import com.lagradost.cloudstream3.extractors.DoodstreamCom
import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Luluvdoo
import com.lagradost.cloudstream3.extractors.MixDropAg
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Upstream
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.extractors.Vidoza
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.Voe1
import com.lagradost.cloudstream3.extractors.Voe2
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

        // Weitere Hoster für loadExtractor(...).
        registerExtractorAPI(DoodstreamCom())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(Voe())
        registerExtractorAPI(Voe1())
        registerExtractorAPI(Voe2())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(FilemoonV2())
        registerExtractorAPI(Vidmoly())
        registerExtractorAPI(Vidoza())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Luluvdoo())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(Upstream())
    }
}
