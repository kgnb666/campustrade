@echo off
rem ============================================================================
rem  CampusTrade Backend launcher (Spring Boot 3 + JDK 21)
rem  NOTE: keep this file ASCII-only. cmd.exe reads .bat files with the console
rem  codepage; non-ASCII text can merge lines and break the script.
rem ============================================================================
rem Java 21 writes console output as UTF-8; without this the Chinese log lines
rem render as mojibake in a GBK (936) console.
chcp 65001 >nul
cd /d "%~dp0"

echo ==============================================================================
echo        CampusTrade Backend Service (Spring Boot 3 + JDK 21)
echo ==============================================================================
echo.

rem ---- 1. resolve a usable JDK 21 ----
if not exist "%JAVA_HOME%\bin\java.exe" (
    if exist "D:\yp3\.tools\jdk21\bin\java.exe" set "JAVA_HOME=D:\yp3\.tools\jdk21"
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 21 not found.
    echo         Install JDK 21, or set JAVA_HOME to a valid JDK directory, then retry.
    echo.
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- 2. resolve Maven ----
set "MVN_CMD="
if exist "D:\yp3\.tools\maven\bin\mvn.cmd" set "MVN_CMD=D:\yp3\.tools\maven\bin\mvn.cmd"
if not defined MVN_CMD for %%i in (mvn.cmd) do set "MVN_CMD=%%~$PATH:i"
if not defined MVN_CMD (
    echo [ERROR] Maven not found.
    echo         Install Maven, or make sure D:\yp3\.tools\maven\bin\mvn.cmd exists, then retry.
    echo.
    pause
    exit /b 1
)

echo [*] JDK   : %JAVA_HOME%
echo [*] Maven : %MVN_CMD%
echo [*] Port  : 8080 (context path /api)
echo [*] Starting Spring Boot - first run needs to resolve dependencies and run
echo     Flyway migrations, please wait...
echo.
echo ------------------------------------------------------------------------------

call "%MVN_CMD%" spring-boot:run
set "EXIT_CODE=%ERRORLEVEL%"

echo ------------------------------------------------------------------------------
echo.
if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Backend failed to start. Exit code %EXIT_CODE% - see Maven output above.
) else (
    echo [INFO] Backend process exited.
)
echo.
pause
exit /b %EXIT_CODE%
