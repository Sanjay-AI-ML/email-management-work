[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$classesDirectory = Join-Path $projectRoot 'build/classes'
$sourceDirectories = @(
    (Join-Path $projectRoot 'src/main/java'),
    (Join-Path $projectRoot 'src/demo/java')
)
$sourceFiles = @(
    foreach ($sourceDirectory in $sourceDirectories) {
        if (Test-Path -LiteralPath $sourceDirectory) {
            Get-ChildItem -LiteralPath $sourceDirectory -Filter '*.java' -File -Recurse |
                Sort-Object FullName |
                Select-Object -ExpandProperty FullName
        }
    }
)

if ($sourceFiles.Count -eq 0) {
    throw 'No Java source files were found in src/main/java or src/demo/java.'
}

$compiler = Get-Command javac -CommandType Application -ErrorAction Stop
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
& $compiler.Source --release 17 -encoding UTF-8 -d $classesDirectory @sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed (exit code $LASTEXITCODE)."
}

Write-Host "Compiled $($sourceFiles.Count) Java files to $classesDirectory"
