@echo off
rem ============================================================================
rem  CampusTrade launcher - ASCII shim only, all logic lives in start.ps1.
rem
rem  Why this file is ASCII-only:
rem  cmd.exe parses .bat files with the console codepage (GBK/936 on this
rem  machine) even when the file itself is UTF-8. Chinese text in a .bat file
rem  therefore corrupts the parser - quotes get swallowed and lines get shifted.
rem  That is exactly what used to make the backend silently fail to start.
rem  PowerShell reads start.ps1 as UTF-8 (the file carries a BOM), so all
rem  Chinese messages and all launcher logic belong there.
rem
rem  Usage: double-click this file, or run "start.bat" from a terminal.
rem ============================================================================
chcp 65001 >nul

if not exist "%~dp0start.ps1" (
    echo [ERROR] start.ps1 not found next to this launcher.
    echo         Expected: %~dp0start.ps1
    echo.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
pause
exit /b %EXIT_CODE%
