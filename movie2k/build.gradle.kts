// Versionsnummer des Plugins als Ganzzahl.
version = 1

// Gemeinsame Quellen (ExtractorRegistry/Vinovo/HosterResolver) mit den
// anderen Modulen teilen.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "Movie2K - deutsche Filme und Serien (Voe, Doodstream, Streamtape, Mixdrop, Vinovo ...)"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    // Katalog, Suche und Detailseiten liefern serverseitig HTML inklusive Hoster-Links.
    status = 1
    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://movie2k.cx&sz=%size%"
}
