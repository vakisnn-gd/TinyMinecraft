@echo off
cd /d "%~dp0"

if not exist out mkdir out

javac -encoding UTF-8 --release 8 -cp "lib/*" -d out *.java
if errorlevel 1 (
    echo Compilation failed.
    pause
    exit /b 1
)

set "JAVA_FLAGS=-Dfile.encoding=UTF-8"

java %JAVA_FLAGS% -cp "out;lib/*" TinyCraft
