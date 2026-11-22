// Versionsnummer des Plugins als Ganzzahl.
version = 3

cloudstream {
    language = "de"
    description = "Burning Series - derzeit nicht verfügbar (Cloudflare/AJAX)"
    authors = listOf("IceBERGG")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Nur Beta
     */
    // Down: burning-series.co antwortet mit Cloudflare 526.
    status = 0
    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://burning-series.co&sz=%size%"
}
