# IceBERGG – CloudStream German Repository

Deutsche Provider für **CloudStream** (recloudstream).
Die Plugins werden als `.cs3` über den Branch `builds` ausgeliefert.

## In CloudStream hinzufügen

1. CloudStream öffnen → **Einstellungen** → **Erweiterungen**
2. **Repository hinzufügen** auswählen
3. Diese URL eintragen:

```
https://raw.githubusercontent.com/iceberggxyz/cs3-german-repo/main/repo.json
```

4. Speichern, danach unter **Erweiterungen** die gewünschten Plugins installieren

Updates werden von CloudStream automatisch angeboten.

### Warum ein Update manchmal nicht sofort erscheint

- Die Plugin-Liste wird **15 Minuten** zwischengespeichert. Nach einem neuen
  Build dauert es also bis zu einer Viertelstunde, bis die App sie sieht.
- CloudStream prüft beim Download den in `plugins.json` hinterlegten SHA-256.
  Passt er nicht zur `.cs3`, bricht die Installation mit
  `Extension hash mismatch` ab. Deshalb werden beide Dateien immer im selben
  Commit veröffentlicht (siehe `.github/workflows/build.yml`).

## DNS over HTTPS aktivieren (wichtig)

Mehrere Quellen sind in Deutschland per DNS gesperrt – die Seiten laufen also,
sind aber über den Standard-DNS nicht auflösbar. CloudStream kann das umgehen:

1. CloudStream öffnen → **Einstellungen**
2. **DNS over HTTPS** (DoH) aktivieren
3. Als Resolver **Google** wählen (`https://dns.google/dns-query`)

Empfehlung: **Google**. Cloudflare (`https://cloudflare-dns.com/dns-query`)
funktioniert ebenfalls, löst aber einzelne Domains nicht auf – `kkiste-io.ink`
ist dafür ein belegtes Beispiel.

Nach dem Umschalten die App einmal neu starten und die Erweiterungen
aktualisieren.

Ohne DoH bleiben `filmpalast.to`, `serienstream.si` (s.to), `kkiste-io.ink`
und `megakino29.com` unerreichbar.

### Wenn eine Quelle keine Ergebnisse liefert

Liefert ein Provider trotz aktivem DoH nichts (Suche leer, Startseite leer,
Anbietertest bricht ab), zuerst **den Resolver wechseln** – nicht den Provider
neu installieren. Der Grund ist fast immer die Namensauflösung, nicht der Code:
ein blockierter Host liefert keine Antwort, und die Provider behandeln das
bewusst defensiv (leere Liste statt Absturz).

Sichtbar wird das im Log an Zeilen wie `[KKiste] no working mirror` – dort
scheitert die Mirror-Prüfung, bevor überhaupt gesucht wird.

## Enthaltene Provider

| Provider       | Inhalt          | Hinweis              |
|----------------|-----------------|----------------------|
| AniWorld       | Animes & Serien |                      |
| Filmpalast     | Filme & Serien  | benötigt DoH         |
| KKiste         | Filme & Serien  |                      |
| s.to           | Serien          | benötigt DoH         |
| StreamCloud    | Filme & Serien  |                      |
| MegaKino       | Filme & Serien  | Mirror: `1megakino.cc` (Suche dort per Cloudflare gesperrt) |
| Kinox          | Filme & Serien  | derzeit nicht verfügbar |

> Alle Inhalte stammen von den jeweiligen Anbietern. Dieses Repository stellt
> lediglich die Schnittstelle für CloudStream bereit.
