@echo off
cd /d "%~dp0"

if not exist out\TinyCraftServer.class (
    echo Compiled server files were not found.
    echo Trying to compile TinyCraft...

    if not exist out mkdir out
    javac -encoding UTF-8 --release 8 -cp "lib/*" -d out *.java
    if errorlevel 1 (
        echo.
        echo Compilation failed. Install Java JDK 8 or newer and try again.
        pause
        exit /b 1
    )
)

set "JAVA_FLAGS=-Dfile.encoding=UTF-8"

java %JAVA_FLAGS% -cp "out;lib/*" TinyCraftServer
if errorlevel 1 (
    echo.
    echo TinyCraftServer stopped with an error.
    pause
    exit /b 1
)
