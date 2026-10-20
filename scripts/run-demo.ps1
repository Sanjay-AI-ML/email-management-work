[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'build.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$classesDirectory = Join-Path $projectRoot 'build/classes'
$javaRuntime = Get-Command java -CommandType Application -ErrorAction Stop

& $javaRuntime.Source -cp $classesDirectory ems.demo.DemoApp
if ($LASTEXITCODE -ne 0) {
    throw "The demo exited with code $LASTEXITCODE."
}
