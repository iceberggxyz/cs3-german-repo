# Scraping-Selektoren & Wartung

Dieses Dokument beschreibt für jede Quelle die verwendeten CSS-Selektoren
(bzw. Regex-Muster), warum sie gewählt wurden und welchen Verifikationsstatus
sie haben.

## Verifikationsstatus (Legende)

| Symbol | Bedeutung                                                            |
|--------|----------------------------------------------------------------------|
| ✅      | Live verifiziert (via Playwright-Browser gegen die aktuelle Domain)   |
| 🟡      | Aus dokumentierter Plattform-Struktur übernommen – muss verifiziert werden |
| ⚠️      | Domain aktuell nicht erreichbar (DNS-Sperre / SSL-Fehler / geparkt)    |

> **Stand der Live-Prüfung:** 04.09.2026 (via Playwright MCP / Opera GX).

---

## 1. AniWorld.to ✅

- **Basis-URL:** `https://aniworld.to`
- **Plattform:** „SSO“-Codebasis (identisch zu SerienStream/s.to).

### Suche – AJAX-Endpoint (✅ live verifiziert)

```
POST https://aniworld.to/ajax/search
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
X-Requested-With: XMLHttpRequest
Body: keyword={query}
```

Die Suche ist **nicht** serverseitig gerendert. Die Seite `/search?q=…` zeigt
nur „Loading…“ und lädt die Ergebnisse per jQuery-AJAX nach. Antwort ist ein
JSON-Array:

```json
[
  { "title": "<em>One Piece</em>",
    "description": "Reichtum, Macht und Ruhm …",
    "link": "/anime/stream/one-piece" }
]
```

**Warum:** `keyword` (nicht `q`!) ist der korrekte Parameter; `title` und
`description` enthalten HTML (`<em>`-Highlighting) und werden mit JSoup
gestrippt.

### Detailseite

| Zweck                | Selektor                                           | Anmerkung |
|----------------------|----------------------------------------------------|-----------|
| Titel                | `h1`                                               | Der Serien-/Animetitel |
| Cover                | `img[itemprop=image]` bzw. `img[alt*='Cover']`     | `src` ist relativ (`/public/img/cover/…_220x330.jpg`) |
| Staffeln             | `a[href*='/staffel-']:not([href*='/episode-'])`    | z. B. `/anime/stream/naruto/staffel-4` |
| Episoden (Zeilen)    | `table.seasonEpisodesList tbody tr`                | **Klasse statt ID** – stabil über Staffeln |
| Episoden (Link+Titel)| `td.seasonEpisodeTitle a[href]`                    | `<strong>` = dt. Titel, `<span>` = engl. Titel |
| Hoster (Übersicht)   | `td i.icon[title]`                                 | `title` = Hoster-Name (VOE, Doodstream, …) |

**Warum `table.seasonEpisodesList`:** Die Tabelle trägt eine stabile Klasse
und ein `data-season-id`-Attribut; IDs wie `#season1` wechseln je Staffel.

### Episodenseite / Streams

| Zweck         | Selektor                  | Anmerkung |
|---------------|---------------------------|-----------|
| Hoster-Links  | `a[href^='/redirect/']`   | z. B. `/redirect/2626179` |
| Hoster-Name   | `h4` innerhalb des Links  | „VOE“, „Doodstream“, „Filemoon“, „Vidmoly“ |

Die `/redirect/{id}`-URL folgt einer Redirect-/Iframe-Kette zum Hoster-Embed.
Der Provider löst sie mit [`resolveRedirect()`](../core/src/main/kotlin/com/icebergg/cs3/core/BaseGermanProvider.kt)
auf und übergibt den Embed an den eingebauten CloudStream-Extraktor
(`source = Hoster-Name`).

---

## 2. s.to / SerienStream – Version 2 ✅

- **Basis-URL:** `http://186.2.175.5` (offizieller IP-Mirror; Alternative
  `https://serienstream.to`, `https://serienstream.cx`).
- **Status:** SerienStream hat 2026 auf eine **neue Plattform (Version 2)**
  umgestellt – die alte SSO-Struktur (`/ajax/search`, `/redirect/{id}`) ist
  damit veraltet.

Live verifiziert am 04.09.2026:

| Zweck     | Endpoint / Selektor                                    |
|-----------|--------------------------------------------------------|
| Suche     | `GET /suche?q={query}` → Links auf `/serie/{slug}`      |
| Serie     | `GET /serie/{slug}`                                    |
| Staffeln  | `a[href*='/staffel-']:not([href*='/episode-'])`        |
| Episoden  | `a[href*='/episode-']` → `/serie/{slug}/staffel-{n}/episode-{n}` |
| Poster    | `img[data-src]` (Lazy-Load)                            |
| Streams   | `iframe[src]`, `[data-link]`, `a[href*='/redirect/']`  |

> 🟡 Der konkrete Hoster-Selektor der Episodenseite wird defensiv über drei
> Varianten abgedeckt (`iframe`, `data-link`, `redirect`). Er sollte nach dem
> ersten erfolgreichen DoH-Aufruf einmal gegengeprüft werden.

---

## 3. Kinox ⚠️ 🟡

- **Basis-URL:** `https://kinox.to` (wechselt regelmäßig)
- **Plattform:** Kinox-Codebasis (identisch zu Burning Series).

| Zweck    | Endpoint / Selektor                                        |
|----------|------------------------------------------------------------|
| Suche    | `GET /Search.html?q={query}`                               |
| Ergebnis | `a[href*='/Stream/']` (Episoden/Mirror-Links herausfiltern) |
| Serie    | `/Stream/{Addr}.html`                                      |
| Hoster   | `GET /aGET/MirrorByEpisode/?Addr={addr}&Episode={n}&Season={n}` |

⚠️ Alle getesteten Kinox-Domains (`kinox.to`, `kinox.sx`, `kinox.cam`) waren
zum Prüfzeitpunkt nicht erreichbar (DNS-Sperre, geparkte Domain oder
Cloudflare). Die Selektoren stammen aus der dokumentierten Kinox-Struktur und
**müssen nach dem ersten erfolgreichen DoH-Zugriff validiert werden**.

**Wartungs-Anleitung Kinox:**

1. Aktuelle Domain ermitteln (offizielle Mirror-Listen bzw. Suchmaschinen).
2. `BASE_URL` in [`KinoxProvider`](../kinox/src/main/kotlin/com/icebergg/cs3/kinox/KinoxProvider.kt) setzen.
3. Im Browser (DevTools → Network) prüfen, ob `Search.html` und
   `aGET/MirrorByEpisode` unverändert sind.
4. Ggf. die Selektoren in
   [`KinoxPlatformProvider`](../core/src/main/kotlin/com/icebergg/cs3/core/KinoxPlatformProvider.kt)
   anpassen (alle Selektoren sind dort als Konstanten zentralisiert).

---

## 4. Burning Series ⚠️ 🟡

- **Basis-URL:** `https://burning-series.co`
- **Plattform:** Kinox-Codebasis – Selektoren identisch zu Kinox.

⚠️ `burning-series.co` lieferte zum Prüfzeitpunkt **Cloudflare 526
(Invalid SSL certificate)**; `bs.to` löst nicht mehr auf. Burning Series
wechselt die Domain extrem häufig. Wartung wie bei Kinox (siehe oben);
`BASE_URL` in
[`BurningSeriesProvider`](../burningseries/src/main/kotlin/com/icebergg/cs3/burningseries/BurningSeriesProvider.kt)
aktualisieren.

---

## 5. KKiste ✅ (DataLife Engine)

- **Basis-URL:** `https://kkiste-io.ink`
- **Plattform:** DataLife Engine (DLE) – geteilt mit StreamCloud und MegaKino.

Live verifiziert am 04.09.2026:

| Zweck   | Endpoint / Selektor                                            |
|---------|---------------------------------------------------------------|
| Suche   | `GET /index.php?do=search&subaction=search&story={query}`      |
| Treffer | `article` mit `h2 a[href]` (Titel+Link) und `img` (Poster)     |
| Detail  | `article h1` (Titel), `article img[alt]` (Poster)              |
| Streams | `span[data-link]` → z. B. `https://meinecloud.click/movie/{imdb}` (Film) oder `https://voe.sx/e/…` (Serie) |

**Serien-Sonderfall:** KKiste listet eine Staffel als **eine** Seite mit
mehreren `span[data-link]` (ein Voe-Embed pro Episode). Der Provider erzeugt
daraus eine Episode je Embed (`data = "{url}#idx=N"`).

## 6. StreamCloud ✅ (DataLife Engine)

- **Basis-URL:** `https://streamcloud.date`
- **Plattform:** DataLife Engine – gleiche Struktur wie KKiste.

Live verifiziert am 04.09.2026:

| Zweck   | Endpoint / Selektor                                            |
|---------|---------------------------------------------------------------|
| Suche   | `GET /index.php?do=search&subaction=search&story={query}`      |
| Detail  | `/ID-slug-stream-deutsch.html` bzw. `/ID-slug-streaming-…`    |
| Streams | `<iframe src="https://meinecloud.click/movie/{imdbId}">`      |

**Hinweis:** Der `meinecloud.click`-Player lädt die eigentliche `mp4`/`m3u8`-
URL per JavaScript. Der Provider versucht eine rekursive Auflösung
([`resolveUrl()`](../core/src/main/kotlin/com/icebergg/cs3/core/BaseGermanProvider.kt));
falls der Player seine URL stark obfuskiert, muss der eingebaute
CloudStream-Extraktor bzw. ein manuelles Muster in
[`HostExtractor`](../core/src/main/kotlin/com/icebergg/cs3/core/HostExtractor.kt)
ergänzt werden.

## 7. MegaKino ⚠️ (DataLife Engine, Domain derzeit tot)

- **Basis-URL:** `https://megakino.fyi`
- **Status:** Die Domain leitet aktuell auf eine Antivirus-Werbe-Seite
  (totalav.com) weiter – **nicht mehr erreichbar**. Struktur ist DLE-identisch
  zu KKiste/StreamCloud. Nach Bekanntwerden einer neuen Domain genügt die
  Aktualisierung von `BASE_URL` in
  [`MegakinoProvider`](../megakino/src/main/kotlin/com/icebergg/cs3/megakino/MegakinoProvider.kt).

---

## 8. Host-Extraktoren (Voe, Doodstream, Filemoon, Vidmoly, MeineCloud)

Die CloudStream-App bringt eingebaute Extraktoren für diese Hoster mit. Die
Provider setzen deshalb nur den `source`-Namen. Für den Notfall (ohne
eingebaute Extraktoren) existieren manuelle Regex-Muster in
[`HostExtractor`](../core/src/main/kotlin/com/icebergg/cs3/core/HostExtractor.kt):

| Hoster    | Muster                                                       |
|-----------|--------------------------------------------------------------|
| Voe       | `(?:sources|source_url|streamUrl|video_url)\s*[:=]\s*["']…`  |
| Doodstream| `pass_md5\s*=\s*["']…` → `https://{host}/{pass_md5}`         |
| Filemoon  | `sources`-Variable bzw. `.m3u8/.mp4`-URL                     |
| Vidmoly   | `sources`-Variable bzw. `.m3u8/.mp4`-URL                     |
| generisch | `https?://…\.(mp4|m3u8|mpd|mkv|webm)…`                       |

> **Hinweis:** Hoster-Embed-Seiten ändern ihre Obfuskierung häufig. Die
> eingebauten CloudStream-Extraktoren sind die primäre, stets aktuelle Quelle.
> Die manuellen Muster sind nur Fallback und Referenz.
