package com.icebergg.cs3.shared

/**
 * Entscheidet, ob eine URL überhaupt ein abspielbarer Stream sein **kann**.
 *
 * Hintergrund: Die Quell-Seiten betten Trailer als YouTube-iframe ein und
 * führen sie in Hoster-Listen teils wie einen echten Hoster. Ohne Filter landet
 * so ein Trailer als „Quelle“ in der App, weil CloudStream einen YouTube-
 * Extraktor mitbringt.
 *
 * Die Prüfung läuft bewusst zentral (siehe [HosterResolver]) statt siebenmal
 * pro Provider – eine Regel, alle Module.
 *
 * **Keine Allowlist**: neue Hoster wären sonst sofort blockiert. Verworfen wird
 * nur, was nachweislich kein Stream ist.
 */
object StreamFilter {

    /** Domains, die nie ein Stream sind. Vergleich erfolgt auf dem Hostnamen. */
    private val BLOCKED_DOMAINS = listOf(
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "googlevideo.com",
        "vimeo.com",
        "dailymotion.com",
        "dai.ly",
        "facebook.com",
        "fb.watch",
        "instagram.com",
        "tiktok.com",
        "twitter.com",
        "x.com",
        "soundcloud.com",
        "doubleclick.net",
        "acscdn.com",
        "warez.cx",
        "vpnkostenlos",
    )

    /** Beschriftungen, die auf einen Trailer hindeuten (Hoster-Tabs o. Ä.). */
    private val BLOCKED_LABELS = listOf("trailer", "teaser", "vorschau", "clip")

    private val hostRegex = Regex("""^https?://([^/?#]+)""")

    /**
     * @param url   zu prüfende URL (bereits absolut oder relativ)
     * @param label optionaler Hoster-Name aus der Seite, z. B. „Trailer“
     */
    fun isPlayable(url: String, label: String? = null): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false

        val host = hostRegex.find(trimmed.lowercase())?.groupValues?.get(1).orEmpty()
        if (host.isEmpty()) return false
        if (BLOCKED_DOMAINS.any { host == it || host.endsWith(".$it") }) return false

        val cleanLabel = label?.trim()?.lowercase().orEmpty()
        if (cleanLabel.isNotEmpty() && BLOCKED_LABELS.any { cleanLabel.contains(it) }) return false

        return true
    }

    /** Wie [isPlayable], aber mit `true` bei verworfenen URLs – für Sammelstellen. */
    fun isRejected(url: String, label: String? = null): Boolean = !isPlayable(url, label)
}
