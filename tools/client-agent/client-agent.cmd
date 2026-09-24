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
where node >nul 2>nul || ( echo [x] node missing - install Node.js 22+ first & exit /b 2 )
echo [1/3] installing DSH (pinned 0.1.5-rc.3)...
call npm i -g @deepseek-ai/dsh@0.1.5-rc.3 || exit /b 1
echo [2/3] installing presets into %USERPROFILE%\.dsh\.agent-presets\ ...
if not exist "%USERPROFILE%\.dsh\.agent-presets" mkdir "%USERPROFILE%\.dsh\.agent-presets"
xcopy /E /I /Y "%HERE%presets\alice-client-master" "%USERPROFILE%\.dsh\.agent-presets\alice-client-master" >nul || exit /b 1
echo [3/3] checking credentials + preset default...
if not exist "%USERPROFILE%\.dsh\.credentials.yaml" (
  echo   !! %USERPROFILE%\.dsh\.credentials.yaml ????????????????? API key??? git??
  echo      ?? DSH ????????? agent ???????
)
echo done. Try:  client-agent.cmd "?????????"
exit /b 0
