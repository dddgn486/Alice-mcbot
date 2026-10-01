@echo off
rem ============================================================
rem  Alice / DSH web UI - the PLAIN entry point (double-click me)
rem ------------------------------------------------------------
rem  START : double-click this file  (no arguments needed)
rem  STOP  : press Ctrl+C in the window that opens  (or run: alice-web.cmd -Stop)
rem  WHY --no-open : opening a browser automatically has caused trouble; the
rem                  one-time address (with ?token=...) is printed instead -
rem                  copy that whole line into your browser.
rem  NEEDS : Node.js 22+ and a global dsh
rem          (npm i -g @deepseek-ai/dsh@0.1.5-rc.3)
rem ============================================================
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-web.ps1" %1 %2
echo.
echo [alice] window will stay open so you can read any error above.
pause
