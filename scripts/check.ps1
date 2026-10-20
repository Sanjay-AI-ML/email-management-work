[CmdletBinding()]
param([switch]$Render)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $mavenArguments = @('--batch-mode', '--no-transfer-progress', 'test')
    if ($Render) { $mavenArguments += '-Prender' }
    & (Join-Path $projectRoot 'mvnw.cmd') @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw "Checks failed (exit code $LASTEXITCODE)." }
} finally { Pop-Location }
