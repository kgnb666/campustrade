@echo off
rem ============================================================================
rem  CampusTrade Flutter frontend launcher.
rem  Usage: run-frontend.cmd run -d chrome      (or: run -d windows)
rem
rem  Toolchain resolution order (no hard-coded drive letters):
rem    1. environment variable FLUTTER_ROOT
rem    2. the system PATH
rem    3. explicit error message with what to install / how to configure
rem  The git-ignored, machine-local file .env.tools in the project root is loaded
rem  first to persist FLUTTER_ROOT on this machine.
rem
rem  NOTE: keep this file ASCII-only - cmd.exe parses .bat files with the
rem  console codepage and non-ASCII text can shift the parser's file offset.
rem ============================================================================
cd /d "%~dp0"

if not exist "pubspec.yaml" (
    echo [ERROR] pubspec.yaml not found - run this script from the frontend directory.
    pause
    exit /b 1
)

rem ---- 0. machine-local toolchain file (never overrides a real env var) ----
set "TOOLS_FILE=%~dp0..\.env.tools"
if not exist "%TOOLS_FILE%" goto :tools_loaded
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%TOOLS_FILE%") do (
    if not "%%~a"=="" if not "%%~b"=="" if not defined %%~a set "%%a=%%b"
)
:tools_loaded

rem ---- 1. resolve the flutter command (FLUTTER_ROOT first, then PATH) ----
set "FLUTTER_CMD="
if defined FLUTTER_ROOT if exist "%FLUTTER_ROOT%\bin\flutter.bat" set "FLUTTER_CMD=%FLUTTER_ROOT%\bin\flutter.bat"
if not defined FLUTTER_CMD for %%i in (flutter.bat) do set "FLUTTER_CMD=%%~$PATH:i"

if defined FLUTTER_CMD goto :flutter_found

echo [ERROR] Flutter SDK not found: neither FLUTTER_ROOT nor PATH provides flutter.bat.
echo         How to fix (any one of these):
echo           1) install Flutter 3.x and add its bin directory to PATH, or
echo           2) set the environment variable FLUTTER_ROOT to the Flutter SDK root
echo              (the directory that contains bin\flutter.bat), or
echo           3) create .env.tools in the project root (git-ignored, ASCII) with:
echo                  FLUTTER_ROOT=C:\path\to\flutter
pause
exit /b 1

:flutter_found
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
