[CmdletBinding()]
param([switch]$Render, [switch]$MySql)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'initialize-java.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $mavenArguments = @('--batch-mode', '--no-transfer-progress', 'test')
    if ($Render) { $mavenArguments += '-Prender' }
    if ($MySql) { $mavenArguments += '-Dmaildesk.mysqlTest=true' }
    & (Join-Path $projectRoot 'mvnw.cmd') @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw "Checks failed (exit code $LASTEXITCODE)." }
} finally { Pop-Location }
