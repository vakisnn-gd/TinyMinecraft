@echo off
cd /d "%~dp0"

if not exist out\TinyMinecraft.class (
    echo Compiled game files were not found.
    echo Trying to compile TinyMinecraft...

    if not exist out mkdir out
    javac -cp "lib/*" -d out *.java
    if errorlevel 1 (
        echo.
        echo Compilation failed. Install Java JDK 17 or newer and try again.
        pause
        exit /b 1
    )
)

java -cp "out;lib/*" TinyMinecraft
if errorlevel 1 (
    echo.
    echo TinyMinecraft stopped with an error.
    pause
    exit /b 1
)
