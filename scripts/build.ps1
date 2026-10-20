[CmdletBinding()]
param([switch]$SkipTests)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $mavenArguments = @('--batch-mode', '--no-transfer-progress', 'package')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    & (Join-Path $projectRoot 'mvnw.cmd') @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)." }
    Write-Host "Application: $(Join-Path $projectRoot 'target/maildesk.jar')"
} finally { Pop-Location }
