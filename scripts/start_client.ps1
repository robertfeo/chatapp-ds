# Start a chatapp client on Windows (native Windows PowerShell, NOT WSL).
# No configuration: the client discovers the leader on its own. Just runs the jar.
#
# Usage (PowerShell):  .\scripts\start_client.ps1
param([string]$Jar = "chatapp.jar")

if (-not (Test-Path $Jar)) { Write-Error "Jar not found: $Jar (copy chatapp.jar next to this script)"; exit 1 }

$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) {
  $java = (Get-ChildItem "$env:USERPROFILE\.jdks" -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
           Where-Object { $_.FullName -match '21' } | Select-Object -First 1).FullName
}
if (-not $java) { Write-Error "No Java 21 found (install it, or put java on PATH)"; exit 1 }

& $java -jar $Jar client
