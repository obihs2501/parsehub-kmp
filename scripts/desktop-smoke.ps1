# CI-only smoke against the already-built Tauri executable.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$release = Join-Path $root 'studio/src-tauri/target/release'
$exe = Join-Path $release 'parsehub-studio.exe'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Desktop executable missing.' }
foreach ($name in @('runtime', 'sidecar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $release $name))) {
        Copy-Item -LiteralPath (Join-Path $root "studio/src-tauri/$name") -Destination $release -Recurse
    }
}
$env:PARSEHUB_DESKTOP_SMOKE_TEST = '1'
$log = Join-Path $release 'desktop-smoke.log'
$process = $null
try {
    $process = Start-Process -FilePath $exe -PassThru -RedirectStandardError $log
    if (-not $process.WaitForExit(120000)) {
        & taskkill /PID $process.Id /T /F
        throw 'Desktop startup/shutdown smoke timed out after 120 seconds.'
    }
    if ($process.ExitCode -ne 0) { throw "Desktop smoke failed: $($process.ExitCode). See $log" }
    # A JVM launched by this application must not survive its parent.
    $orphan = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.ParentProcessId -eq $process.Id }
    if ($orphan) { throw 'Desktop exited with a Java child still running.' }
} finally {
    Remove-Item Env:PARSEHUB_DESKTOP_SMOKE_TEST -ErrorAction SilentlyContinue
}
