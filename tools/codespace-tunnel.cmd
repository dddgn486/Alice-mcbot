@echo off
rem 从**任何当前目录**都能调用的包装：内部用 %~dp0 解析脚本自身所在目录。
rem   codespace-tunnel.cmd            （挂隧道 + 打印链接）
rem   codespace-tunnel.cmd -Open      （挂好后直接开浏览器）
rem   codespace-tunnel.cmd -Stop      （停掉转发）
rem   codespace-tunnel.cmd -LocalPort 3183
setlocal
set "SCRIPT=%~dp0codespace-tunnel.ps1"
if not exist "%SCRIPT%" ( echo [x] not found: "%SCRIPT%" & exit /b 2 )
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%
