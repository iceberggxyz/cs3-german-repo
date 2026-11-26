// Versionsnummer des Plugins als Ganzzahl.
version = 3

// Gemeinsame Quellen (HosterResolver/DevideoSrc) mit den anderen Modulen teilen.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "Animes und Serien von AniWorld"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    status = 1
    // Gueltige TvType-Werte laut CloudStream-Doku: Anime, AnimeMovie, TvSeries, ...
    tvTypes = listOf("Anime", "AnimeMovie")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://aniworld.to&sz=%size%"
}
