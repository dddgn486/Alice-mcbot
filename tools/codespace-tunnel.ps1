<#
.SYNOPSIS
  在**新设备（只用 PowerShell，不装 WSL）**上把云端 DSH 的"回环入口"挂起来，并打印可点链接。

.DESCRIPTION
  为什么必须走"回环"：DSH 客户端把设置镜像的持久化绑在页面 hostname 上
  （`dsh-client-ui-settings/lib/client.js:1345` `persistence = isLoopback ? "host" : "memory"`）
  ⇒ 用 `https://<名字>-3081.app.github.dev` 打开时，对话能用但**模型/插件配置永远打不开**。
  本脚本把云端 3081 转发到本机 127.0.0.1:<本地端口> ⇒ 浏览器看到回环 ⇒ 设置页可用。

  实测过的两个事实（决定本脚本的实现）：
   ① `gh codespace ports forward <远端>:<本地>` 顺序是**远端在前**，但它**绑在所有网卡**（`*:3182`）；
   ② `gh codespace ssh -c <名> -- -N -L <本地>:127.0.0.1:<远端>` 绑 **127.0.0.1**（更安全）⇒ 本脚本优先走 ①，
      没有 ssh.exe 时才退到 ②（并提醒局域网暴露这一点）。

.PARAMETER Codespace
  codespace 名。默认是当前这个（Alice 零期用的 `humble-tribble-*`）。

.PARAMETER LocalPort
  本机端口，默认 3181（避开你本机 DSH 的 3081）。

.PARAMETER Open
  挂好后直接用默认浏览器打开链接。

.PARAMETER Stop
  停掉本脚本之前挂的转发（按记录下来的 PID），然后退出。

.EXAMPLE
  pwsh -File tools\codespace-tunnel.ps1 -Open
.EXAMPLE
  pwsh -File tools\codespace-tunnel.ps1 -Stop
#>
[CmdletBinding()]
param(
    [string]$Codespace = "humble-tribble-97pv59gw5rg62prg5",
    [int]$LocalPort = 3181,
    [int]$RemotePort = 3081,
    [switch]$Open,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$PidFile = Join-Path $HOME ".dsh-cloud-tunnel.pid"

function Info($m) { Write-Host "→ $m" }
function Die($m)  { Write-Host "✗ $m" -ForegroundColor Red; exit 1 }

# ---------- -Stop ----------
if ($Stop) {
    if (Test-Path $PidFile) {
        foreach ($p in (Get-Content $PidFile)) {
            if ($p -match '^\d+$') { Stop-Process -Id ([int]$p) -Force -ErrorAction SilentlyContinue }
        }
        Remove-Item $PidFile -Force
        Info "已停掉记录的转发进程"
    } else { Info "没有记录（$PidFile）" }
    exit 0
}

# ---------- 0) gh 存在吗 ----------
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host @"
✗ 没找到 gh。装法（任选其一，都不需要 WSL）：
    winget install --id GitHub.cli -e
    scoop install gh
装完**新开一个终端**再跑本脚本。
"@ -ForegroundColor Red
    exit 2
}
Info "gh = $(gh --version | Select-Object -First 1)"

# ---------- 1) 认证：GH_TOKEN（PAT，带 codespace scope）----------
$TokenFile = Join-Path $HOME ".gh-token"
if (-not $env:GH_TOKEN) {
    if (Test-Path $TokenFile) {
        $env:GH_TOKEN = (Get-Content $TokenFile -Raw).Trim()
        Info "认证 = PAT（读自 $TokenFile，内容不打印）"
    } else {
        Write-Host @"
✗ 还没有认证。新设备上二选一：
  A) **有 PAT**（带 repo + codespace scope）：把它存成文件（别贴在聊天里）
        Set-Content -NoNewline -Path `$HOME\.gh-token -Value '<PAT>'
  B) **没有 PAT**：在能访问 github.com 的设备/网络上建一个
        https://github.com/settings/tokens/new?scopes=repo,codespace&description=alice-codespace
     然后按 A 存下来（注意：本机网络若不通 github.com，这一步要在别的设备做）。
"@ -ForegroundColor Red
        exit 3
    }
}
$login = (gh api /user --jq .login 2>$null)
if (-not $login) { Die "认证不通（检查 PAT 是否过期 / 网络是否能到 api.github.com）" }
Info "账号 = $login"

# ---------- 2) 确保远端 dsh web 在跑，并取回令牌 ----------
# ⚠️ gh 会把 `--` 之后的参数用空格拼成一个字符串，所以这里**只传一个参数**（PowerShell 的双引号串 = 一个参数）。
$remote = "cd /workspaces/Alice-mcbot 2>/dev/null || cd ~/projects/alice; " +
          "pgrep -f 'bin/dsh web' >/dev/null || (nohup env DSH_WORKDIR=/workspaces tools/codespace-start-dsh.sh >> ~/dsh-web.log 2>&1 &); " +
          "sleep 8; grep -o 'token=[A-Za-z0-9_-]*' ~/dsh-web.log | tail -1"
Info "唤醒 codespace + 确保远端服务在跑（首次要 10–30 秒）…"
$tokenLine = (gh codespace ssh -c $Codespace -- $remote 2>$null | Select-Object -Last 1)
$token = $null
if ($tokenLine -match 'token=([A-Za-z0-9_-]+)') { $token = $Matches[1] }
if (-not $token) { Info "⚠️ 没读到令牌（服务可能刚重启，稍后重跑本脚本；或手动看远端 ~/dsh-web.log）" }

# ---------- 3) 挂转发（优先 ssh -L = 只绑回环）----------
if (Test-Path $PidFile) {
    foreach ($p in (Get-Content $PidFile)) { if ($p -match '^\d+$') { Stop-Process -Id ([int]$p) -Force -ErrorAction SilentlyContinue } }
    Remove-Item $PidFile -Force
}
$newPids = @()
if (Get-Command ssh -ErrorAction SilentlyContinue) {
    Info "挂 ssh 转发（127.0.0.1:$LocalPort → 云端 $RemotePort，只绑回环）…"
    $p = Start-Process -FilePath "gh" -WindowStyle Hidden -PassThru -ArgumentList @(
        "codespace","ssh","-c",$Codespace,"--","-N",
        "-o","ServerAliveInterval=30","-o","ServerAliveCountMax=6","-o","ExitOnForwardFailure=yes",
        "-L","$LocalPort`:127.0.0.1:$RemotePort")
    $newPids += $p.Id
} else {
    Info "⚠️ 没有 ssh.exe ⇒ 退到 gh 原生转发（注意：它**绑在所有网卡**，局域网内可访问该端口）"
    Info "   （要更安全：装 Windows 可选功能 OpenSSH 客户端，或改用 WSL 里的 tools/codespace-zero.sh tunnel-bg）"
    $p = Start-Process -FilePath "gh" -WindowStyle Hidden -PassThru -ArgumentList @(
        "codespace","ports","forward","$RemotePort`:$LocalPort","-c",$Codespace)
    $newPids += $p.Id
}
$newPids | Set-Content $PidFile

# ---------- 4) 等就绪 + 打印链接 ----------
$base = "http://127.0.0.1:$LocalPort/"
$code = $null
foreach ($i in 1..20) {
    Start-Sleep -Seconds 2
    $code = (& curl.exe -s -o NUL -w "%{http_code}" --max-time 6 $base 2>$null)
    if ($code -eq "401" -or $code -eq "200" -or $code -eq "303") { break }
}
if ($code -eq "401" -or $code -eq "200" -or $code -eq "303") {
    Info "本地 $LocalPort 已就绪（HTTP $code；401 = 缺令牌，正常）"
} else {
    Info "⚠️ 本地 $LocalPort 还没就绪（最后一次 HTTP=$code）。稍等再重跑，或看是否有别的程序占用该端口。"
}
$url = if ($token) { "$base`?token=$token" } else { $base }

Write-Host ""
Write-Host "  ⭐ 浏览器打开（回环入口 ⇒ 设置页才可用）：" -ForegroundColor Green
Write-Host ""
Write-Host "      $url"
Write-Host ""
Write-Host "  · 每次**远端服务重启，令牌都会变** ⇒ 重跑本脚本拿新链接" -ForegroundColor DarkGray
Write-Host "  · 关掉转发：pwsh -File tools\codespace-tunnel.ps1 -Stop" -ForegroundColor DarkGray
Write-Host "  · 端口 $LocalPort 要没被占用（你本机 DSH 用的是 3081，所以这里用 $LocalPort）" -ForegroundColor DarkGray
Write-Host ""

if ($Open) { Start-Process $url }
