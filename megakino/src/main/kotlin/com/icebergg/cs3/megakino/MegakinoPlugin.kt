package com.icebergg.cs3.megakino

import android.content.Context
import com.lagradost.cloudstream3.extractors.DoodstreamCom
import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.Voe1
import com.lagradost.cloudstream3.extractors.Voe2
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für MegaKino.
 *
 * `devideosrc.co` (Hoster „Sirius“ auf `1megakino.cc`) wird direkt in
 * [DevideoSrc] aufgelöst. Die übrigen Extraktoren decken die Hoster ab, die
 * MegaKino als direkte Iframes ausliefert (VOE, Vidara-Kette, FileMoon, …).
 */
@CloudstreamPlugin
class MegakinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Megakino())

        registerExtractorAPI(Voe())
        registerExtractorAPI(Voe1())
        registerExtractorAPI(Voe2())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(FilemoonV2())
        registerExtractorAPI(Vidmoly())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(DoodstreamCom())
    }
}
