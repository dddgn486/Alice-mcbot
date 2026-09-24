@echo off
rem ============================================================
rem  Alice client-steward agent - INTERACTIVE UI (keeps context)
rem ------------------------------------------------------------
rem  client-agent-ui.cmd            start the web UI on port 3081 (auto-opens browser)
rem  client-agent-ui.cmd 3082       use another port
rem  set DSH_VERSION=0.1.5-rc.3     pick the npx fallback version (default 0.1.5-rc.2)
rem  Default agent preset is alice-client-master (set by client-agent.cmd -Install);
rem  change it in the UI's preset picker if you want the plain coding agent instead.
rem ============================================================
setlocal
set "PORT=%~1"
if "%PORT%"=="" set "PORT=3081"
rem refresh PATH (winget/npm installs are only visible in NEW terminals); cmd-native, no powershell:
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do call set "SYSPATH=%%b"
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do call set "USRPATH=%%b"
if defined SYSPATH set "PATH=%SYSPATH%"
if defined USRPATH set "PATH=%PATH%;%USRPATH%"
if "%DSH_VERSION%"=="" set "DSH_VERSION=0.1.5-rc.2"
echo [i] DSH web UI  ->  http://127.0.0.1:%PORT%/   (preset default: alice-client-master)
echo [i] keep this window open; press Ctrl-C here to stop the UI.
where dsh >nul 2>nul
if not errorlevel 1 (
  dsh --profile web --port %PORT%
  exit /b %ERRORLEVEL%
)
where npx >nul 2>nul
if not errorlevel 1 (
  echo [i] no global dsh -^> npx @deepseek-ai/dsh@%DSH_VERSION%
  npx --yes @deepseek-ai/dsh@%DSH_VERSION% --profile web --port %PORT%
  exit /b %ERRORLEVEL%
)
echo [x] neither dsh nor npx found - install Node.js 22+ first.
exit /b 2
