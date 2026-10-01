@echo off
rem ============================================================
rem  Alice client-steward agent (Windows launcher)
rem ------------------------------------------------------------
rem  client-agent.cmd                  wake the agent: read the mailbox and act
rem  client-agent.cmd "task text"      wake it with a specific task
rem  client-agent.cmd -Doctor          diagnose this machine + the cloud channel (read-only, no LLM cost)
rem  client-agent.cmd -Doctor -Repair  same, and fix what is safely fixable (idempotent)
rem  client-agent.cmd -SelfTest        end-to-end gh round-trip probe (no LLM cost)
rem  client-agent.cmd -Install         import preset / write config / set gh credentials
rem  client-agent.cmd -PullOutbox      pull cloud ~/outbox back to this PC
rem  client-agent.cmd -Headless [mode]  run the headless battery ON THIS PC (core|full|single:<step>; no LLM cost)
rem  client-agent.cmd -Report           send the newest battery verdict to the cloud mailbox (no LLM cost)
rem  client-agent.cmd -Web              start the DSH web UI in the FOREGROUND (Ctrl+C stops it)
rem  client-agent.cmd -WebStop          stop a DSH web UI that is still listening on the port
rem  client-agent.cmd -Cloud            wake the CLOUD codespace, fetch the fresh entry URL (git branch), open it
rem  set DSH_VERSION=0.1.5-rc.3       pick the npx fallback version (rc.1/rc.2 are BROKEN)
rem ============================================================
setlocal
set "HERE=%~dp0"
if /I "%~1"=="-Install"    goto install
if /I "%~1"=="-SelfTest"   goto selftest
if /I "%~1"=="-PullOutbox" goto pulloutbox
if /I "%~1"=="-Doctor"     goto doctor
if /I "%~1"=="-Headless"   goto headless
if /I "%~1"=="-Report"     goto report
if /I "%~1"=="-Web"        goto web
if /I "%~1"=="-WebStop"    goto webstop
if /I "%~1"=="-Cloud"      goto cloud
set "TASK=%~1"
if "%TASK%"=="" set "TASK=Read the cloud mailbox /home/vscode/bus/to-win (skip file names already listed in .done), do what the newest request asks, then write a receipt to /home/vscode/bus/to-cloud. Do not upload any logs or screenshots unless the request names them."
rem refresh PATH: winget/npm installs are only visible in NEW terminals
rem refresh PATH (winget/npm installs are only visible in NEW terminals); cmd-native, no powershell:
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do call set "SYSPATH=%%b"
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do call set "USRPATH=%%b"
if defined SYSPATH set "PATH=%SYSPATH%"
if defined USRPATH set "PATH=%PATH%;%USRPATH%"
rem local proxy: gh (Go) only honors HTTP(S)_PROXY, NOT the Windows system proxy.
rem Refresh it from the registry, same idea as the PATH refresh above.
for /f "tokens=2*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable 2^>nul') do set "PXON=%%b"
for /f "tokens=2*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer 2^>nul') do set "PXSRV=%%b"
if "%PXON%"=="0x1" if defined PXSRV if not defined HTTPS_PROXY set "HTTPS_PROXY=http://%PXSRV%"
if defined HTTPS_PROXY if not defined HTTP_PROXY set "HTTP_PROXY=%HTTPS_PROXY%"
if "%DSH_VERSION%"=="" set "DSH_VERSION=0.1.5-rc.3"
where dsh >nul 2>nul
if not errorlevel 1 (
  dsh --profile headless "%TASK%"
  exit /b %ERRORLEVEL%
)
where npx >nul 2>nul
if not errorlevel 1 (
  echo [i] no global dsh -^> npx @deepseek-ai/dsh@%DSH_VERSION%
  npx --yes @deepseek-ai/dsh@%DSH_VERSION% --profile headless "%TASK%"
  exit /b %ERRORLEVEL%
)
echo [x] neither dsh nor npx found - install Node.js 22+ first.
exit /b 2

:doctor
rem -Doctor [-Repair] : extra flags are forwarded to the ps1 (it parses -Repair itself)
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-doctor.ps1" %2 %3 %4 %5
exit /b %ERRORLEVEL%

:selftest
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-agent-selftest.ps1" %2 %3 %4
exit /b %ERRORLEVEL%

:pulloutbox
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-info-watch.ps1" -PullOutbox %2 %3 %4
exit /b %ERRORLEVEL%

:install
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-agent-install.ps1" %2 %3 %4 %5
exit /b %ERRORLEVEL%

:headless
rem -Headless [core|full|single:<step>] : run the headless battery locally.
rem Why this lives here: after the main workflow moves to the cloud it CANNOT ssh into this PC
rem (the cloud is not on this device's virtual LAN) - so this box must be able to test itself.
set "HMODE=%~2"
if "%HMODE%"=="" set "HMODE=core"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%run-headless.ps1" -Mode "%HMODE%"
exit /b %ERRORLEVEL%

:report
rem -Report : write a receipt (verdict + step SUMMARY + red-vs-registered) and upload it to the cloud.
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-report.ps1" %2 %3 %4
exit /b %ERRORLEVEL%

:web
rem -Web : start the DSH web UI in the foreground. Ctrl+C in this window stops it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-web.ps1"
exit /b %ERRORLEVEL%

:webstop
rem -WebStop : kill whatever still listens on the DSH web port.
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-web.ps1" -Stop
exit /b %ERRORLEVEL%

:cloud
rem -Cloud : wake the cloud codespace and open its DSH web UI (pure API + git; no SSH key needed).
rem Why: on this PC the ~/.ssh file CONTENTS cannot be read (blocked at OS level) so gh codespace
rem ssh/cp do not work here. The cloud publishes its per-start token to the steward/entry branch.
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-cloud-entry.ps1" %2 %3 %4
exit /b %ERRORLEVEL%
