@echo off
rem ============================================================
rem  Alice cloud FORWARD URL (double-click me) - the FAST link
rem ------------------------------------------------------------
rem  WHAT : prints the https forward-domain URL together with the
rem         current token, copies it to the clipboard, and then
rem         ACTUALLY CHECKS it (fetches the URL and confirms the
rem         body is the DSH app, not an auth page).
rem         Verified ~1s (3/3) vs the SSH tunnel's cold 45s.
rem  WHY CHECK: a bare HTTP status is a liar here. With a PRIVATE
rem         port, GitHub's own sign-in page also comes back as 200,
rem         and DSH's 401 page is a 200 HTML too - only the body
rem         tells them apart. Worse, private ports funnel through a
rem         CROSS-SITE sign-in (github.dev -> this host) while DSH's
rem         session cookie is SameSite=Strict, so the browser never
rem         sends it back => "authentication required" forever.
rem         => the port must be PUBLIC:
rem            gh codespace ports visibility 3081:public -c <name>
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
rem  USAGE: alice-url.cmd            print + copy + check
rem         alice-url.cmd -Open      also open the browser
rem         alice-url.cmd -Token     print the token only
rem         alice-url.cmd -Json      machine-readable output
rem         alice-url.cmd -Verify    cross-check the live token
rem                                  over ssh (+3-5s)
rem         alice-url.cmd -NoCheck   skip the reachability check
rem ============================================================
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%alice-url.ps1" %*
echo.
echo [alice] Press any key to close.
pause >nul
