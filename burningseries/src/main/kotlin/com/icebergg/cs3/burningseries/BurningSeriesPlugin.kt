package com.icebergg.cs3.burningseries

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für Burning Series.
 * Registriert den Provider beim Laden des Plugins.
 */
@CloudstreamPlugin
class BurningSeriesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BurningSeriesProvider())
    }
}
