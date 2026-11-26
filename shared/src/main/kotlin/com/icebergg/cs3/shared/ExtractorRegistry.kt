package com.icebergg.cs3.shared

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

/**
 * Die Hoster-Extraktoren, die alle Provider dieses Repositories brauchen.
 *
 * Vorher hatte fast jedes Plugin dieselbe Liste von 13 `registerExtractorAPI`-
 * Aufrufen – und KKiste, AniWorld und Kinox hatte gar keine, obwohl ihre
 * Stream-Pfade über `loadExtractor` laufen. Jetzt gibt es eine Quelle:
 *
 * ```kotlin
 * standardExtractors().forEach { registerExtractorAPI(it) }
 * ```
 *
 * Bewusst eine **Liste** statt einer `Plugin`-Erweiterung: so bleiben die
 * Plugins unabhängig von der Signatur von `registerExtractorAPI`, und ein
 * Modul kann bei Bedarf zusätzliche Extraktoren selbst registrieren.
 */
// Der Typ wird bewusst nicht ausgeschrieben - Kotlin leitet den gemeinsamen
// Obertyp der Extraktoren selbst ab (weniger Abhaengigkeit von API-Interna).
fun standardExtractors() = listOf(
    Voe(),
    Voe1(),
    Voe2(),
    FileMoon(),
    FilemoonV2(),
    Vidmoly(),
    Vidoza(),
    StreamTape(),
    Luluvdoo(),
    VidHidePro(),
    Upstream(),
    DoodstreamCom(),
    MixDropAg(),
)
