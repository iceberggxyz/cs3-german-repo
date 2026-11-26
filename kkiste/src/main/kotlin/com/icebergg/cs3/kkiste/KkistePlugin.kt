package com.icebergg.cs3.kkiste

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für KKiste.
 * Registriert den Provider beim Laden des Plugins.
 */
@CloudstreamPlugin
class KkistePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KkisteProvider())

        // Hoster-Extraktoren zentral registriert (shared/ExtractorRegistry.kt).
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
