@echo off
rem ============================================================================
rem  CampusTrade Backend launcher (Spring Boot 3 + JDK 21)
rem  NOTE: keep this file ASCII-only. cmd.exe reads .bat files with the console
rem  codepage; non-ASCII text can merge lines and break the script.
rem
rem  Exit codes: 0 = Spring Boot ended normally, non-zero = toolchain missing or
rem              the application failed. External commands are always checked.
rem ============================================================================
rem Java 21 writes console output as UTF-8; without this the Chinese log lines
rem render as mojibake in a GBK (936) console.
chcp 65001 >nul
cd /d "%~dp0"

echo ==============================================================================
echo        CampusTrade Backend Service (Spring Boot 3 + JDK 21)
echo ==============================================================================
echo.

rem ---- 0a. resolve the toolchain: env vars -^> PATH -^> clear guidance ----
rem All path resolution (and the .env.tools machine-local file) lives in
rem resolve-toolchain.cmd so that it can also be run on its own to diagnose
rem a machine:  cd backend ^&^& resolve-toolchain.cmd
call "%~dp0resolve-toolchain.cmd"
if errorlevel 1 (
    echo.
    echo [ERROR] Toolchain check failed - see the messages above.
    pause
    exit /b 1
)
echo.

rem ---- 0b. load environment variables from the project root .env ----
rem The backend no longer ships any default JWT secret: JWT_SECRET must come from
rem the process environment. This block exports every "KEY=VALUE" line of the
rem project root .env (JWT_SECRET, SPRING_*, MINIO_*, DEEPSEEK_*, ...) so that a
rem plain double-click of this script works locally.
rem Parsing rules: skip blank lines and lines starting with '#' (eol=#), split at
rem the FIRST '=' (tokens=1,*), keep the raw value untouched.
set "ENV_FILE=%~dp0..\.env"
if not exist "%ENV_FILE%" goto :env_loaded
echo [*] Env     : %ENV_FILE%
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if not "%%~a"=="" if not "%%~b"=="" set "%%a=%%b"
)
goto :env_report

:env_loaded
echo [WARN] Env file not found: %ENV_FILE%
echo        Copy .env.example to .env and fill in the values (JWT_SECRET,
echo        SPRING_DATASOURCE_PASSWORD, MINIO_ROOT_PASSWORD, REDIS_PASSWORD, ...),
echo        or export the variables in your shell before starting the backend.
echo.

:env_report
rem Never echo credential values - only whether they are present.
if not defined JWT_SECRET (
    echo [WARN] JWT_SECRET is not set. The backend will refuse to start and print
    echo        a Chinese hint telling you how to generate one, for example:
    echo          openssl rand -hex 32
    echo.
) else (
    echo [*] JWT_SECRET is set ^(value is never printed^)
)
if not defined SPRING_DATASOURCE_PASSWORD (
    echo [WARN] SPRING_DATASOURCE_PASSWORD is not set - the backend will fail to
    echo        connect to PostgreSQL. Fill it in .env ^(value is never printed^).
)

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
