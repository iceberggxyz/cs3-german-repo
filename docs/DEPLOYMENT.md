# Build & Deployment

Anleitung, wie die Provider gebaut und über GitHub als CloudStream-Repository
ausgeliefert werden. Das Projekt nutzt das **offizielle CloudStream-Gradle-Plugin**,
das aus jedem Modul eine `.cs3`-Plugin-Datei erzeugt.

---

## 1. Einmalige Einrichtung

Voraussetzungen:

- **JDK 17**
- **Android SDK** (Compile SDK 35) – Installation z. B. über
  [`scripts/setup_sdk.ps1`](../scripts/setup_sdk.ps1)
- **Gradle 8.10.2** – der Wrapper (`gradlew.bat`) liegt bereits im Repo

```powershell
git clone https://github.com/<DEIN_USER>/cs3-german-repo.git
cd cs3-german-repo
```

---

## 2. Plugins bauen

```powershell
.\scripts\build_release.ps1
```

Das Skript führt intern aus:

```powershell
gradlew.bat make makePluginsJson
```

Dabei entstehen:

- `**/build/*.cs3` – die fertigen Plugin-Dateien (eine je Provider)
- `build/plugins.json` – die Plugin-Liste, auf die `repo.json` verweist

`build_release.ps1` kopiert beides nach `dist/`:

```
dist/
├── aniworld.cs3
├── sto.cs3
├── kinox.cs3
├── burningseries.cs3
├── kkiste.cs3
├── streamcloud.cs3
├── megakino.cs3
└── plugins.json
```

---

## 3. Deployment auf GitHub (empfohlener Weg)

Das offizielle Muster nutzt einen separaten **`builds`**-Branch, in den nur die
gebauten Artefakte gepusht werden. `repo.json` zeigt dann per `pluginLists`
auf die dort liegende `plugins.json`.

```powershell
# 1) Quellcode pushen
git add .
git commit -m "Initial commit"
git push origin main

# 2) Builds-Branch anlegen und Artefakte pushen
git checkout --orphan builds
git rm -rf .
copy dist\* .
git add .
git commit -m "Build"
git push origin builds
```

Danach ist die Plugin-Liste erreichbar unter:

```
https://raw.githubusercontent.com/<DEIN_USER>/cs3-german-repo/builds/plugins.json
```

Dieser URL-Pfad ist bereits in [`repo.json`](../repo.json) hinterlegt.
Alternativ lässt sich der Build per GitHub-Actions automatisieren (Vorlage:
`.github/workflows/build.yml`, Aufgaben `make` + `makePluginsJson`).

---

## 4. repo.json ausliefern

Die [`repo.json`](../repo.json) nutzt das aktuelle `pluginLists`-Format:

```json
{
  "name": "IceBERGG German Streaming Repository",
  "manifestVersion": 1,
  "pluginLists": [
    "https://raw.githubusercontent.com/<DEIN_USER>/cs3-german-repo/builds/plugins.json"
  ]
}
```

Sie muss über eine rohe HTTPS-URL erreichbar sein:

```
https://raw.githubusercontent.com/<DEIN_USER>/cs3-german-repo/main/repo.json
```

---

## 5. Installation in CloudStream 3

1. CloudStream öffnen → **Einstellungen → Erweiterungen**.
2. **Repository hinzufügen** mit der `repo.json`-URL (Schritt 4).
3. Die gewünschten Provider installieren.
4. Unter **Einstellungen → Netzwerk → DNS über HTTPS** einen DoH-Provider
   (Cloudflare/Google) aktivieren – nötig für die CUII-gesperrten Seiten
   (Kinox, Burning Series).

---

## 6. Aktualisieren einer Domain

Bei einem Domain-Wechsel (häufig bei Kinox/Burning Series/s.to/DLE-Seiten):

1. `BASE_URL` in der jeweiligen Provider-Klasse ändern.
2. `version` in der Modul-`build.gradle.kts` um 1 erhöhen.
3. Neu bauen (`make makePluginsJson`) und die Artefakte in den `builds`-Branch
   pushen. `plugins.json` wird dabei automatisch aktualisiert.
