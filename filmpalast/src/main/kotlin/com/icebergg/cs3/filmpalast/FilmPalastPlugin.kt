package com.icebergg.cs3.filmpalast

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
 * CloudStream-Plugin für Filmpalast.
 *
 * Die Stream-Links zeigen direkt auf die Hoster (z. B. `voe.sx`), deshalb werden
 * hier die passenden Extractor-APIs mitregistriert.
 */
@CloudstreamPlugin
class FilmPalastPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmPalast())

        registerExtractorAPI(Voe())
        registerExtractorAPI(Voe1())
        registerExtractorAPI(Voe2())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Vidmoly())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(FilemoonV2())
        registerExtractorAPI(DoodstreamCom())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(Upstream())
        registerExtractorAPI(Luluvdoo())
        registerExtractorAPI(Vidoza())
    }
}
