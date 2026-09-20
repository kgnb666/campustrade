@echo off
rem ============================================================================
rem  CampusTrade stopper - ASCII shim only, all logic lives in stop.ps1.
rem
rem  Keep this file ASCII-only. Reason: cmd.exe parses .bat files with the
rem  console codepage (GBK/936 here) while the file bytes are UTF-8, so Chinese
rem  text corrupts the parser (swallowed quotes, shifted lines). See the header
rem  of start.bat for the full explanation.
rem
rem  Usage: double-click this file, or run "stop.bat" from a terminal.
rem ============================================================================
chcp 65001 >nul

if not exist "%~dp0stop.ps1" (
    echo [ERROR] stop.ps1 not found next to this launcher.
    echo         Expected: %~dp0stop.ps1
    echo.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
pause
exit /b %EXIT_CODE%
