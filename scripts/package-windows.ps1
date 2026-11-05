# Run on a GitHub Windows runner AFTER setup-java (Java 21) and :sidecar:fatJar.
# Stages Tauri 1 resources. Does not build, download Java, or invoke Cargo.
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'This packaging script requires PowerShell 7 on Windows.' }
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$tauriRoot = Join-Path $RepositoryRoot 'studio/src-tauri'
$jar = Join-Path $RepositoryRoot 'sidecar/build/libs/parsehub-sidecar.jar'
if (-not (Test-Path -LiteralPath (Join-Path $tauriRoot 'tauri.conf.json') -PathType Leaf)) {
    throw "Tauri project missing: $tauriRoot"
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Missing sidecar/build/libs/parsehub-sidecar.jar. Run .\gradlew.bat :sidecar:fatJar first.'
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'JAVA_HOME must be set by actions/setup-java to Java 21.' }
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$release = Join-Path $JavaHome 'release'
if (-not (Test-Path -LiteralPath $release -PathType Leaf)) { throw 'JAVA_HOME has no release metadata.' }
$releaseText = [System.IO.File]::ReadAllText($release)
if ($releaseText -notmatch '(?m)^JAVA_VERSION="21(?:\.|"|\+)') { throw 'The bundled runtime must be Java 21.' }
foreach ($required in @('bin/java.exe', 'bin/server/jvm.dll', 'lib/modules', 'conf', 'legal')) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome $required))) { throw "Incomplete Java runtime: $required" }
}
$runtime = Join-Path $tauriRoot 'runtime'
$sidecar = Join-Path $tauriRoot 'sidecar'
# Only remove generated resource directories inside this verified Tauri project.
foreach ($destination in @($runtime, $sidecar)) {
    if (Test-Path -LiteralPath $destination) {
        $item = Get-Item -LiteralPath $destination -Force
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw "Refusing linked staging directory: $destination" }
        Remove-Item -LiteralPath $destination -Recurse -Force -Confirm:$false
    }
    New-Item -ItemType Directory -Path $destination | Out-Null
}
# Copy the complete setup-java JDK, including bin/lib/conf/legal (and jmods).
# Destination receives CONTENTS, so resolve_resource("runtime/bin/java.exe") works.
Get-ChildItem -LiteralPath $JavaHome -Force | Copy-Item -Destination $runtime -Recurse -Force
Copy-Item -LiteralPath $jar -Destination (Join-Path $sidecar 'parsehub-sidecar.jar')
Write-Host "Staged Java 21 at $runtime"
Write-Host "Staged sidecar at $sidecar"
Write-Host 'Next: pnpm --dir studio tauri build --bundles nsis'
