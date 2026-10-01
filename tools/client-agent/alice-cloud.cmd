@echo off
rem ============================================================
rem  Alice cloud entry (double-click me) - open the CLOUD DSH UI
rem ------------------------------------------------------------
rem  START : double-click. It wakes the codespace, opens a
rem          loopback port-forward (127.0.0.1:3181) and opens the
rem          browser at the loopback URL - which is REQUIRED for
rem          the settings page / model config / workspace picker.
rem  STOP  : close this window (or Ctrl+C). The window IS the tunnel.
rem  NEEDS : gh (authenticated) + Node/DSH not required here.
rem  WHY NOT the https forward domain: browser hostname must be
rem          127.0.0.1, else DSH uses in-memory settings (see
rem          docs/CLOUD_MIGRATION.md 9-24, source-level).
rem ============================================================
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-cloud.ps1" %1 %2
echo.
echo [alice] tunnel window ended. Press any key to close.
pause >nul
