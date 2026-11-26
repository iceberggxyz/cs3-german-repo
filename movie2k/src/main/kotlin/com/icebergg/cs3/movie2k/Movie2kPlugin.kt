package com.icebergg.cs3.movie2k

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für Movie2K.
 *
 * Movie2K liefert die Hoster-Links direkt im HTML der Detailseite. Die dafür
 * nötigen Extraktoren kommen aus `shared/ExtractorRegistry.kt` (inklusive
 * Vinovo); zusätzliche Hoster müssen hier nicht einzeln registriert werden.
 */
@CloudstreamPlugin
class Movie2kPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Movie2k())
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
