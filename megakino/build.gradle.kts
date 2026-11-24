// Versionsnummer des Plugins als Ganzzahl.
version = 5

// Gemeinsame Quellen (DevideoSrc) mit sto/streamcloud teilen, statt zu kopieren.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "MegaKino - Filme und Serien (megakino29.com, Mirror 1megakino.cc)"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Documentary")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://megakino29.com&sz=%size%"
}
