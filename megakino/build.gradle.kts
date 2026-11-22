// Versionsnummer des Plugins als Ganzzahl.
version = 3

cloudstream {
    language = "de"
    description = "MegaKino - Player derzeit nicht aufloesbar (gxplayer)"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    // Down: megakino.co antwortet dauerhaft mit HTTP 403.
    status = 0
    tvTypes = listOf("Movie", "TvSeries", "Documentary")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://megakino4.to&sz=%size%"
}
