<#
.SYNOPSIS
  秒取云端 DSH 的【转发域名直链】—— 日常开界面用这个（快），不挂隧道。

.DESCRIPTION
  为什么要有它（2026-10-02 用户要求「让我获取转发的快速链接」）：
    · `alice-cloud.cmd`（隧道版）走的是「唤醒 → 挂 SSH 隧道 → 开 127.0.0.1:3181」，
      冷启实测要 45 秒；它慢是因为每个资源请求都要穿一趟 SSH 通道。
    · 日常只是打开云端界面 ⇒ 用**转发域名**就够（实测 1 秒 / 3 次都是 1 秒）。
    · ⚠️ 但**设置页 / 模型配置 / 工作区选择只有隧道版能开**（DSH 源码里用 isLoopback 判定）
      ⇒ 需要改配置时还是得用 alice-cloud.cmd。

  令牌从哪来：云端 `.devcontainer/post-start.sh`（开机钩子）每次启动把入口地址写到
  git 分支 `steward/entry` 的 `entry.txt`。本脚本用 `gh api` 纯 API 读它
  ⇒ **不需要 git、不需要 SSH 密钥、也不唤醒机器**。

  ⚠️ 本脚本会核对「入口里的 codespace 名」和「本机配置 %USERPROFILE%\.alice-client.json」
     是否一致 —— 不一致 = 入口被**另一台机器**覆盖了（两台共用 steward/entry 时会这样），
     这时链接打开的是那台机器，不是你配置的这台。

.PARAMETER Codespace
  codespace 名；空 = 读 %USERPROFILE%\.alice-client.json。

.PARAMETER Repo
  owner/repo；空 = 从本仓库的 git remote 推（再兜底 dddgn486/Alice-mcbot）。

.PARAMETER Open
  取到后直接用默认浏览器打开。

.PARAMETER Token
  只打印令牌（给别的脚本吃），不打印其它。

.PARAMETER Json
  输出 JSON（url / loopback / token / codespace / generated_at / stale_codespace）。

.PARAMETER Verify
  额外用 `gh codespace ssh` 读云端 ~/dsh-web.log 里的**活令牌**交叉核对（多花 3~5 秒）。
  入口若因故是旧的（例如被另一台机器覆盖），这条能抓出来并自动改用活令牌。
  ⭐ **入口标称的 codespace 与本机配置不一致时，本脚本会自动启用这条**（多花几秒，
     但这样给出的链接才是对的）—— 见下面 DESCRIPTION 里的「两台共用入口」那段。

.PARAMETER NoCheck
  跳过「实测这条链接通不通」那一步（默认会实测，~1 秒）。
  ⚠️ 强烈建议别跳 —— 光看 HTTP 状态码会骗人：端口是 private 时 GitHub 的登录页
     也是 200，DSH 的 401 也是 200 的 HTML；只有看响应体才分得清。

.EXAMPLE
  alice-url.cmd
  alice-url.cmd -Open
  alice-url.cmd -Token
  alice-url.cmd -Verify
#>
[CmdletBinding()]
param(
    [string]$Codespace = "",
    [string]$Repo = "",
    [switch]$Open,
    [switch]$Token,
    [switch]$Json,
    [switch]$Verify,
    [switch]$NoCheck
)
$ErrorActionPreference = "Continue"
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
function Say($m) { Write-Host ("[url] " + $m) }
function Die($m, $c = 1) { Write-Host ("[url] ERROR: " + $m) -ForegroundColor Red; exit $c }

# ---------- 代理（gh 是 Go，只认环境变量） ----------
try {
    $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
    if ($reg -and ([int]$reg.ProxyEnable -eq 1) -and $reg.ProxyServer -and -not $env:HTTPS_PROXY) {
        $srv = [string]$reg.ProxyServer; if ($srv -notmatch '^https?://') { $srv = "http://$srv" }
        $env:HTTPS_PROXY = $srv; $env:HTTP_PROXY = $srv
    }
} catch { }

# ---------- 推 owner/repo ----------
if (-not $Repo) {
    $c = $PSScriptRoot
    while ($c) { if (Test-Path (Join-Path $c "build.gradle")) { break }; $p = Split-Path $c -Parent; if ($p -eq $c) { $c = $null; break }; $c = $p }
    if ($c) {
        Push-Location $c
        try {
            $u = (& git remote get-url origin 2>$null | Out-String).Trim()
            if ($u -match 'github\.com[:/]+([^/]+/[^/\.]+)') { $Repo = $Matches[1] }
        } catch { }
        Pop-Location
    }
}
if (-not $Repo) { $Repo = "dddgn486/Alice-mcbot" }

# ---------- codespace 名 ----------
if (-not $Codespace) {
    $cfgPath = Join-Path $HOME ".alice-client.json"
    if (Test-Path $cfgPath) { try { $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json; if ($cfg.codespace) { $Codespace = [string]$cfg.codespace } } catch { } }
}
if (-not $Codespace) { Die "不知道 codespace 名（配置里没有、也没 -Codespace 传）" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Die "没有 gh（装 GitHub CLI）" }

# ---------- 读 steward/entry:entry.txt（纯 API，raw） ----------
$raw = ""
try { $raw = (& gh api "/repos/$Repo/contents/entry.txt?ref=steward/entry" -H "Accept: application/vnd.github.raw" 2>&1 | Out-String) } catch { }
if ($raw -notmatch 'token=') {
    Die ("读不到入口（steward/entry:entry.txt 里没有 token=）。可能原因：① 云端没启动过（开机钩子没跑）② 分支被清 ③ gh 没认证。`n--- gh 原始返回 ---`n" + $raw) 4
}

$tok = ""
if ($raw -match 'token=([A-Za-z0-9_\-]{8,})') { $tok = $Matches[1] }
$eCs = ""
if ($raw -match '(?m)^codespace:\s*(\S+)') { $eCs = $Matches[1] }
$eAt = ""
if ($raw -match '(?m)^generated_at:\s*(\S+)') { $eAt = $Matches[1] }
$eUrl = ""
if ($raw -match '(?m)^url:\s*(\S+)') { $eUrl = $Matches[1] }
$eLoop = ""
if ($raw -match '(?m)^loopback:\s*(\S+)') { $eLoop = $Matches[1] }
if (-not $eUrl) { $eUrl = "https://$eCs-3081.app.github.dev/?token=$tok" }

# ---------- 新鲜度 ----------
$ageTxt = "?"
if ($eAt) {
    try {
        $t = [datetime]::Parse($eAt)
        $mins = [int]([datetime]::UtcNow - $t.ToUniversalTime()).TotalMinutes
        if ($mins -lt 0) { $mins = 0 }
        $ageTxt = "$mins 分钟前"
    } catch { }
}

$staleCs = ($eCs -and $eCs -ne $Codespace)

# ---------- 可选：ssh 交叉核对活令牌 ----------
# ⭐ 入口指向**别的机器**时，默认也强制核对一次 —— 否则默认输出就是**错的链接**
#    （两台共用 steward/entry 分支时必然发生：谁最后启动谁覆盖）
if ($staleCs -and -not $Verify) {
    Say "入口指向别的机器 ⇒ 自动做一次 ssh 交叉核对（多花几秒，这样给你的链接才是对的）"
    $Verify = $true
}
$tokFixed = $false
$verifyTxt = ""
if ($Verify) {
    Say "用 ssh 读云端活令牌交叉核对…"
    $live = ""
    try { $live = (& gh codespace ssh -c $Codespace -- "grep -o 'token=[A-Za-z0-9_-]*' ~/dsh-web.log | tail -1" 2>&1 | Out-String) } catch { }
    if ($live -match 'token=([A-Za-z0-9_\-]{8,})') {
        $liveT = $Matches[1]
        if ($liveT -ne $tok) {
            $verifyTxt = "入口是旧令牌 ⇒ 已自动改用云端活令牌"
            $tok = $liveT
            $tokFixed = $true
            $eUrl = "https://$Codespace-3081.app.github.dev/?token=$tok"
            $eLoop = "http://127.0.0.1:3081/?token=$tok"
        } else {
            $verifyTxt = "一致（入口 = 活令牌）"
        }
    } else {
        $verifyTxt = "取不到活令牌（ssh 不通？）"
    }
}

# ---------- 实测这条链接到底通不通 ----------
# ⚠️ 2026-10-02 血泪教训：**别只看 HTTP 状态码**。端口是 private 时，转发域名前面挡着
#    GitHub 自己的登录页（302 → github.dev/pf-signin），那个页面**也是 200**，
#    于是"HTTP 200 就算服务活着"成了**假阳性**；而真正的 DSH 401（authentication
#    required）也照样是 200 的 HTML。⇒ 只有**看响应体内容**才分得清。
#    另一层：private 端口的登录流程是**跨站**跳转（github.dev → 本域名），而 DSH 的
#    会话 cookie 是 `SameSite=Strict` ⇒ 浏览器不回传 ⇒ 永远 401。所以端口必须是 public。
#    ⚠️ 而且**端口可见性不跨 stop/start 保留**（实测：每次唤醒都复位成 private）
#       ⇒ 不自动修的话，每天第一次双击必然拿到一张打不开的"链接"。
#    ⇒ 所以本脚本：**认 DSH 应用标志 `__ModuleLoader__` 才算通**（而不是"有 <!doctype html>"，
#      那太宽 —— GitHub 的转发认证页也是 HTML，实测 5068 字节，我第一版就误判过）；
#      一旦认出那张 GitHub 页，就**自动把端口改回 public 并重试一次**。
$checkTxt = ""
$checkOk = $false
$portFixed = $false
function Test-DshLink([string]$u) {
    $sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $resp = Invoke-WebRequest -Uri $u -WebSession $sess -MaximumRedirection 5 -TimeoutSec 30 -UseBasicParsing -ErrorAction Stop
    return [string]$resp.Content
}
$ghRelayPattern   = 'name="authUrl"|pf-signin|codespaces/auth/'
$dshAppPattern    = '__ModuleLoader__'
if (-not $NoCheck) {
    try {
        $body = Test-DshLink $eUrl
        # ① GitHub 的转发认证页 ⇒ 端口是 private ⇒ 自动改 public 再试一次
        #    ⚠️ 实测：端口可见性**不跨 stop/start 保留**（每次唤醒都复位成 private）
        #    ⇒ 不自动修的话，每天第一次双击必然拿到一张打不开的"链接"。
        if ($body -match $ghRelayPattern) {
            Say "端口是 private（拿到的是 GitHub 的转发认证页）⇒ 自动改成 public 再试"
            & gh codespace ports visibility '3081:public' -c $Codespace 2>&1 | Out-Null
            $portFixed = $true
            Start-Sleep -Seconds 6
            $body = Test-DshLink $eUrl
        }
        if ($body -match 'authentication required') {
            $checkTxt = "HTTP 200，但返回的是 DSH 的 401 页（authentication required）—— 令牌失效了？重启云端后重跑本命令"
        } elseif ($body -match $ghRelayPattern) {
            $checkTxt = "HTTP 200，但返回的是 GitHub 的转发认证页（端口仍不是 public / 改完还没生效）"
        } elseif ($body -match $dshAppPattern) {
            $checkOk = $true
            $checkTxt = "HTTP 200，$($body.Length) 字节，确认是 DSH 应用界面" + $(if ($portFixed) { "（顺手把端口改回 public 了）" } else { "" })
        } else {
            $checkTxt = "HTTP 200，$($body.Length) 字节，但既不是 DSH 应用也不是已知的认证页 —— 请人工看一眼"
        }
    } catch {
        $code = $null
        try { $code = $_.Exception.Response.StatusCode.value__ } catch { }
        if ($code) { $checkTxt = "HTTP $code —— $($_.Exception.Message)" } else { $checkTxt = "请求失败：" + $_.Exception.Message }
    }
}

# ---------- 输出 ----------
if ($Token) { Write-Output $tok; exit 0 }
if ($Json) {
    [pscustomobject]@{
        url             = $eUrl
        loopback        = $eLoop
        token           = $tok
        codespace       = $eCs
        generated_at    = $eAt
        stale_codespace = $staleCs
        verify          = $verifyTxt
        check_ok        = $checkOk
        check           = $checkTxt
    } | ConvertTo-Json -Depth 3
    exit 0
}

Write-Host ""
Write-Host "===== 云端转发直链（快：实测 1 秒）=====" -ForegroundColor Cyan
Write-Host $eUrl -ForegroundColor Green
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host ("  入口发布：{0}（{1}）" -f $eAt, $ageTxt) -ForegroundColor DarkGray
Write-Host ("  入口标称 codespace：{0}" -f $eCs) -ForegroundColor DarkGray
if ($verifyTxt) { Write-Host ("  活令牌核对：" + $verifyTxt) -ForegroundColor DarkGray }
if ($checkTxt) {
    if ($checkOk) { Write-Host ("  ✅ 链接实测：" + $checkTxt) -ForegroundColor Green }
    else { Write-Host ("  ❌ 链接实测：" + $checkTxt) -ForegroundColor Red }
}
Write-Host "  （要改设置页 / 模型 / 工作区 ⇒ 必须用隧道版 alice-cloud.cmd，DSH 只认回环）" -ForegroundColor DarkGray

if ($staleCs) {
    Write-Host ""
    Write-Host ("  ! 警告：入口里的 codespace 是【{0}】，本机配置的是【{1}】，两者不一致！" -f $eCs, $Codespace) -ForegroundColor Yellow
    Write-Host "    说明入口被另一台机器覆盖了（两台共用 steward/entry 分支时就会这样）。" -ForegroundColor Yellow
    if ($tokFixed) {
        Write-Host "    OK 上面那条 URL 已用【本机配置那台】的活令牌重建过 ⇒ 可以直接用。" -ForegroundColor Yellow
        Write-Host "       要根治：把多余的那台机器删掉，否则它下次启动还会覆盖入口。" -ForegroundColor Yellow
    } else {
        Write-Host "    ⇒ 打开这个链接进的是【那台】机器。处理办法：在目标机器上跑一次" -ForegroundColor Yellow
        Write-Host "      bash .devcontainer/post-start.sh（重新发布入口），或把多余的那台机器删掉。" -ForegroundColor Yellow
    }
}

Write-Host ""
try { Set-Clipboard -Value $eUrl; Write-Host "  （链接已复制到剪贴板）" -ForegroundColor DarkGray } catch { }
if ($Open) { Say "打开浏览器…"; Start-Process $eUrl }
exit 0
