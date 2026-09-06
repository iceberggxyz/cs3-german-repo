# IceBERGG – CloudStream 3 German Repository

Eine **eigenständige** CloudStream-3-Repository für den deutschen Markt –
aufgebaut nach der offiziellen CloudStream-Plugin-Vorlage (recloudstream/gradle),
mit eigenen, modularen Providern.

Enthalten sind sieben Provider:

| Provider       | Modul            | Inhalt                          | Plattform              |
|----------------|------------------|---------------------------------|------------------------|
| AniWorld       | `aniworld`       | Animes & Serien                 | SSO (aniworld.to)      |
| s.to           | `sto`            | Serien                          | SerienStream (s.to)    |
| Kinox          | `kinox`          | Filme & Serien                  | Kinox                  |
| Burning Series | `burningseries`  | Serien                          | Kinox                  |
| KKiste         | `kkiste`         | Filme & Serien                  | DataLife Engine        |
| StreamCloud    | `streamcloud`    | Filme & Serien                  | DataLife Engine        |
| MegaKino       | `megakino`       | Filme & Serien                  | DataLife Engine        |

---

## Projektstruktur

```
repo/
├── settings.gradle.kts          # bindet jedes Verzeichnis mit build.gradle.kts automatisch ein
├── build.gradle.kts             # Root-Build (CloudStream-Gradle-Plugin)
├── gradle.properties            # Gradle-/Android-Einstellungen
├── gradle/wrapper/              # Gradle-Wrapper (8.10.2)
├── repo.json                    # Repository-Index für CloudStream (pluginLists)
│
├── aniworld/                    # AniWorld-Plugin
├── sto/                         # s.to / SerienStream-Plugin
├── kinox/                       # Kinox-Plugin
├── burningseries/               # Burning-Series-Plugin
├── kkiste/                      # KKiste-Plugin
├── streamcloud/                 # StreamCloud-Plugin
├── megakino/                    # MegaKino-Plugin
│
├── scripts/build_release.ps1    # Release-Build-Skript
└── docs/
    ├── SELECTORS.md             # Scraping-Selektoren & Wartung
    └── DEPLOYMENT.md            # Build, Deployment auf GitHub
```

Jedes Provider-Modul ist **eigenständig** und enthält:

- `build.gradle.kts` – Plugin-Metadaten (`version`, `cloudstream { … }`)
- `src/main/AndroidManifest.xml` – leer (`<manifest />`)
- `src/main/kotlin/…` – Provider (`MainAPI`) + Plugin-Klasse (`Plugin`)

---

## Technischer Aufbau

- **Sprache:** Kotlin
- **Build-Tool:** Gradle (Kotlin DSL) + offizielles **CloudStream-Gradle-Plugin**
  (`com.github.recloudstream:gradle:-SNAPSHOT`)
- **CloudStream-API:** `com.lagradost:cloudstream3:pre-release` (Stub-Konfiguration,
  wird **nicht** in das Plugin gebündelt)
- **HTTP:** NiceHttp (`com.github.Blatzar:NiceHttp`) über die CloudStream-`app`
- **Scraping:** [JSoup](https://jsoup.org/) `1.21.2`
- **JSON:** Jackson `2.13.1` (nicht höher – Kompatibilität mit alten Geräten)

### Die Kern-Ideen

1. **Offizielles Plugin-System** – Jedes Modul wird vom CloudStream-Gradle-Plugin
   zu einer `.cs3`-Datei gebaut. Die Plugin-Klasse ist mit `@CloudstreamPlugin`
   annotiert und erweitert `com.lagradost.cloudstream3.plugins.Plugin`
   (`load(context: Context)`).

2. **CloudStream-eigene HTTP-Schicht** – Die Provider nutzen `app.get()` /
   `app.post()` mit Cookie- und Redirect-Handling der App.

3. **Eingebaute Hoster-Extraktoren** – Stream-URLs werden über
   `loadExtractor(…)` an die CloudStream-Extraktoren übergeben (Voe, Doodstream,
   Filemoon, Vidmoly, …). MegaKino und s.to registrieren zusätzlich ihre eigenen
   Extraktor-APIs (`registerExtractorAPI`).

4. **Kein Fork** – Die Provider-Anbieter (AniWorld, s.to/SerienStream, MegaKino)
   orientieren sich an bewährten öffentlichen Implementierungen, sind aber in
   eine eigenständige, deutsche Repo-Struktur gegossen.

---

## Voraussetzungen

- **JDK 17**
- **Android SDK** (Compile SDK 35) – z. B. über [`scripts/setup_sdk.ps1`](scripts/setup_sdk.ps1)
- Gradle 8.10.2 (Wrapper liegt im Repo, es ist keine lokale Gradle-Installation nötig)

---

## Build

Alle Plugins bauen und die Plugin-Liste erzeugen:

```powershell
.\scripts\build_release.ps1
```

Oder manuell:

```powershell
gradlew.bat make makePluginsJson
```

Das CloudStream-Gradle-Plugin erzeugt:

- `**/build/*.cs3` – die fertigen Plugin-Dateien
- `build/plugins.json` – die von `repo.json` referenzierte Plugin-Liste

`build_release.ps1` kopiert beides anschließend nach `dist/`.

---

## Installation in CloudStream 3

1. CloudStream öffnen → **Einstellungen → Erweiterungen**.
2. **Repository hinzufügen** und die URL der `repo.json` eintragen:

   ```
   https://raw.githubusercontent.com/<DEIN_USER>/cs3-german-repo/main/repo.json
   ```

3. Die gewünschten Provider installieren.

> **Wichtig:** Die `.cs3`-Dateien und die `plugins.json` müssen unter der in
> `repo.json` angegebenen `pluginLists`-URL erreichbar sein (üblicherweise ein
> separater `builds`-Branch). Details in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

> **Hinweis:** Kinox und Burning Series sind in Deutschland per CUII-DNS-Sperre
> blockiert. Aktiviere in CloudStream unter **Einstellungen → Netzwerk →
> DNS über HTTPS**, damit diese Seiten erreichbar sind.

---

## Wartung & Domain-Wechsel

Kinox, Burning Series, s.to und die DLE-Seiten wechseln ihre Domains regelmäßig.
Bei einem Domainwechsel genügt es, die Konstante `BASE_URL` in der jeweiligen
Provider-Klasse zu aktualisieren:

- [`KinoxProvider`](kinox/src/main/kotlin/com/icebergg/cs3/kinox/KinoxProvider.kt)
- [`BurningSeriesProvider`](burningseries/src/main/kotlin/com/icebergg/cs3/burningseries/BurningSeriesProvider.kt)
- [`StoProvider`](sto/src/main/kotlin/com/icebergg/cs3/sto/StoProvider.kt)
- [`KkisteProvider`](kkiste/src/main/kotlin/com/icebergg/cs3/kkiste/KkisteProvider.kt)
- [`StreamCloudProvider`](streamcloud/src/main/kotlin/com/icebergg/cs3/streamcloud/StreamCloudProvider.kt)
- [`Megakino`](megakino/src/main/kotlin/com/icebergg/cs3/megakino/MegakinoProvider.kt)

Die vollständige Selektoren-Referenz inkl. Verifikationsstatus befindet sich in
[`docs/SELECTORS.md`](docs/SELECTORS.md).

---

## Lizenz & Rechtliches

Dieses Projekt wird ausschließlich zu **Bildungs- und Forschungszwecken**
bereitgestellt. Die Nutzung der Streaming-Webseiten unterliegt den jeweiligen
Nutzungsbedingungen und der Rechtslage des Nutzers.
