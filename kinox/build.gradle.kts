// Versionsnummer des Plugins als Ganzzahl.
version = 6

// Gemeinsame Quellen (HosterResolver/Requests/Registry) mitnutzen.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "Kinox - derzeit keine erreichbare Domain"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    // Down: Domain per CUII/DNS-Sperre nicht erreichbar.
    status = 0
    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://kinox.to&sz=%size%"
}
