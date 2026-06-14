@echo off
setlocal
cd /d "%~dp0"

set "URL=https://github.com/robertfeo/chatapp-ds/releases/download/v1.0.0/chatapp.jar"
set "JAR=chatapp.jar"

java -version >nul 2>&1
if errorlevel 1 (
    echo Error: Java is not installed or not on PATH.
    echo Please install a Java 21 runtime: https://adoptium.net/
    exit /b 1
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "RAW=%%v"
    goto :gotver
)
:gotver
set "RAW=%RAW:"=%"
for /f "delims=. tokens=1" %%a in ("%RAW%") do set "MAJOR=%%a"
if "%MAJOR%"=="1" (
    for /f "delims=. tokens=2" %%b in ("%RAW%") do set "MAJOR=%%b"
)
if %MAJOR% LSS 21 (
    echo Error: Java 21 or newer is required. Found version %RAW%.
    echo Please install a Java 21 runtime: https://adoptium.net/
    exit /b 1
)

if not exist "%JAR%" (
    echo Downloading %JAR%...
    powershell -Command "Invoke-WebRequest -Uri '%URL%' -OutFile '%JAR%'"
    if errorlevel 1 (
        echo Download failed.
        exit /b 1
    )
)

java -jar "%JAR%" %*
