# Starts the mihon_test emulator for Bubble Zoom development.
#
# Run this in its OWN terminal window and leave it open. The Claude Code session must
# never start the emulator itself: background processes it owns are killed when the
# session ends or its context compacts, which would take the emulator down mid-phase.

$ErrorActionPreference = 'Stop'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' }
$emulator = Join-Path $sdk 'emulator\emulator.exe'

if (-not (Test-Path $emulator)) {
    Write-Error "Emulator not found at $emulator. Is ANDROID_HOME set correctly?"
}

# avdmanager creates AVDs under ANDROID_USER_HOME, but the emulator only searches
# ANDROID_AVD_HOME, ANDROID_SDK_HOME and ~/.android. Point it at the same place.
if (-not $env:ANDROID_AVD_HOME -and $env:ANDROID_USER_HOME) {
    $env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
}

Write-Host ''
Write-Host '  Starting AVD: mihon_test' -ForegroundColor Cyan
Write-Host '  KEEP THIS WINDOW OPEN. Closing it kills the emulator.' -ForegroundColor Yellow
Write-Host '  Press Ctrl+C here when you want to shut the emulator down.'
Write-Host ''

& $emulator -avd mihon_test -gpu host -no-snapshot-load
