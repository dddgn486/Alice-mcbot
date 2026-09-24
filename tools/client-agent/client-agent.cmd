@echo off
rem ============================================================
rem  Alice client-steward agent (Windows launcher)
rem ------------------------------------------------------------
rem  client-agent.cmd                  wake the agent: read the mailbox and act
rem  client-agent.cmd "task text"      wake it with a specific task
rem  client-agent.cmd -SelfTest        check this machine + the cloud channel (no LLM cost)
rem  client-agent.cmd -Install         import preset / write config / set gh credentials
rem  client-agent.cmd -PullOutbox      pull cloud ~/outbox back to this PC
rem  set DSH_VERSION=0.1.5-rc.3       pick the npx fallback version (default 0.1.5-rc.2)
rem ============================================================
setlocal
set "HERE=%~dp0"
if /I "%~1"=="-Install"    goto install
if /I "%~1"=="-SelfTest"   goto selftest
if /I "%~1"=="-PullOutbox" goto pulloutbox
set "TASK=%~1"
if "%TASK%"=="" set "TASK=Read the cloud mailbox /home/vscode/bus/to-win (skip file names already listed in .done), do what the newest request asks, then write a receipt to /home/vscode/bus/to-cloud. Do not upload any logs or screenshots unless the request names them."
rem refresh PATH: winget/npm installs are only visible in NEW terminals
for /f "usebackq tokens=*" %%i in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')"`) do set "PATH=%%i"
if "%DSH_VERSION%"=="" set "DSH_VERSION=0.1.5-rc.2"
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

:selftest
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-agent-selftest.ps1" %2 %3 %4
exit /b %ERRORLEVEL%

:pulloutbox
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-info-watch.ps1" -PullOutbox %2 %3 %4
exit /b %ERRORLEVEL%

:install
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-agent-install.ps1" %2 %3 %4 %5
exit /b %ERRORLEVEL%
