@echo off
rem Hytale Server Launcher
rem This script handles staged updates and starts the server with default arguments.
rem
rem CUSTOM JVM ARGUMENTS
rem --------------------
rem To customize JVM arguments (e.g., memory settings), create a file named
rem "jvm.options" in the same directory as this script. One argument per line.
rem Lines starting with # are comments.
rem
rem Example jvm.options:
rem   -Xms2G
rem   -Xmx4G
rem   -XX:+UseG1GC

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

:start
set APPLIED_UPDATE=0

rem Apply staged update if present (from script dir)
cd /d "%SCRIPT_DIR%"
if exist updater\staging\Server\HytaleServer.jar (
    echo [Launcher] Applying staged update...
    rem Only replace update files, preserve config/saves/mods
    copy /y updater\staging\Server\HytaleServer.jar Server\ >nul
    if exist updater\staging\Server\Licenses rmdir /s /q Server\Licenses 2>nul & xcopy /s /e /i /y updater\staging\Server\Licenses Server\Licenses >nul
    if exist updater\staging\Assets.zip copy /y updater\staging\Assets.zip .\ >nul
    if exist updater\staging\start.bat copy /y updater\staging\start.bat .\ >nul
    if exist updater\staging\start.sh copy /y updater\staging\start.sh .\ >nul
    rmdir /s /q updater\staging 2>nul
    set APPLIED_UPDATE=1
)

rem Run server from inside Server/ folder so config/backups/etc. are generated there
cd Server

rem Load custom JVM arguments from jvm.options if it exists (uses JVM's @-file syntax)
set JVM_OPTS=
if exist ..\jvm.options set JVM_OPTS=@..\jvm.options

rem Default server arguments
rem --assets: Assets.zip is in parent directory
rem --backup: Enable periodic backups like singleplayer
set DEFAULT_ARGS=--assets ../Assets.zip --backup --backup-dir backups --backup-frequency 30

rem Start server
java %JVM_OPTS% -jar HytaleServer.jar %DEFAULT_ARGS% %*
set EXIT_CODE=%ERRORLEVEL%

rem Return to script dir
cd /d "%SCRIPT_DIR%"

rem Handle exit code 8 (restart for update)
if %EXIT_CODE%==8 goto :start

rem Warn on crash after update (batch timing is limited, so warn on any post-update crash)
if %EXIT_CODE% NEQ 0 if %APPLIED_UPDATE%==1 (
    echo.
    echo [Launcher] ERROR: Server exited with code %EXIT_CODE% after update.
    echo [Launcher] This may indicate the update failed to start correctly.
    echo [Launcher]
    echo [Launcher] Your previous files are in the updater/backup/ folder.
    echo [Launcher] To rollback: delete Server/ and Assets.zip, then move from updater/backup/
    echo.
    pause
)

exit /b %EXIT_CODE%
