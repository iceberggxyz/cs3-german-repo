// Versionsnummer des Plugins als Ganzzahl.
version = 1

// Gemeinsame Quellen (ExtractorRegistry/Vinovo/HosterResolver) mit den
// anderen Modulen teilen.
android {
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

cloudstream {
    language = "de"
    description = "KinoKing - deutsche Filme und Serien (Voe, Dood, Streamtape, Mixdrop, Upstream, Vinovo ...)"
    authors = listOf("IceBERGG")
    // Katalog, Suche und Detailseiten liefern serverseitig HTML inklusive der Hoster-Liste (SERVERS).
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://kinoking.cc&sz=%size%"
}
