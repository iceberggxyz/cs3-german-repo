package com.icebergg.cs3.kinoking

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für KinoKing.
 *
 * KinoKing liefert die Hoster-Liste serverseitig (`const SERVERS = [...]`)
 * bzw. je Episode (`allEpisodesData`). Registriert werden die gemeinsamen
 * Extraktoren aus `shared/ExtractorRegistry.kt` (inklusive Vinovo und
 * Supervideo) – eine zusätzliche Einzelregistrierung ist damit entfallen.
 */
@CloudstreamPlugin
class KinokingPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kinoking())
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
