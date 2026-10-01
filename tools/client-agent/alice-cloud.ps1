<#
.SYNOPSIS
  一键开**云端**界面（新设备用）—— 唤醒云端 → 挂回环转发 → 打印/打开带令牌的回环地址。

.DESCRIPTION
  ⭐ 为什么必须是**回环**入口（2026-10-02 查记录 + 源码级结论，`CLOUD_MIGRATION §9-24`）：
    `dsh-client-ui-settings/lib/client.js:1345` = `persistence = ctx.remote.$host.isLoopback ? "host" : "memory"`
    ⇒ 走 `https://<名>-3081.app.github.dev` 时 `isLoopback=false` ⇒ `persistence="memory"`
    ⇒ **设置页/模型配置/工作区选择永远打不开**（但对话能用）。
    `isLoopback` 只认 `localhost` / `[::1]` / `127.x.x.x`
    ⇒ 想用设置页，**浏览器地址必须是 `http://127.0.0.1:<本地端口>/`**。

  ⭐ 为什么不用 `gh codespace ssh -L`：那要读 `~/.ssh/id_ed25519`，而本机 **火绒**（HipsDaemon）
    挡着私钥内容读（实测元数据可读、内容读挂住/拒绝）⇒ `gh codespace ssh/cp` 在本机全废。
    而 `gh codespace ports forward` 走 **VS Code 隧道 API，不需要 SSH 密钥**（实测端口能起来）。

  做的事：① `gh api .../start` 唤醒（已 Available 就跳过）→ ② 挂 `gh codespace ports forward 3081:3181`
  → ③ 等本机 3181 真的在听 → ④ 从 git 分支 `steward/entry` 读**最新令牌**（云端 `postStartCommand` 写的）
  → ⑤ 打印并（默认）打开 `http://127.0.0.1:3181/?token=…`。
  **前台运行**：这个窗口就是转发的载体，**Ctrl+C 就是关**（或直接关窗口）。

.EXAMPLE
  alice-cloud.cmd              # 双击等价：开云端界面
  alice-cloud.cmd -NoOpen      # 只打印地址
#>
[CmdletBinding()]
param(
    [string]$Codespace = "",
    [string]$Repo = "",
    [int]$LocalPort = 3181,
    [int]$RemotePort = 3081,
    [switch]$NoOpen
)
$ErrorActionPreference = "Continue"
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
function Say($m) { Write-Host ("[cloud] " + $m) }
function Die($m,$c=1) { Write-Host ("[cloud] 错误：" + $m) -ForegroundColor Red; exit $c }

try {
    $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
    if ($reg -and ([int]$reg.ProxyEnable -eq 1) -and $reg.ProxyServer -and -not $env:HTTPS_PROXY) {
        $srv = [string]$reg.ProxyServer; if ($srv -notmatch '^https?://') { $srv = "http://$srv" }
        $env:HTTPS_PROXY = $srv; $env:HTTP_PROXY = $srv
    }
} catch { }

if (-not $Repo) {
    $c = $PSScriptRoot
    while ($c) { if (Test-Path (Join-Path $c "build.gradle")) { $Repo = $c; break }; $p = Split-Path $c -Parent; if ($p -eq $c) { break }; $c = $p }
}
if (-not $Codespace) {
    $cfgPath = Join-Path $HOME ".alice-client.json"
    if (Test-Path $cfgPath) { try { $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json; if ($cfg.codespace) { $Codespace = [string]$cfg.codespace } } catch { } }
}
if (-not $Codespace) { Die "不知道 codespace 名" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Die "没有 gh" }

# ① 唤醒
$state = ""
try { $state = (& gh api "/user/codespaces/$Codespace" --jq '.state' 2>&1 | Out-String).Trim() } catch { }
Say ("云端状态：" + $state)
if ($state -ne "Available") {
    Say "先唤醒（纯 API）…"
    & gh api -X POST "/user/codespaces/$Codespace/start" > $null 2>&1
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt 180) {
        Start-Sleep 6
        $state = (& gh api "/user/codespaces/$Codespace" --jq '.state' 2>&1 | Out-String).Trim()
        Say ("  state=" + $state)
        if ($state -eq "Available") { break }
    }
    if ($state -ne "Available") { Die "唤醒了 180 秒仍是 $state" 3 }
}

# ② 挂转发（前台子进程 = 这个窗口的生命）
if (Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue) {
    Say "本机 $LocalPort 已在监听（沿用现有转发）"
    $fwd = $null
} else {
    Say "挂回环转发：gh codespace ports forward $RemotePort`:$LocalPort -c $Codespace"
    $fwd = Start-Process -FilePath "gh" -ArgumentList @("codespace","ports","forward","$RemotePort`:$LocalPort","-c",$Codespace) -PassThru -NoNewWindow
    $ok = $false
    for ($i = 1; $i -le 20; $i++) {
        Start-Sleep 3
        if (Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue) { $ok = $true; Say ("  端口就绪（" + ($i*3) + " 秒）"); break }
        if ($fwd.HasExited) { Die ("转发进程退出了（exit=" + $fwd.ExitCode + "）⇒ 常见原因：火绒拦截 / gh 未登录") 4 }
    }
    if (-not $ok) { Die "等了 60 秒 $LocalPort 还是没在听" 4 }
}

# ③ 令牌（云端 postStartCommand 钩子每次启动写的分支）
$token = ""
for ($i = 1; $i -le 6; $i++) {
    Push-Location $Repo
    # ⚠️ 必须 **--force**：云端钩子用 --force 推这条分支（语义=只存最新一条），
    #    普通 fetch 会**拒绝**更新追踪引用（非快进）⇒ 永远读到**上一轮**旧令牌 ⇒ 打开就 401（静默失效）。
    try {
        & git fetch -q --force origin "refs/heads/steward/entry:refs/remotes/origin/steward/entry" 2>&1 | Out-Null
        $e = (& git show "origin/steward/entry:entry.txt" 2>&1 | Out-String)
    } catch { $e = "" }
    Pop-Location
    if ($e -match 'token=([A-Za-z0-9_\-]{8,})') { $token = $Matches[1]; break }
    Say ("  第 $i 轮还没读到令牌（云端钩子可能还在跑）⇒ 等 12 秒")
    Start-Sleep 12
}
if (-not $token) { Die "读不到令牌（分支 steward/entry 里没有 token=）" 5 }

$url = "http://127.0.0.1:$LocalPort/?token=$token"
Write-Host ""
Write-Host "================ 云端界面（回环入口，设置页可用）================" -ForegroundColor Cyan
Write-Host $url -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  ● 本窗口就是转发载体：**关掉窗口 / Ctrl+C 即断开**" -ForegroundColor DarkGray
Write-Host "  ● 令牌每次云端重启都会换 ⇒ 重跑本命令即可" -ForegroundColor DarkGray

if (-not $NoOpen) { Start-Process $url; Say "已打开浏览器" }

if ($fwd) {
    Say "转发运行中… （Ctrl+C 退出）"
    try { $fwd.WaitForExit() } catch { }
    Say "转发已结束"
} else {
    Say "沿用了已有转发 ⇒ 本窗口可以直接关掉"
}
exit 0
