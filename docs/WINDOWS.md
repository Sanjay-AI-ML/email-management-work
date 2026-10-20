# Windows package

The portable application contains `Maildesk.exe`, the application JAR, and a
bundled Java runtime. Keep these files together; the EXE is not a standalone file
or an installer. Extract `Maildesk-Windows-x64.zip` before opening the EXE.

## Build

Use Windows x64 and an x64 JDK with `jpackage`, `jlink`, and `jdeps`, such as
[Eclipse Temurin 17](https://adoptium.net/temurin/releases/).

```powershell
.\scripts\package-windows.ps1 -JdkPath 'C:\path\to\temurin-jdk-17'
```

The script builds and tests the JAR, creates a trimmed runtime with TLS and mail
charset support, and writes the application and ZIP under `package/`. Existing
packages are preserved; pass another `-OutputDirectory` when building again.
Use `-NoBuild` to package an already-built JAR.

## Local configuration

Copy `config/app.properties.example` to `config/app.properties` inside the
Maildesk folder and enter the MySQL login. Save a Google Desktop OAuth JSON as
`config/credentials.json` beside it. See [setup](SETUP.md) for Google configuration.
The launcher finds this folder even when started from a shortcut or another
working directory. MySQL is a separate service; the package bundles Java only.

For a personal build, add `-UseLocalConfig`. The script first archives a clean
distribution and then copies the project's local configuration into the unpacked
application. It protects copied tokens with an owner-only Windows ACL. Those
personal files are excluded from the shareable ZIP.

The `package/` output is ignored by Git. Runtime license notices are included in
`runtime/legal`. The GitHub Windows workflow also publishes the ZIP as a build artifact.
