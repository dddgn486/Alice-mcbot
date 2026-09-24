@echo off
rem ============================================================
rem  bus-watch: cloud mailbox -> automatic local wake-up
rem ------------------------------------------------------------
rem  bus-watch.cmd                 watch in foreground (every 300 s; Ctrl-C = stop)
rem  bus-watch.cmd -Once           one check only (no agent call when nothing is new)
rem  bus-watch.cmd -DryRun         report what it would do, never wake the agent
rem  bus-watch.cmd -Interval 60    custom interval, seconds
rem  bus-watch.cmd -Stop           stop the running watcher
rem  bus-watch.cmd -Install        register logon autostart (hidden window)
rem  bus-watch.cmd -Uninstall      remove that autostart task
rem  Cost: zero LLM calls while there are no new messages.
rem  Log: %TEMP%\bus-watch.log    State: %TEMP%\bus-watch.state.json
rem ============================================================
setlocal
set "HERE=%~dp0"
rem refresh PATH: winget/npm installs are only visible in NEW terminals (cmd-native, no powershell):
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do call set "SYSPATH=%%b"
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do call set "USRPATH=%%b"
if defined SYSPATH set "PATH=%SYSPATH%"
if defined USRPATH set "PATH=%PATH%;%USRPATH%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%bus-watch.ps1" %*
exit /b %ERRORLEVEL%
