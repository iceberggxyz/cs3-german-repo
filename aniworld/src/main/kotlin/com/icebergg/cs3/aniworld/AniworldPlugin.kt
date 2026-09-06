package com.icebergg.cs3.aniworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für AniWorld.
 * Registriert den Provider beim Laden des Plugins.
 */
@CloudstreamPlugin
class AniworldPlugin : Plugin() {
    override fun load(context: Context) {
        // Alle Provider auf diese Weise registrieren.
        registerMainAPI(AniworldProvider())
    }
}
