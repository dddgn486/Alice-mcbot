@echo off
rem Client test data AUTO-UPLOAD (logs / screenshots / crash reports -> cloud codespace).
rem   client-info-watch.cmd              run in foreground (close window to stop)
rem   client-info-watch.cmd -Once        push one round then exit
rem   client-info-watch.cmd -Install     register logon autostart (recommended)
rem   client-info-watch.cmd -Uninstall   remove the logon task
rem   client-info-watch.cmd -Stop        stop the running watcher
rem   client-info-watch.cmd -PullOutbox  pull cloud ~/outbox back to this PC
setlocal
set "SCRIPT=%~dp0client-info-watch.ps1"
if not exist "%SCRIPT%" ( echo [x] script not found: "%SCRIPT%" & exit /b 2 )
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%
