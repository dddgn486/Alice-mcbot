@echo off
rem Wake the LOCAL Windows client-steward agent (DSH headless one-shot) and exit.
rem   client-agent.cmd                      default task: read the mailbox and act
rem   client-agent.cmd "??????????????"
rem   client-agent.cmd -Install              install/refresh the Windows-side DSH + preset
rem   client-agent.cmd -SelfTest             check this machine + the cloud channel (no LLM cost)
rem   client-agent.cmd -PullOutbox           pull cloud ~/outbox back to this PC
setlocal
set "HERE=%~dp0"
if /I "%~1"=="-Install" goto install
if /I "%~1"=="-SelfTest" goto selftest
if /I "%~1"=="-PullOutbox" goto pulloutbox
set "TASK=%~1"
if "%TASK%"=="" set "TASK=??? ~/bus/to-win??? .done ????????????????? ~/bus/to-cloud"
rem refresh PATH (winget/npm installs are only visible in NEW terminals)
for /f "usebackq tokens=*" %%i in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')"`) do set "PATH=%%i"
where dsh >nul 2>nul
if errorlevel 1 (
  echo [x] dsh not found. Run: client-agent.cmd -Install   ^(needs node, already present^)
  exit /b 2
)
dsh --profile headless "%TASK%"
exit /b %ERRORLEVEL%

:install
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-agent-install.ps1" %2 %3 %4 %5
exit /b %ERRORLEVEL%

:selftest
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-agent-selftest.ps1" %2 %3 %4
exit /b %ERRORLEVEL%

:pulloutbox
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%client-info-watch.ps1" -PullOutbox %2 %3 %4
exit /b %ERRORLEVEL%
