# Start the chatapp server on Windows (run in native Windows PowerShell, NOT WSL).
# No configuration: the server detects its own LAN address and broadcast, picks a
# random id, and uses the default ports. Just runs the jar.
#
# Usage (PowerShell):  .\scripts\start_server.ps1
param([string]$Jar)

# Locate the fat jar automatically (no flags needed): use -Jar if given, otherwise
# search the build output and the repo root relative to this script's own folder, so
# it works no matter the current directory.
if (-not $Jar) {
  $Jar = @(
    (Join-Path $PSScriptRoot '..\target\chatapp.jar'),
    (Join-Path $PSScriptRoot '..\chatapp.jar'),
    (Join-Path $PSScriptRoot 'chatapp.jar')
  ) | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Jar -or -not (Test-Path $Jar)) { Write-Error "Jar not found. Build it first: mvn -DskipTests package (produces target\chatapp.jar)"; exit 1 }

# Resolve a Java 21 without changing any environment: PATH first, then the Temurin JDKs.
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) {
  $java = (Get-ChildItem "$env:USERPROFILE\.jdks" -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
           Where-Object { $_.FullName -match '21' } | Select-Object -First 1).FullName
}
if (-not $java) { Write-Error "No Java 21 found (install it, or put java on PATH)"; exit 1 }

& $java -jar $Jar server
