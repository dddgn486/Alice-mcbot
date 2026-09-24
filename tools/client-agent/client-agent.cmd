@echo off
rem Wake the LOCAL Windows client-steward agent (DSH headless one-shot) and exit.
rem   client-agent.cmd                      default task: read the mailbox and act
rem   client-agent.cmd "??????????????"
rem   client-agent.cmd -Install              install/refresh the Windows-side DSH + preset
setlocal
set "HERE=%~dp0"
if /I "%~1"=="-Install" goto install
set "TASK=%~1"
if "%TASK%"=="" set "TASK=??? ~/bus/to-win??? .done ????????????????? ~/bus/to-cloud"
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
