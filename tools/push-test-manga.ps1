# Pushes the synthetic test pages to Mihon's local source on the running emulator.
#
# Layout comes from source-local/.../LocalSource.kt and StorageManager.kt:
#   <storage>/local/<Manga Title>/<Chapter Name>/<page>.png
# where <storage> is the folder picked during onboarding, and a chapter is a plain
# directory of images. The cover is copied from the first page automatically.
#
# Run tools/make-test-pages.py first. Requires a booted emulator (adb devices).

[CmdletBinding()]
param(
    # The storage root chosen in Mihon's onboarding.
    [string]$StorageRoot = '/storage/emulated/0/Mihon',

    [string]$MangaTitle = 'Bubble Zoom Test',

    [string]$ChapterName = 'Chapter 001'
)

$ErrorActionPreference = 'Stop'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb. Is ANDROID_HOME set?" }

$pagesDir = Join-Path $PSScriptRoot 'test-pages'
$pages = Get-ChildItem -Path $pagesDir -Filter '*.png' -ErrorAction SilentlyContinue | Sort-Object Name
if (-not $pages) { throw "No pages in $pagesDir. Run: python tools/make-test-pages.py" }

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if (-not $devices) { throw 'No emulator attached. Start it with tools/start-emulator.ps1.' }

$chapterDir = "$StorageRoot/local/$MangaTitle/$ChapterName"

Write-Host "Pushing $($pages.Count) pages to $chapterDir" -ForegroundColor Cyan

# A stale chapter would leave pages from a previous run interleaved with the new
# ones, so clear just this chapter before pushing.
& $adb shell rm -rf "'$chapterDir'" | Out-Null
& $adb shell mkdir -p "'$chapterDir'" | Out-Null

foreach ($page in $pages) {
    & $adb push $page.FullName "$chapterDir/$($page.Name)" | Out-Null
    Write-Host "  $($page.Name)"
}

# Mihon reads the local source from disk on demand, so no media scan is needed,
# but the library entry only appears after the local source is browsed or the
# library is refreshed. Nudge the app to re-read rather than relying on a restart.
# `monkey` writes its banner to stderr, which trips $ErrorActionPreference='Stop';
# am start is deterministic and quiet.
& $adb shell am force-stop app.mihon.dev | Out-Null
& $adb shell am start -n app.mihon.dev/eu.kanade.tachiyomi.ui.main.MainActivity | Out-Null

Write-Host ''
Write-Host 'Done. In the app: Browse > Local source > pull to refresh.' -ForegroundColor Yellow
& $adb shell ls "'$chapterDir'"
