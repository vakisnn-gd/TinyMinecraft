@echo off
cd /d "%~dp0"

if not exist out mkdir out

javac -cp "lib/*" -d out *.java
if errorlevel 1 (
    echo Compilation failed.
    pause
    exit /b 1
)

java -cp "out;lib/*" TinyMinecraft
