# Changelog

Alle nennenswerten Änderungen an diesem Repository.

## Unveröffentlicht

- **MegaKino reaktiviert** (v5, Status 1): Der Provider zielt jetzt auf
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
