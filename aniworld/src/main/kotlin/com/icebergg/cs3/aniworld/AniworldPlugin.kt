package com.icebergg.cs3.aniworld

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für AniWorld.
 * Registriert den Provider beim Laden des Plugins.
 */
@CloudstreamPlugin
class AniworldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniworldProvider())

        // Hoster-Extraktoren zentral registriert (shared/ExtractorRegistry.kt).
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
