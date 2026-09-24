@echo off
rem Wrapper so this can be called from ANY current directory (resolves its own folder via %~dp0).
rem   codespace-tunnel.cmd              start tunnel + print loopback link
rem   codespace-tunnel.cmd -Open        also open the browser
rem   codespace-tunnel.cmd -Stop        stop the forward
rem   codespace-tunnel.cmd -LocalPort 3183
setlocal
set "SCRIPT=%~dp0codespace-tunnel.ps1"
if not exist "%SCRIPT%" ( echo [x] script not found: "%SCRIPT%" & exit /b 2 )
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%
