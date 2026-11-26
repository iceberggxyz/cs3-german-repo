// Versionsnummer des Plugins als Ganzzahl.
version = 10

// Gemeinsame Quellen (DevideoSrc) mit streamcloud teilen, statt zu kopieren.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "Serien von s.to / SerienStream (benoetigt DNS over HTTPS)"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    status = 1
    // Gueltiger TvType-Wert ist "TvSeries", nicht "Series".
    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://serienstream.si&sz=%size%"
}
