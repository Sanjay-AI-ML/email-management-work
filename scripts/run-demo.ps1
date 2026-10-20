[CmdletBinding()]
param([switch]$NoBuild)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'initialize-java.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -SkipTests }
$applicationJar = Join-Path $projectRoot 'target/maildesk.jar'
if (-not (Test-Path -LiteralPath $applicationJar)) { throw 'Build first with .\scripts\build.ps1.' }
$javaRuntime = Get-Command java -CommandType Application -ErrorAction Stop
Push-Location -LiteralPath $projectRoot
try {
    & $javaRuntime.Source -cp $applicationJar ems.demo.DemoApp
    if ($LASTEXITCODE -ne 0) { throw "The demo exited with code $LASTEXITCODE." }
} finally { Pop-Location }
