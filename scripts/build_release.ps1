# =============================================================================
#  IceBERGG CloudStream-3 German Repository – Build-Skript
# -----------------------------------------------------------------------------
#  Baut alle Provider-Plugins (.cs3) mit dem offiziellen CloudStream-Gradle-
#  Plugin und generiert die Plugin-Liste (build/plugins.json).
#
#  Ergebnis:
#    dist/*.cs3           – fertige Plugin-Dateien
#    dist/plugins.json    – Repo-Plugin-Liste (in den `builds`-Branch pushen)
# =============================================================================

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# -----------------------------------------------------------------------------
# 1) JAVA_HOME automatisch erkennen, falls nicht (korrekt) gesetzt.
# -----------------------------------------------------------------------------
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $searchRoots = @(
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )

    $java = Get-ChildItem -Path $searchRoots -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "jdk[-_]?17" } |
        Select-Object -First 1

    if ($java) {
        $env:JAVA_HOME = $java.DirectoryName
        Write-Host "JAVA_HOME automatisch gesetzt auf: $env:JAVA_HOME"
    }
    else {
        Write-Error "Kein JDK 17 gefunden. Bitte JAVA_HOME manuell setzen."
    }
}

# -----------------------------------------------------------------------------
# 2) ANDROID_HOME setzen und in local.properties schreiben.
# -----------------------------------------------------------------------------
$sdkCandidates = @("C:\Android", "$env:LOCALAPPDATA\Android\Sdk", "$env:ANDROID_HOME")
$sdk = $sdkCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if (-not $sdk) {
    Write-Error "Kein Android SDK gefunden. Bitte scripts\setup_sdk.ps1 ausführen oder ANDROID_HOME setzen."
}

$env:ANDROID_HOME = $sdk
$sdkProp = ($sdk -replace '\\', '/')
"sdk.dir=$sdkProp" | Set-Content -Path "local.properties" -Encoding utf8
Write-Host "Android SDK: $sdk"

# -----------------------------------------------------------------------------
# 3) Build ausführen (CloudStream-Gradle-Plugin).
# -----------------------------------------------------------------------------
Write-Host "Baue Plugins (make + makePluginsJson) ..."
& ".\gradlew.bat" make makePluginsJson
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)."
}

# -----------------------------------------------------------------------------
# 4) Ergebnisse nach dist/ kopieren.
# -----------------------------------------------------------------------------
$dist = Join-Path $Root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

Get-ChildItem -Path $Root -Recurse -Filter "*.cs3" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch "\\dist\\" } |
    ForEach-Object { Copy-Item $_.FullName -Destination $dist -Force }

if (Test-Path (Join-Path $Root "build\plugins.json")) {
    Copy-Item (Join-Path $Root "build\plugins.json") -Destination $dist -Force
}

Write-Host ""
Write-Host "Fertig. Folgende Dateien wurden erzeugt:"
Get-ChildItem -Path $dist | ForEach-Object { Write-Host ("  " + $_.Name) }
Write-Host ""
Write-Host "Hinweis: dist/plugins.json und dist/*.cs3 in den GitHub-Branch"
Write-Host "`"builds`" pushen, damit repo.json darauf verweisen kann."
