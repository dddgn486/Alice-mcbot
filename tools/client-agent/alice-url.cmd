@echo off
rem ============================================================
rem  Alice cloud FORWARD URL (double-click me) - the FAST link
rem ------------------------------------------------------------
rem  WHAT : prints the https forward-domain URL together with the
rem         current token, and copies it to the clipboard.
rem         Verified ~1s (3/3) vs the SSH tunnel's cold 45s.
rem  FAST : pure gh API. Does NOT wake the codespace, does NOT
rem         open an SSH tunnel, does NOT need git or SSH keys.
rem  TOKEN: read from branch steward/entry, which the cloud's
rem         postStartCommand hook rewrites on every start.
rem  LIMIT: the settings page / model config / workspace picker
rem         are loopback-only in DSH (isLoopback check in source)
rem         - for those use alice-cloud.cmd (the tunnel one).
rem  WARN : if the entry's codespace differs from the one in
rem         %USERPROFILE%\.alice-client.json, the entry was
rem         overwritten by ANOTHER machine and this link points
rem         at that machine. The script prints a loud warning.
rem  USAGE: alice-url.cmd            print + copy
rem         alice-url.cmd -Open      also open the browser
rem         alice-url.cmd -Token     print the token only
rem         alice-url.cmd -Json      machine-readable output
rem         alice-url.cmd -Verify    cross-check the live token
rem                                  over ssh (+3-5s)
rem ============================================================
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-url.ps1" %*
echo.
echo [alice] Press any key to close.
pause >nul
