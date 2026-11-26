// Versionsnummer des Plugins als Ganzzahl.
version = 1

// Gemeinsame Quellen (DevideoSrc/ExtractorRegistry/HosterResolver) mit den
// anderen Modulen teilen.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "HDFilme - deutsche Filme in HD (Aufloesung ueber devideosrc.co)"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    // Katalog und Detailseiten liefern serverseitig HTML; die eigentliche
    // Quelle ist ein devideosrc.co-Embed, den der gemeinsame DevideoSrc loest.
    status = 1
    tvTypes = listOf("Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://hd-filme.today&sz=%size%"
}
