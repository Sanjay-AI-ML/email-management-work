[CmdletBinding()]
param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$OutputDirectory,
    [switch]$NoBuild,
    [switch]$UseLocalConfig
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Build the Windows package on Windows with an x64 JDK 17 or newer.'
}
if (-not $JdkPath) {
    $packager = Get-Command jpackage -CommandType Application -ErrorAction SilentlyContinue
    if ($packager) { $JdkPath = Split-Path -Parent (Split-Path -Parent $packager.Source) }
}
if (-not $JdkPath) { throw 'Pass -JdkPath with an x64 JDK containing jpackage, jlink, and jdeps.' }
$JdkPath = (Resolve-Path -LiteralPath $JdkPath).Path
foreach ($tool in @('java', 'javac', 'jpackage', 'jlink', 'jdeps')) {
    if (-not (Test-Path -LiteralPath (Join-Path $JdkPath "bin/$tool.exe"))) {
        throw "The selected JDK is missing $tool.exe."
    }
}
$env:JAVA_HOME = $JdkPath
$env:Path = (Join-Path $JdkPath 'bin') + ';' + $env:Path
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
$applicationJar = Join-Path $projectRoot 'target/maildesk.jar'
if (-not (Test-Path -LiteralPath $applicationJar)) { throw 'Build the application first.' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'package' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$applicationDirectory = Join-Path $OutputDirectory 'Maildesk'
$archive = Join-Path $OutputDirectory 'Maildesk-Windows-x64.zip'
if ((Test-Path -LiteralPath $applicationDirectory) -or (Test-Path -LiteralPath $archive)) {
    throw 'The package already exists. Choose another -OutputDirectory to preserve existing local settings.'
}

$stage = Join-Path $projectRoot ('build/windows-package-' + [Guid]::NewGuid().ToString('N'))
$inputDirectory = Join-Path $stage 'input'
$runtimeDirectory = Join-Path $stage 'runtime'
New-Item -ItemType Directory -Path $inputDirectory, $OutputDirectory -Force | Out-Null
Copy-Item -LiteralPath $applicationJar -Destination (Join-Path $inputDirectory 'maildesk.jar')
$dependencyModules = & (Join-Path $JdkPath 'bin/jdeps.exe') --multi-release 17 --ignore-missing-deps --print-module-deps $applicationJar
if ($LASTEXITCODE -ne 0) { throw 'Could not determine Java runtime modules.' }
# Explicit providers retain TLS, international mail charsets, and locale support.
$modules = @((($dependencyModules -join '') -split ',') + @('jdk.crypto.ec', 'jdk.charsets', 'jdk.localedata')) |
    Sort-Object -Unique
& (Join-Path $JdkPath 'bin/jlink.exe') --add-modules ($modules -join ',') --output $runtimeDirectory --strip-debug --no-header-files --no-man-pages --compress=2
if ($LASTEXITCODE -ne 0) { throw 'Could not build the bundled Java runtime.' }

$packageArguments = @('--type', 'app-image', '--name', 'Maildesk', '--app-version', '1.0.0',
    '--vendor', 'Maildesk', '--description', 'Gmail client with MySQL persistence and spam review',
    '--input', $inputDirectory, '--main-jar', 'maildesk.jar', '--main-class', 'ems.App',
    '--runtime-image', $runtimeDirectory, '--dest', $OutputDirectory,
    '--java-options', '-Dmaildesk.home=$APPDIR/..')
& (Join-Path $JdkPath 'bin/jpackage.exe') @packageArguments
if ($LASTEXITCODE -ne 0) { throw 'Windows application packaging failed.' }

$configurationDirectory = Join-Path $applicationDirectory 'config'
$schemaDirectory = Join-Path $applicationDirectory 'sql'
New-Item -ItemType Directory -Path $configurationDirectory, $schemaDirectory -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'config/app.properties.example') -Destination $configurationDirectory
Copy-Item -LiteralPath (Join-Path $projectRoot 'sql/schema.sql') -Destination $schemaDirectory
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/SETUP.md') -Destination (Join-Path $applicationDirectory 'SETUP.md')
@'
MAILDESK FOR WINDOWS x64

Extract the entire ZIP, then open Maildesk.exe. Keep app/ and runtime/ beside it.
A separate Java installation is unnecessary. MySQL must be running separately.

SETUP
1. Copy config/app.properties.example to config/app.properties and set the MySQL login.
2. Save your Google Desktop OAuth JSON as config/credentials.json.
3. Open Maildesk.exe and click Connect Gmail. See SETUP.md for detailed instructions.

Imports are capped at 18 messages: up to 6 each from Inbox, Sent, and Spam.
The distribution ZIP contains no personal settings, OAuth credentials, or tokens.
User configuration is resolved beside Maildesk.exe, independently of the working directory.

The bundled Java runtime includes its notices and licenses under runtime/legal.
For Eclipse Temurin runtime sources, see https://adoptium.net/temurin/releases/.
'@ | Set-Content -LiteralPath (Join-Path $applicationDirectory 'README.txt') -Encoding UTF8

# Archive the clean distribution before adding any user-specific configuration.
Compress-Archive -LiteralPath $applicationDirectory -DestinationPath $archive -CompressionLevel Optimal
if ($UseLocalConfig) {
    foreach ($name in @('app.properties', 'credentials.json')) {
        $source = Join-Path $projectRoot "config/$name"
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $configurationDirectory }
    }
    $sourceTokens = Join-Path $projectRoot 'config/tokens'
    if (Test-Path -LiteralPath $sourceTokens) {
        $destinationTokens = Join-Path $configurationDirectory 'tokens'
        New-Item -ItemType Directory -Path $destinationTokens -Force | Out-Null
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls.exe $destinationTokens /inheritance:r /grant:r ('{0}:(OI)(CI)F' -f $identity) | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not protect the local token directory.' }
        Get-ChildItem -LiteralPath $sourceTokens -File | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $destinationTokens
        }
    }
}
Write-Host "Windows executable: $(Join-Path $applicationDirectory 'Maildesk.exe')"
Write-Host "Clean distribution: $archive"
