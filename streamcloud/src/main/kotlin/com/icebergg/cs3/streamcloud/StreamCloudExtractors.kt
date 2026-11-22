package com.icebergg.cs3.streamcloud

import com.lagradost.cloudstream3.extractors.Supervideo

/** Supervideo-Varianten, die von der StreamCloud-/devideosrc-Kette genutzt werden. */
class SupervideoCom : Supervideo() {
    override var name = "SupervideoCom"
    override var mainUrl = "https://supervideo.com"
}

class SupervideoTv : Supervideo() {
    override var name = "SupervideoTv"
    override var mainUrl = "https://supervideo.tv"
}

class DroploadIo : Supervideo() {
    override var name = "DroploadIO"
    override var mainUrl = "https://dropload.io"
}

class DropStreamCom : Supervideo() {
    override var name = "DropStreamCom"
    override var mainUrl = "https://dr0pstream.com"
}

class SuperplayerWs : Supervideo() {
    override var name = "SuperplayerWs"
    override var mainUrl = "https://superplayer.ws"
}
