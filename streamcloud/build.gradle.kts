// Versionsnummer des Plugins als Ganzzahl.
version = 12

// Gemeinsame Quellen (DevideoSrc) mit sto teilen, statt zu kopieren.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "Filme und Serien von StreamCloud"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    status = 1
    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://streamcloud.date&sz=%size%"
}
