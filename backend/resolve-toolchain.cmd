@echo off
rem ============================================================================
rem  CampusTrade toolchain resolver (JDK 21 + Maven)
rem
rem  Resolution order (no hard-coded drive letters anywhere):
rem    1. environment variables  JAVA_HOME / MAVEN_HOME / M2_HOME
rem    2. the system PATH         java.exe / mvn.cmd
rem    3. explicit error message with what to install / how to configure
rem
rem  A git-ignored, machine-local file .env.tools in the project root is loaded
rem  first, purely to persist these variables on this machine. Real environment
rem  variables always win over the file.
rem
rem  Usage:  call resolve-toolchain.cmd [quiet]
rem          On success it leaves JAVA_HOME, JAVA_EXE and MVN_CMD set for the
rem          caller and returns 0. On failure it prints guidance and returns 1.
rem
rem  NOTE: keep this file ASCII-only. cmd.exe parses .bat/.cmd with the console
rem  codepage (GBK/936 here) while the file bytes are UTF-8, so non-ASCII text
rem  corrupts the parser (swallowed quotes, shifted lines). Chinese messages
rem  belong in the .ps1 launchers, which are UTF-8 with BOM.
rem ============================================================================

set "QUIET=%~1"
set "TOOLS_FILE=%~dp0..\.env.tools"

rem ---- 0. load the machine-local toolchain file (never overrides real vars) ----
if not exist "%TOOLS_FILE%" goto :tools_loaded
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%TOOLS_FILE%") do (
    if not "%%~a"=="" if not "%%~b"=="" if not defined %%~a set "%%a=%%b"
)
:tools_loaded

rem ---- 1. resolve a usable java.exe (JAVA_HOME first, then PATH) ----
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%i in (java.exe) do set "JAVA_EXE=%%~$PATH:i"

if defined JAVA_EXE goto :java_found

echo [ERROR] JDK 21 not found: neither JAVA_HOME nor PATH provides java.exe.
echo         How to fix (any one of these):
echo           1) install OpenJDK 21 LTS, or
echo           2) set the environment variable JAVA_HOME to the JDK directory
echo              (the one that contains bin\java.exe) and reopen the terminal, or
echo           3) create .env.tools in the project root (git-ignored, ASCII) with:
echo                  JAVA_HOME=C:\path\to\jdk-21
exit /b 1

:java_found
rem ---- 1b. Maven itself needs JAVA_HOME; derive it when only PATH provided java ----
set "JAVA_BIN_DIR="
for %%i in ("%JAVA_EXE%") do set "JAVA_BIN_DIR=%%~dpi"
if defined JAVA_BIN_DIR set "JAVA_BIN_DIR=%JAVA_BIN_DIR:~0,-1%"
set "JDK_ROOT="
if defined JAVA_BIN_DIR for %%i in ("%JAVA_BIN_DIR%") do set "JDK_ROOT=%%~dpi"
if defined JDK_ROOT set "JDK_ROOT=%JDK_ROOT:~0,-1%"
if not defined JAVA_HOME if defined JDK_ROOT set "JAVA_HOME=%JDK_ROOT%"

rem ---- 1c. warn early when the resolved JDK is not 21 ----
rem  Building Java 21 sources with an older JDK fails with a confusing
rem  "invalid target release" error, so say it in plain words here instead.
set "JAVA_VER="
for /f "tokens=3" %%v in ('"%JAVA_EXE%" -version 2^>^&1') do if not defined JAVA_VER set "JAVA_VER=%%~v"
set "JAVA_MAJOR="
for /f "tokens=1 delims=." %%m in ("%JAVA_VER%") do set "JAVA_MAJOR=%%m"
if not "%JAVA_MAJOR%"=="21" echo [WARN] resolved JDK is not Java 21 (version: %JAVA_VER%) - this project requires Java 21.

rem ---- 2. resolve Maven (MAVEN_HOME / M2_HOME first, then PATH) ----
set "MVN_CMD="
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN_CMD if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" set "MVN_CMD=%M2_HOME%\bin\mvn.cmd"
if not defined MVN_CMD for %%i in (mvn.cmd) do set "MVN_CMD=%%~$PATH:i"

if defined MVN_CMD goto :maven_found

echo [ERROR] Maven not found: neither MAVEN_HOME/M2_HOME nor PATH provides mvn.cmd.
echo         How to fix (any one of these):
echo           1) install Apache Maven 3.9+ and add its bin directory to PATH, or
echo           2) set the environment variable MAVEN_HOME to the Maven directory, or
echo           3) create .env.tools in the project root (git-ignored, ASCII) with:
echo                  MAVEN_HOME=C:\path\to\maven
exit /b 1

:maven_found
if /i "%QUIET%"=="quiet" exit /b 0
echo [*] JAVA_HOME : %JAVA_HOME%
echo [*] java      : %JAVA_EXE%
echo [*] Maven     : %MVN_CMD%
exit /b 0
