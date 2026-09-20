@echo off
rem ============================================================================
rem  Port 8080 ownership check for the CampusTrade backend.
rem  NOTE: keep this file ASCII-only - cmd.exe parses .bat files with the
rem  console codepage and non-ASCII text can shift the parser's file offset,
rem  which corrupts the following lines.
rem  Exit codes: 0 = port free, 1 = held by another program, 2 = held by us
rem ============================================================================
setlocal

set "PORT_PID="
for /f "tokens=*" %%p in ('powershell -NoProfile -Command "(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess)"') do set "PORT_PID=%%p"
if not defined PORT_PID (
    endlocal & exit /b 0
)

set "PORT_INFO=unknown"
for /f "usebackq tokens=*" %%p in (`powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + %PORT_PID%); $c = $p.CommandLine; if ($c -like '*com.campustrade.CampusTradeApplication*') { 'ours' } else { $m = [regex]::Match($c, '([\w.]+Application)'); $cls = if ($m.Success) { $m.Groups[1].Value } else { 'unknown' }; $par = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $p.ParentProcessId) -ErrorAction SilentlyContinue; $dir = ''; if ($par -and $par.CommandLine) { $d = [regex]::Match($par.CommandLine, 'multiModuleProjectDirectory=(.+?)[\\/]backend'); if ($d.Success) { $dir = $d.Groups[1].Value } }; if ($dir) { $cls + ' from ' + $dir } else { $cls } }"`) do set "PORT_INFO=%%p"

if "%PORT_INFO%"=="ours" (
    echo [INFO] Port 8080 is already served by the CampusTrade backend.
    endlocal & exit /b 2
)

echo [WARN] Port 8080 is held by another program: %PORT_INFO% (PID %PORT_PID%).
echo        Stop that program first, then start the CampusTrade backend again.
endlocal & exit /b 1
