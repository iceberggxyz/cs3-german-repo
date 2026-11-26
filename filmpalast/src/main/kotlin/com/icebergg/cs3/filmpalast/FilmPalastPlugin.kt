package com.icebergg.cs3.filmpalast

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin für Filmpalast.
 *
 * Die Stream-Links zeigen direkt auf die Hoster (z. B. `voe.sx`), deshalb werden
 * hier die passenden Extractor-APIs mitregistriert – gebündelt über
 * `shared/ExtractorRegistry.kt`.
 */
@CloudstreamPlugin
class FilmPalastPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmPalast())
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
