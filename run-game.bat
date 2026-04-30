@echo off
cd /d "%~dp0"

if not exist out\TinyCraft.class (
    echo Compiled game files were not found.
    echo Trying to compile TinyCraft...

    if not exist out mkdir out
    javac -encoding UTF-8 -cp "lib/*" -d out *.java
    if errorlevel 1 (
        echo.
        echo Compilation failed. Install Java JDK 17 or newer and try again.
        pause
        exit /b 1
    )
)

java -cp "out;lib/*" TinyCraft
if errorlevel 1 (
    echo.
    echo TinyCraft stopped with an error.
    pause
    exit /b 1
)
