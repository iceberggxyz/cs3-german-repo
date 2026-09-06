# ============================================================================
#  setup_sdk.ps1
#  ------------------------------------------------------------
#  Installiert das Android SDK automatisch (nur Command-line Tools,
#  KEIN Android Studio noetig) und legt local.properties an.
#
#  Verwendung (cmd oder PowerShell):
#    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup_sdk.ps1
# ============================================================================
$ErrorActionPreference = "Stop"

$sdkRoot = "C:\Android"
$cmdlineToolsDir = Join-Path $sdkRoot "cmdline-tools\latest"
$sdkmanager = Join-Path $cmdlineToolsDir "bin\sdkmanager.bat"

# Zielverzeichnis sicherstellen (sonst schlaegt Invoke-WebRequest -OutFile fehl).
New-Item -ItemType Directory -Force -Path $sdkRoot | Out-Null

# ---------------------------------------------------------------------------
# 1) JAVA_HOME ermitteln (JDK 17+)
# ---------------------------------------------------------------------------
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    $searchRoots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto"
    )
    foreach ($r in $searchRoots) {
        if (Test-Path $r) {
            $jdk = Get-ChildItem -Path $r -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
                Sort-Object Name -Descending |
                Select-Object -First 1
            if ($jdk) { $javaHome = $jdk.FullName; break }
        }
    }
}
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "Keine JDK gefunden. Bitte JDK 17 (oder neuer) installieren."
}
$env:JAVA_HOME = $javaHome
Write-Host "==> JAVA_HOME = $javaHome"

# ---------------------------------------------------------------------------
# 2) Android Command-line Tools herunterladen + entpacken
# ---------------------------------------------------------------------------
if (-not (Test-Path $sdkmanager)) {
    Write-Host "==> Lade Android Command-line Tools herunter ..."
    # Stabile Version 11076708 (klassisches, funktionierendes sdkmanager).
    # Die neueste Version (16111833) enthaelt einen defekten "android"-CLI-Shim,
    # der bei "platforms;android-35" mit STATUS_STACK_BUFFER_OVERRUN abstuerzt.
    $url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    Write-Host "==> URL: $url"
    $zip = Join-Path $sdkRoot "cmdline-tools.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip

    Write-Host "==> Entpacke ..."
    $unpackDir = Join-Path $sdkRoot "unpack"
    Expand-Archive -Path $zip -DestinationPath $unpackDir -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot "cmdline-tools") | Out-Null
    Move-Item -Path (Join-Path $unpackDir "cmdline-tools") -Destination $cmdlineToolsDir -Force
    Remove-Item $unpackDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "==> Command-line Tools bereits vorhanden: $cmdlineToolsDir"
}

# ---------------------------------------------------------------------------
# 3) SDK-Komponenten installieren
# ---------------------------------------------------------------------------
$env:ANDROID_HOME = $sdkRoot
Write-Host "==> Installiere SDK-Komponenten (kann einige Minuten dauern) ..."
& $sdkmanager --sdk_root=$sdkRoot "platform-tools" "platforms;android-35" "build-tools;34.0.0" "build-tools;35.0.0"
if ($LASTEXITCODE -ne 0) { throw "sdkmanager fehlgeschlagen (Exit-Code $LASTEXITCODE)" }

# ---------------------------------------------------------------------------
# 4) Lizenzen akzeptieren
# ---------------------------------------------------------------------------
Write-Host "==> Akzeptiere Lizenzen ..."
1..30 | ForEach-Object { "y" } | & $sdkmanager --sdk_root=$sdkRoot --licenses

# ---------------------------------------------------------------------------
# 5) local.properties schreiben (Forward-Slashes vermeiden Escape-Probleme)
# ---------------------------------------------------------------------------
$sdkPath = $sdkRoot.Replace('\', '/')
Set-Content -Path "local.properties" -Value "sdk.dir=$sdkPath" -Encoding ASCII
Write-Host "==> local.properties erstellt: sdk.dir=$sdkPath"
Write-Host "==> Fertig. Android SDK liegt unter $sdkRoot"
