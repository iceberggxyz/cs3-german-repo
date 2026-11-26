package com.icebergg.cs3.megakino

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für MegaKino.
 *
 * `devideosrc.co` (Hoster „Sirius“ auf `1megakino.cc`) wird direkt in
 * `DevideoSrc` aufgelöst. Die übrigen Hoster deckt der Standard-Satz aus
 * `shared/ExtractorRegistry.kt` ab.
 */
@CloudstreamPlugin
class MegakinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Megakino())
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
