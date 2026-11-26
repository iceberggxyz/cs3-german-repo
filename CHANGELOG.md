# Changelog

Alle nennenswerten Änderungen an diesem Repository.

## Prüfstand 26.11.2026

Automatisch geprüft für den Build dieses Stands:

| Prüfung | Ergebnis |
|---|---|
| `scripts/smoke_test.ps1` | 13 von 13 Prüfungen grün (2 × `skip`: Filmpalast per DNS-Sperre) |
| MegaKino `yg_token`-Handshake | Startseite 65983 B, Detailseite enthält `film_main` |
| KKiste-POST-Suche | 150326 B, Treffer `hokum` enthalten |
| `plugins.json` ↔ `.cs3` | 7 von 7 Hashes identisch, lokal und auf `raw.githubusercontent.com` |
| Versionen | aniworld 3, filmpalast 6, kinox 5, kkiste 6, megakino 7, sto 9, streamcloud 11 |

Noch offen: **Anbietertest auf dem Gerät** für alle sieben Provider
(CloudStream → Einstellungen → Erweiterungen → Anbieter testen). Ergebnis nach
dem Lauf hier nachtragen – besonders für MegaKino (Startseite, Film, Serie) und
KKiste (Suche, Stream-Start).

## Unveröffentlicht

- **Manifest-Fehler behoben**: `aniworld` nutzte `tvTypes = "AnimeMovies"` und
  `sto` `"Series"` – beides sind keine gültigen `TvType`-Werte (richtig:
  `AnimeMovie`, `TvSeries`). Die Plugins waren damit keinem Typ-Filter zuordenbar.
- **`HosterResolver` zentralisiert**: lag sechsmal als Kopie in den Modulen, jetzt
  einmal in `shared/` und per Gradle-Source-Set in allen sechs Modulen eingebunden.
- **`shared/Requests.kt`**: gemeinsamer Netzwerk-Layer mit `USER_AGENT`-Header,
  deaktiviertem Antwort-Cache und einem Retry bei 403/429/503.
- **Fehler statt leerer Listen**: `search` und `load` werfen bei nicht erreichbarer
  Quelle `ErrorLoadingException` mit Provider-Präfix. „Nichts gefunden“ und
  „Quelle kaputt“ sind damit unterscheidbar. `getMainPage` bleibt bewusst defensiv.
- **Monitor** prüft jetzt die real genutzten Domains (u. a. `megakino29.com`,
  `1megakino.cc`, `kkiste-io.com`), inklusive Marker-Prüfung im HTML und
  Token-Handshake für MegaKino. Offene Ausfall-Issues werden bei Erholung
  automatisch geschlossen.
- **CI**: Gradle-Cache aktiviert und ein Hash-Gegencheck zwischen `plugins.json`
  und den `.cs3`-Dateien ergänzt (verhindert `Extension hash mismatch`).
- **Smoke-Test** deckt MegaKino (Start + Detail, mit Token-Handshake), die
  KKiste-POST-Suche und eine AniWorld-Detailseite ab.
- `repo.json`-Beschreibung nennt nicht mehr das entfernte Burning Series.
- README: **Google** als empfohlenen DoH-Resolver dokumentiert. Cloudflare löst
  `kkiste-io.ink` nicht auf – dort greift der DoH-Resolver, nicht der Provider.
  Domain-Liste um `kkiste-io.ink` und `megakino29.com` erweitert, plus Hinweis
  „bei leerer Quelle zuerst den Resolver wechseln“.
- **MegaKino reaktiviert** (v5/v6, Status 1): Der Provider zielt jetzt auf
  `megakino29.com` (DataLife Engine, Template `popcornie-dark`). Streams stehen
  pro Hoster als Iframe mit `data-src` in der Detailseite, Serien als
  `select#ep1…epN`. Der alte `gxplayer`-Pfad entfällt damit vollständig.
- `megakino29.com` schützt jede Seite mit einem `yg_token`-Cookie-Handshake
  (15 Minuten gültig). Ohne Token liefert der Server nur ein 243-Byte-Stub
  (`location.replace(...)`); der Provider holt den Token vorab und erneuert ihn
  bei Bedarf automatisch.
- **MegaKino-Mirror `1megakino.cc`** (Template `megaKino`): Hoster-Ziele stehen
  als `span[data-link]` (devideosrc/Vidara/VOE/FrStream). Nur Startseite,
  Detailseiten und Streams – die Suche ist dort durch Cloudflare-Turnstile
  gesperrt (HTTP 403) und liefert eine leere Liste.
- `megakino28.top` wird bewusst nicht unterstützt: eigene Plattform mit
  VOE-Klon als einziger Quelle.
- Fehlerbehandlung: alle Provider fangen HTTP-/Parse-Fehler ab, statt eine
  Exception an die App durchzureichen.
- `ExtractorLinkType.M3U8` für devideosrc-Quellen gesetzt.
- IMDb-IDs werden an die App übergeben (`addImdbId`), wo sie bereits im HTML stehen.
- Domain-Failover mit Inhaltsprüfung zusätzlich für AniWorld (`.to`, `.cc`, `.cx`)
  und KKiste (`kkiste-io.ink`, `kkiste-io.com`).
- `HosterResolver` in allen aktiven Providern: VOE-Kurzlinks werden normalisiert,
  Vorschalt-Seiten (z. B. Vidara) über ihren iframe aufgelöst.
- Burning Series entfernt: der Code war eine Kinox-Kopie und passte nicht zur
  bs.to-Engine der aktuellen Seite.
- GitHub Actions: Build und Deployment auf den `builds`-Branch in einem Commit
  (verhindert `Extension hash mismatch`).
- Täglicher Monitor-Workflow prüft die Erreichbarkeit aller Quellen.
- LICENSE (MIT), `.editorconfig`, CHANGELOG ergänzt.

## 2026-11-22

- Neuer Provider **Filmpalast** (Suche über `/search/title/`, direkte Hoster-Links).
- Filmpalast: Sterne-Icons nicht mehr als Poster, Serien-Episoden zu einem
  Serientreffer gruppiert.
- Filmpalast: `data-player-url` wird gelesen, VOE-Kurzlink normalisiert.
- s.to und StreamCloud: Hoster-Embeds über `JsUnpacker` entpackt, HLS mit
  `Referer` ausgeliefert (behebt `ERROR_CODE_IO_BAD_HTTP_STATUS`).
- Startseiten für s.to, StreamCloud und KKiste.
- StreamCloud: echte Folgentitel für die gerenderte Staffel.
- Kinox, Burning Series und MegaKino vorübergehend als „Down“ markiert.
- README auf Zweck und Installation gekürzt, `docs/` entfernt.
