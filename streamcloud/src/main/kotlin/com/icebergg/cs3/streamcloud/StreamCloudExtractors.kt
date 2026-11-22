package com.icebergg.cs3.streamcloud

import com.lagradost.cloudstream3.extractors.Supervideo

/**
 * Supervideo-Varianten der devideosrc-Kette.
 *
 * Der normale Weg läuft über [DevideoSrc] (JsUnpacker + Referer). Diese Klassen
 * bleiben als Fallback erhalten, falls ein Embed den Config ungepackt ausliefert.
 * `dr0pstream.com` und `superplayer.ws` werden direkt aufgelöst und brauchen
 * deshalb keine eigene Extraktor-Klasse mehr.
 */
class SupervideoCom : Supervideo() {
    override var name = "SupervideoCom"
    override var mainUrl = "https://supervideo.com"
}

class SupervideoTv : Supervideo() {
    override var name = "SupervideoTv"
    override var mainUrl = "https://supervideo.tv"
}
