<#
.SYNOPSIS
    Prueft, ob die konfigurierten Quellen erreichbar sind und ihre
    Kern-Selektoren noch greifen.

.DESCRIPTION
    Kein Ersatz fuer einen echten Provider-Test auf dem Geraet, aber ein
    schneller Indikator in CI: Wenn hier ein Marker fehlt, hat die Seite ihre
    Struktur geaendert und der Provider muss nachgezogen werden.

.PARAMETER SaveFixtures
    Speichert die abgerufenen HTML-Antworten unter fixtures/ zur spaeteren
    Auswertung.

.EXAMPLE
    pwsh -File scripts/smoke_test.ps1
    pwsh -File scripts/smoke_test.ps1 -SaveFixtures
#>
param(
    [switch]$SaveFixtures
)

$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'
$fixtureDir = Join-Path (Join-Path $PSScriptRoot '..') 'fixtures'
$failures = 0
$results = @()

if ($SaveFixtures -and -not (Test-Path $fixtureDir)) {
    New-Item -ItemType Directory -Path $fixtureDir -Force | Out-Null
}

function Test-Source {
    param(
        [string]$Name,
        [string]$Url,
        [string]$Marker,
        [string]$FixtureName,
        # Domains, die in Deutschland per DNS gesperrt sind: ein
        # Namensaufloesungs-Fehler wird dann dokumentiert, aber nicht gewertet.
        [switch]$DnsBlockedInDe
    )

    $status = 'FAIL'
    $detail = ''
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 30 -Headers @{ 'User-Agent' = $ua }
        $body = $r.Content
        $found = $body -match $Marker
        $status = if ($found) { 'ok' } else { 'MARKER FEHLT' }
        $detail = "HTTP $($r.StatusCode), $($body.Length) Bytes"
        if ($SaveFixtures -and $FixtureName) {
            $path = Join-Path $fixtureDir $FixtureName
            Set-Content -Path $path -Value $body -Encoding UTF8
            $detail += ", Fixture gespeichert"
        }
    } catch {
        $msg = $_.Exception.Message
        $resolvesNothing = $msg -match 'aufgel|resolved|Name or service not known|No address'
        if ($DnsBlockedInDe -and $resolvesNothing) {
            $status = 'skip (DNS-Sperre DE)'
            $detail = 'erwartbar - in CI/US-Netz aussagekraeftig'
        } else {
            $code = 'ERR'
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            $detail = "HTTP $code - $msg"
        }
    }

    if ($status -ne 'ok' -and $status -notlike 'skip*') { $script:failures++ }

    $script:results += [pscustomobject]@{
        Quelle = $Name
        Status = $status
        Detail = $detail
        URL    = $Url
    }
}

# Ergebnis erfassen (gemeinsam fuer die spezialisierten Pruefungen unten).
function Record {
    param(
        [string]$Name,
        [string]$Status,
        [string]$Detail,
        [string]$Url
    )
    if ($Status -ne 'ok' -and $Status -notlike 'skip*') { $script:failures++ }
    $script:results += [pscustomobject]@{
        Quelle = $Name
        Status = $Status
        Detail = $Detail
        URL    = $Url
    }
}

<#
    MegaKino schuetzt jede Seite mit einem yg_token-Cookie. Ohne Handshake
    liefert der Server nur ein 243-Byte-Stub - genau der Zustand, in dem die
    Startseite frueher leer blieb. Diese Pruefung bildet den Provider nach.
#>
function Test-SourceWithSession {
    param(
        [string]$Name,
        [string]$TokenUrl,
        [string]$Url,
        [string]$Marker,
        [string]$FixtureName
    )

    $status = 'FAIL'
    $detail = ''
    try {
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        Invoke-WebRequest -Uri $TokenUrl -WebSession $session -UseBasicParsing -TimeoutSec 30 `
            -Headers @{ 'User-Agent' = $ua } | Out-Null

        $r = Invoke-WebRequest -Uri $Url -WebSession $session -UseBasicParsing -TimeoutSec 30 `
            -Headers @{ 'User-Agent' = $ua }
        $body = $r.Content
        $status = if ($body -match $Marker) { 'ok' } else { 'MARKER FEHLT' }
        $detail = "HTTP $($r.StatusCode), $($body.Length) Bytes"
        if ($SaveFixtures -and $FixtureName) {
            Set-Content -Path (Join-Path $fixtureDir $FixtureName) -Value $body -Encoding UTF8
            $detail += ", Fixture gespeichert"
        }
    } catch {
        $detail = "ERR - $($_.Exception.Message)"
    }

    Record -Name $Name -Status $status -Detail $detail -Url $Url
}

<#
    Suche per POST (DLE-Formular). Deckt die Suche ab, die ohne DoH bzw. ohne
    passenden User-Agent als leere Liste zurueckkam.
#>
function Test-SourcePost {
    param(
        [string]$Name,
        [string]$Url,
        [hashtable]$Body,
        [string]$Marker,
        [string]$FixtureName
    )

    $status = 'FAIL'
    $detail = ''
    try {
        $r = Invoke-WebRequest -Uri $Url -Method Post -Body $Body -UseBasicParsing -TimeoutSec 30 `
            -Headers @{ 'User-Agent' = $ua }
        $content = $r.Content
        $status = if ($content -match $Marker) { 'ok' } else { 'MARKER FEHLT' }
        $detail = "HTTP $($r.StatusCode), $($content.Length) Bytes"
        if ($SaveFixtures -and $FixtureName) {
            Set-Content -Path (Join-Path $fixtureDir $FixtureName) -Value $content -Encoding UTF8
            $detail += ", Fixture gespeichert"
        }
    } catch {
        $detail = "ERR - $($_.Exception.Message)"
    }

    Record -Name $Name -Status $status -Detail $detail -Url $Url
}

<#
    Prueft die Hoster-Liste einer Detailseite so, wie der Provider sie liest.
    Trailer (YouTube & Co.) duerfen vorkommen - sie werden vom zentralen
    StreamFilter verworfen. Entscheidend ist, dass mindestens ein echter Hoster
    uebrig bleibt.
#>
function Test-HosterList {
    param(
        [string]$Name,
        [string]$Url,
        [string]$Pattern,
        [string]$TokenUrl
    )

    $status = 'FAIL'
    $detail = ''
    try {
        if ($TokenUrl) {
            $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
            Invoke-WebRequest -Uri $TokenUrl -WebSession $session -UseBasicParsing -TimeoutSec 30 `
                -Headers @{ 'User-Agent' = $ua } | Out-Null
            $r = Invoke-WebRequest -Uri $Url -WebSession $session -UseBasicParsing -TimeoutSec 30 `
                -Headers @{ 'User-Agent' = $ua }
        } else {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 30 -Headers @{ 'User-Agent' = $ua }
        }

        $matches = [regex]::Matches($r.Content, $Pattern)
        $urls = @($matches | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
        $trailer = @($urls | Where-Object { $_ -match 'youtube|youtu\.be|vimeo|dailymotion|facebook' })
        $playable = @($urls | Where-Object { $_ -notmatch 'youtube|youtu\.be|vimeo|dailymotion|facebook' })

        $status = if ($playable.Count -gt 0) { 'ok' } else { 'KEIN HOSTER' }
        $detail = "$($urls.Count) Hoster, davon $($trailer.Count) Trailer (vom StreamFilter verworfen)"
    } catch {
        $detail = "ERR - $($_.Exception.Message)"
    }

    Record -Name $Name -Status $status -Detail $detail -Url $Url
}

# --- Startseiten -----------------------------------------------------------
Test-Source -Name 'AniWorld'     -Url 'https://aniworld.to/'            -Marker 'carousel'          -FixtureName 'aniworld-start.html'
Test-Source -Name 'Filmpalast'   -Url 'https://filmpalast.to/'          -Marker 'article class="liste' -FixtureName 'filmpalast-start.html' -DnsBlockedInDe
Test-Source -Name 'KKiste'       -Url 'https://kkiste-io.ink/'          -Marker '<article'          -FixtureName 'kkiste-start.html'
Test-Source -Name 's.to'         -Url 'https://serienstream.si/'        -Marker '/serie/'           -FixtureName 'sto-start.html'
Test-Source -Name 'StreamCloud'  -Url 'https://streamcloud.date/filme'  -Marker '/filme/'           -FixtureName 'streamcloud-filme.html'

# --- Suche -----------------------------------------------------------------
Test-Source -Name 'Filmpalast-Suche' -Url 'https://filmpalast.to/search/title/godzilla'      -Marker '/stream/'   -FixtureName 'filmpalast-suche.html' -DnsBlockedInDe
Test-Source -Name 's.to-Suche'       -Url 'https://serienstream.si/suche?q=breaking'         -Marker '/serie/'    -FixtureName 'sto-suche.html'
Test-Source -Name 'StreamCloud-Suche'-Url 'https://streamcloud.date/suche?q=breaking'        -Marker '/serie/'    -FixtureName 'streamcloud-suche.html'

# --- MegaKino (Token-Handshake wie im Provider) -----------------------------
Test-SourceWithSession -Name 'MegaKino-Start' -TokenUrl 'https://megakino29.com/index.php?yg=token' `
    -Url 'https://megakino29.com/films/' -Marker 'poster grid-item' -FixtureName 'megakino-films.html'
Test-SourceWithSession -Name 'MegaKino-Detail' -TokenUrl 'https://megakino29.com/index.php?yg=token' `
    -Url 'https://megakino29.com/films/6667-hokum.html' -Marker 'film_main'
# Katalogseite des Mirrors: Detail-Links enden auf "-stream-deutsch".
Test-Source -Name 'MegaKino-Mirror' -Url 'https://1megakino.cc/kinofilme-online/' -Marker '-stream-deutsch'

# --- Hoster-Listen (so, wie der Provider sie liest) ------------------------
Test-HosterList -Name 'MegaKino-Hoster' -TokenUrl 'https://megakino29.com/index.php?yg=token' `
    -Url 'https://megakino29.com/films/6667-hokum.html' `
    -Pattern 'id="film_main"[^>]*(?:data-src|src)="([^"]+)"'
Test-HosterList -Name '1MegaKino-Hoster' `
    -Url 'https://1megakino.cc/26096-forgotten-island-die-vergessene-insel-stream-deutsch-kostenlos.html' `
    -Pattern 'data-link="(https?://[^"]+)"'
Test-HosterList -Name 'KKiste-Hoster' `
    -Url 'https://kkiste-io.ink/24540-hokum-stream-deutsch-kostenlos.html' `
    -Pattern 'data-link="(https?://[^"]+)"'

# --- Detailseiten und POST-Suche -------------------------------------------
Test-Source -Name 'AniWorld-Detail' -Url 'https://aniworld.to/anime/stream/one-piece' -Marker '/staffel-' -FixtureName 'aniworld-detail.html'
# "hokum" liefert verlaesslich genau einen Treffer (verifiziert 24.11.2026).
Test-SourcePost -Name 'KKiste-Suche' -Url 'https://kkiste-io.ink/' `
    -Body @{ do = 'search'; subaction = 'search'; story = 'hokum' } `
    -Marker 'hokum' -FixtureName 'kkiste-suche.html'

# --- Ausgabe ---------------------------------------------------------------
$results | Format-Table -AutoSize | Out-String -Width 200 | Write-Output

if ($env:GITHUB_STEP_SUMMARY) {
    $md = "| Quelle | Status | Detail |`n|---|---|---|`n"
    foreach ($r in $results) {
        $md += "| $($r.Quelle) | $($r.Status) | $($r.Detail) |`n"
    }
    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value $md
}

if ($failures -gt 0) {
    Write-Output "$failures Quelle(n) fehlgeschlagen."
    exit 1
}

Write-Output 'Alle Quellen in Ordnung.'
exit 0
