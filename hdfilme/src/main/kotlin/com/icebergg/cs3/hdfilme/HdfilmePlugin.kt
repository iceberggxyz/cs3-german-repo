package com.icebergg.cs3.hdfilme

import android.content.Context
import com.icebergg.cs3.shared.standardExtractors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream-Plugin-Einstiegspunkt für HDFilme.
 *
 * HDFilme liefert die eigentliche Quelle als devideosrc.co-Embed, das der
 * gemeinsame [com.icebergg.cs3.shared.DevideoSrc] auflöst. Die Standard-
 * Extraktoren werden als Fallback registriert (devideosrc verweist teils auf
 * Supervideo/Doodstream & Co.).
 */
@CloudstreamPlugin
class HdfilmePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Hdfilme())
        standardExtractors().forEach { registerExtractorAPI(it) }
    }
}
