[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command java -CommandType Application -ErrorAction SilentlyContinue)) {
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path","User") + ";" + [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + $env:Path
    if (-not (Get-Command java -CommandType Application -ErrorAction SilentlyContinue)) {
        $adoptiumJdk = "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17\bin"
        if (Test-Path $adoptiumJdk) {
            $env:Path = "$adoptiumJdk;$env:Path"
        }
    }
}

& (Join-Path $PSScriptRoot 'build.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$classesDirectory = Join-Path $projectRoot 'build/classes'
$javaRuntime = Get-Command java -CommandType Application -ErrorAction Stop

& $javaRuntime.Source -cp $classesDirectory ems.demo.DemoApp
if ($LASTEXITCODE -ne 0) {
    throw "The demo exited with code $LASTEXITCODE."
}
