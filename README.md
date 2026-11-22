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

## DNS over HTTPS aktivieren (wichtig)

Mehrere Quellen sind in Deutschland per DNS gesperrt – die Seiten laufen also,
sind aber über den Standard-DNS nicht auflösbar. CloudStream kann das umgehen:

1. CloudStream öffnen → **Einstellungen**
2. **DNS over HTTPS** (DoH) aktivieren
3. Als Resolver **Cloudflare** wählen (`https://cloudflare-dns.com/dns-query`)

Alternativ funktionieren auch andere DoH-Anbieter. Nach dem Umschalten die App
einmal neu starten und die Erweiterungen aktualisieren.

Ohne DoH bleiben `filmpalast.to` und `serienstream.si` (s.to) unerreichbar.

## Enthaltene Provider

| Provider       | Inhalt          | Hinweis              |
|----------------|-----------------|----------------------|
| AniWorld       | Animes & Serien |                      |
| Filmpalast     | Filme & Serien  | benötigt DoH         |
| KKiste         | Filme & Serien  |                      |
| s.to           | Serien          | benötigt DoH         |
| StreamCloud    | Filme & Serien  |                      |
| Burning Series | Serien          | derzeit nicht verfügbar |
| Kinox          | Filme & Serien  | derzeit nicht verfügbar |
| MegaKino       | Filme & Serien  | derzeit nicht verfügbar |

> Alle Inhalte stammen von den jeweiligen Anbietern. Dieses Repository stellt
> lediglich die Schnittstelle für CloudStream bereit.
