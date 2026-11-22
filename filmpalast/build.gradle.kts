// Versionsnummer des Plugins als Ganzzahl.
version = 4

cloudstream {
    language = "de"
    description = "Filme und Serien von Filmpalast (benoetigt DNS over HTTPS)"
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

    iconUrl = "https://www.google.com/s2/favicons?domain=https://filmpalast.to&sz=%size%"
}
