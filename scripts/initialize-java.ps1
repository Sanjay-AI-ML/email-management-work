# Shared version of Member 2's JDK discovery for build, check, app, and demo launchers.
if (-not (Get-Command javac -CommandType Application -ErrorAction SilentlyContinue)) {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' +
        [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + $env:Path
}
$maildeskJdkCandidates = @(
    $env:JAVA_HOME
    (Join-Path $env:LOCALAPPDATA 'Programs/Eclipse Adoptium/jdk-17')
)
foreach ($maildeskJdk in $maildeskJdkCandidates) {
    if ($maildeskJdk -and (Test-Path -LiteralPath (Join-Path $maildeskJdk 'bin/javac.exe'))) {
        $env:JAVA_HOME = $maildeskJdk
        $env:Path = (Join-Path $maildeskJdk 'bin') + ';' + $env:Path
        break
    }
}
if (-not (Get-Command javac -CommandType Application -ErrorAction SilentlyContinue)) {
    throw 'Install JDK 17 or newer and set JAVA_HOME to its installation folder.'
}
