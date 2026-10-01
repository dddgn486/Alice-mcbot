<#
.SYNOPSIS
  一键打开**云端** DSH 网页界面（新设备自助）—— 唤醒云端 → 取带令牌地址 → 开浏览器。

.DESCRIPTION
  为什么要有它（2026-10-02 用户硬需求：「新设备跟云端交互，不然迁移干嘛」）：
    新设备的 `~/.ssh` **内容读被系统层挡住**（元数据正常、非 OneDrive 占位符；
    连 `cmd /c type` 都挂、`cmd /c copy` exit=1）⇒ **`gh codespace ssh/cp` 在那台机器上全废**
    ⇒ 拿不到云端 `dsh web` 那个**每次启动新生成**的令牌。
  本脚本只用**纯 API + git**（都不需要 SSH 密钥）：
    ① `gh api -X POST /user/codespaces/<名>/start` 唤醒（已停就先唤醒）
    ② 轮询 `.state` 直到 `Available`
    ③ `git fetch origin steward/entry` ⇒ 读 `entry.txt`（云端 `postStartCommand` 钩子每次启动写的）
    ④ 打开浏览器（含 ?token=…）

  云端侧的载体：`.devcontainer/post-start.sh`（每次启动发布入口地址到 `steward/entry` 分支）。

.PARAMETER Codespace
  codespace 名；空 = 读 `%USERPROFILE%\.alice-client.json`。

.PARAMETER NoOpen
  只打印地址，不开浏览器。

.PARAMETER Repo
  仓库路径；空 = 从脚本位置往上找 `build.gradle`。

.EXAMPLE
  client-agent.cmd -Cloud
#>
[CmdletBinding()]
param(
    [string]$Codespace = "",
    [string]$Repo = "",
    [switch]$NoOpen,
    [int]$WaitSeconds = 180
)
$ErrorActionPreference = "Continue"
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
function Say($m) { Write-Host ("[cloud] " + $m) }
function Die($m,$c=1) { Write-Host ("[cloud] 错误：" + $m) -ForegroundColor Red; exit $c }

# 代理（gh 是 Go，只认环境变量）
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
if (-not $Codespace) { Die "不知道 codespace 名（配置里没有、也没 -Codespace 传）" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Die "没有 gh（装 GitHub CLI）" }

# ---------- ① 唤醒 ----------
$state = ""
try { $state = (& gh api "/user/codespaces/$Codespace" --jq '.state' 2>&1 | Out-String).Trim() } catch { }
Say ("云端状态：" + $state)
if ($state -ne "Available") {
    Say "不是 Available ⇒ 发 start（纯 API，不需要 SSH 密钥）"
    & gh api -X POST "/user/codespaces/$Codespace/start" > $null 2>&1
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt $WaitSeconds) {
        Start-Sleep -Seconds 6
        $state = (& gh api "/user/codespaces/$Codespace" --jq '.state' 2>&1 | Out-String).Trim()
        Say ("  state=" + $state)
        if ($state -eq "Available") { break }
    }
    if ($state -ne "Available") { Die "唤醒了 $WaitSeconds 秒仍是 $state" 3 }
}

# ---------- ② 域名（纯 API）----------
$url = ""
try {
    $portLine = (& gh codespace ports -c $Codespace 2>&1 | Out-String)
    if ($portLine -match 'https://[A-Za-z0-9.-]+') { $url = $Matches[0] }
} catch { }
if (-not $url) { $url = "https://$Codespace-3081.app.github.dev" }
Say ("转发域名：" + $url)

# ---------- ③ 令牌（git 分支；云端 postStartCommand 钩子写的）----------
# ⚠️ 刚唤醒时钩子可能还没跑完 ⇒ 重试几轮
$token = ""
$entry = ""
for ($i = 1; $i -le 6; $i++) {
    Push-Location $Repo
    try {
        & git fetch -q origin steward/entry 2>&1 | Out-Null
        $entry = (& git show "origin/steward/entry:entry.txt" 2>&1 | Out-String)
    } catch { }
    Pop-Location
    if ($entry -match 'token=([A-Za-z0-9_\-]{8,})') { $token = $Matches[1]; break }
    Say ("  第 $i 轮还没读到入口（钩子可能还在跑）⇒ 等 15 秒")
    Start-Sleep -Seconds 15
}
if (-not $token) {
    Die "没读到令牌（分支 steward/entry 里没有 token=）。云端可能刚启动、钩子还没写完；稍后重跑本命令，或让云端侧手动跑 .devcontainer/post-start.sh" 4
}
$full = "$url/?token=$token"
Write-Host ""
Write-Host "========== 云端入口（新设备用这个）==========" -ForegroundColor Cyan
Write-Host $full -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "（⚠️ 这个令牌**每次云端重启都会换**；失效了就重跑本命令）" -ForegroundColor DarkGray

if ($NoOpen) { exit 0 }
Say "打开浏览器…"
Start-Process $full
exit 0
