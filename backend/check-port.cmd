@echo off
rem ============================================================================
rem  Backend port ownership check for the CampusTrade backend.
rem
rem  Usage: check-port.cmd [port]
rem    port - optional. Defaults to BACKEND_PORT in the project root .env
rem           (the single place the port is configured), and to 8081 when
rem           that key is missing. An explicit argument always wins.
rem
rem  NOTE: keep this file ASCII-only - cmd.exe parses .bat files with the
rem  console codepage and non-ASCII text can shift the parser's file offset,
rem  which corrupts the following lines.
rem  Exit codes: 0 = port free, 1 = held by another program, 2 = held by us
rem ============================================================================
setlocal

rem ---- resolve the port: argument -> .env BACKEND_PORT -> 8081 ----
set "PORT=%~1"
if not defined PORT (
    set "ENV_FILE=%~dp0..\.env"
    if exist "%ENV_FILE%" for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do if /i "%%~a"=="BACKEND_PORT" set "PORT=%%~b"
)
if not defined PORT set "PORT=8081"

rem A non-numeric port would make the PowerShell lookups below fail silently and
rem report "port free" for a port that is in fact taken - fall back instead.
echo %PORT%| findstr /r /c:"^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo [WARN] Invalid port "%PORT%" - falling back to 8081.
    set "PORT=8081"
)

set "PORT_PID="
for /f "tokens=*" %%p in ('powershell -NoProfile -Command "(Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess)"') do set "PORT_PID=%%p"
if not defined PORT_PID (
    endlocal & exit /b 0
)

set "PORT_INFO=unknown"
for /f "usebackq tokens=*" %%p in (`powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + %PORT_PID%); $c = $p.CommandLine; if ($c -like '*com.campustrade.CampusTradeApplication*') { 'ours' } else { $m = [regex]::Match($c, '([\w.]+Application)'); $cls = if ($m.Success) { $m.Groups[1].Value } else { 'unknown' }; $par = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $p.ParentProcessId) -ErrorAction SilentlyContinue; $dir = ''; if ($par -and $par.CommandLine) { $d = [regex]::Match($par.CommandLine, 'multiModuleProjectDirectory=(.+?)[\\/]backend'); if ($d.Success) { $dir = $d.Groups[1].Value } }; if ($dir) { $cls + ' from ' + $dir } else { $cls } }"`) do set "PORT_INFO=%%p"

if "%PORT_INFO%"=="ours" (
    echo [INFO] Port %PORT% is already served by the CampusTrade backend.
    endlocal & exit /b 2
)

echo [WARN] Port %PORT% is held by another program: %PORT_INFO% (PID %PORT_PID%).
echo        Stop that program first, then start the CampusTrade backend again.
endlocal & exit /b 1
