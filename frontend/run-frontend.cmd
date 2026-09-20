@echo off
rem ============================================================================
rem  CampusTrade Flutter frontend launcher.
rem  Usage: run-frontend.cmd run -d chrome      (or: run -d windows)
rem  NOTE: keep this file ASCII-only - cmd.exe parses .bat files with the
rem  console codepage and non-ASCII text can shift the parser's file offset.
rem ============================================================================
cd /d "%~dp0"

if not exist "pubspec.yaml" (
    echo [ERROR] pubspec.yaml not found - run this script from the frontend directory.
    pause
    exit /b 1
)

set "FLUTTER_CMD="
if exist "D:\flutter_sdk\flutter\bin\flutter.bat" set "FLUTTER_CMD=D:\flutter_sdk\flutter\bin\flutter.bat"
if not defined FLUTTER_CMD if exist "C:\flutter\bin\flutter.bat" set "FLUTTER_CMD=C:\flutter\bin\flutter.bat"
if not defined FLUTTER_CMD for %%i in (flutter.bat) do set "FLUTTER_CMD=%%~$PATH:i"
if not defined FLUTTER_CMD (
    echo [ERROR] Flutter SDK not found.
    echo         Install Flutter, add it to PATH, or fix the path in this script.
    echo.
    pause
    exit /b 1
)

echo [*] Flutter : %FLUTTER_CMD%
echo [*] Project : %CD%
echo [*] Command : flutter %*
echo.
echo ------------------------------------------------------------------------------

call "%FLUTTER_CMD%" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo ------------------------------------------------------------------------------
echo.
if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Flutter exited with code %EXIT_CODE% - see the output above.
) else (
    echo [INFO] Flutter process finished.
)
echo.
pause
exit /b %EXIT_CODE%
