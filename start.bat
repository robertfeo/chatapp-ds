@echo off
rem One-command launcher: start.bat server | start.bat client
rem
rem Finds a runnable jar in this order:
rem   1. target\chatapp.jar   (a source checkout that was already built)
rem   2. chatapp.jar          (downloaded next to this script on an earlier run)
rem   3. build from source    (source checkout + Maven available)
rem   4. download the latest release jar
setlocal
cd /d "%~dp0"

set "URL=https://github.com/robertfeo/chatapp-ds/releases/latest/download/chatapp.jar"

if "%~1"=="" goto :usage
if /i "%~1"=="server" goto :checkjava
if /i "%~1"=="client" goto :checkjava
goto :usage

:checkjava
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

if exist "target\chatapp.jar" (
    set "JAR=target\chatapp.jar"
    goto :run
)
if exist "chatapp.jar" (
    set "JAR=chatapp.jar"
    goto :run
)
if not exist "pom.xml" goto :download
where mvn >nul 2>&1
if errorlevel 1 goto :download
echo Building chatapp.jar from source (mvn -DskipTests package)...
call mvn -q -DskipTests package
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)
set "JAR=target\chatapp.jar"
goto :run

:download
echo Downloading chatapp.jar...
powershell -Command "Invoke-WebRequest -Uri '%URL%' -OutFile 'chatapp.jar'"
if errorlevel 1 (
    echo Download failed.
    exit /b 1
)
set "JAR=chatapp.jar"
goto :run

:run
java -jar "%JAR%" %*
exit /b %errorlevel%

:usage
echo usage: start.bat ^<server^|client^>
exit /b 2
