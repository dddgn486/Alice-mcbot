<#
.SYNOPSIS
  新设备**自检**：不花钱（不叫 LLM）地把"这台机器能不能当客户端管家"逐项验一遍。
  由 `client-agent.cmd -SelfTest` 调用。

.DESCRIPTION
  检查顺序刻意从"本机"到"云端"，哪一步断了就是哪一段的问题：
    ① 本机依赖：node / dsh
    ② 本机配置：~/.alice-client.json（机器相关事实的唯一来源）+ 客户端目录形状（logs/ + mods/）
    ③ 凭据：%USERPROFILE%\.gh-token + gh 是否真能用（gh api /user）
    ④ 云端可达：codespace 名字对不对、能不能 ssh 进去、信箱目录在不在
    ⑤ 读信箱：~/bus/to-win 列得出来
    ⑥ **写信箱**：写一个探针文件到 ~/bus/to-cloud/ 再读回来（这是"回执能不能送到"的关键一步）
    ⑦ 附件通道：小文件走 `gh codespace cp -e` 到 ~/client-info/selftest/ 并核对字节数
  全绿 ⇒ 再唤醒 agent；有红 ⇒ 按输出里的提示修，不要先怀疑 agent。
#>
[CmdletBinding()]
param(
    [string]$Codespace = "",
    [switch]$Quiet
)
$ErrorActionPreference = "Continue"

# ⭐ 必须自己刷新 PATH：winget/npm 刚装完的东西只在**新终端**里可见，
#    从别处（WSL 调用、计划任务、刚装完就双击）启动时 PATH 是旧的 ⇒ 会出现"明明装了却说没有"。
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')

$script:ok = 0; $script:bad = 0
function Say($m, $c) { Write-Host $m -ForegroundColor $c; $script:buf += $m }
function Pass($m) { $script:ok++;  Say ("  [ OK ] " + $m) "Green" }
function Fail($m) { $script:bad++; Say ("  [FAIL] " + $m) "Red" }
function Hint($m) { if (-not $Quiet) { Say ("         -> " + $m) "DarkGray" } }

$script:buf = @()
$script:LogPath = Join-Path $env:TEMP ("client-agent-selftest-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")
Say ("=== 客户端管家自检（" + $env:COMPUTERNAME + "）===") "Cyan"

# ---------- ① 本机依赖 ----------
if (Get-Command node -ErrorAction SilentlyContinue) { Pass ("node " + (node --version)) } else { Fail "没有 node（装 Node.js 22+）" }
if (Get-Command dsh -ErrorAction SilentlyContinue) {
    $v = (& dsh --version 2>&1) -join " "
    Pass ("dsh 可用：" + $v)
} else { Fail "没有 dsh ⇒ 跑 client-agent.cmd -Install 安装" }

# ---------- ② 配置 + 客户端目录 ----------
$cfgPath = Join-Path $HOME ".alice-client.json"
$cfg = $null
if (Test-Path $cfgPath) {
    Pass "配置存在：$cfgPath"
    try { $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json } catch { Fail "配置不是合法 JSON ⇒ 重跑 -Install" }
} else { Fail "缺 $cfgPath ⇒ 跑 client-agent.cmd -Install（它会发现客户端路径并写这份配置）" }

if ($cfg) {
    if (-not $Codespace -and $cfg.codespace) { $Codespace = $cfg.codespace }
    if ($cfg.clientRoot) {
        $cr = $cfg.clientRoot
        if (Test-Path $cr) {
            $hasLogs = Test-Path (Join-Path $cr "logs"); $hasMods = Test-Path (Join-Path $cr "mods")
            if ($hasLogs -and $hasMods) { Pass "客户端目录形状正确：$cr（含 logs/ 与 mods/）" }
            else { Fail "客户端目录存在但形状不对（logs=$hasLogs mods=$hasMods）：$cr" }
            if (Test-Path (Join-Path $cr "logs\latest.log")) { Pass "latest.log 存在（$([math]::Round((Get-Item (Join-Path $cr 'logs\latest.log')).Length/1KB,1)) KB）" }
            else { Hint "没有 logs\latest.log（客户端还没启动过？）" }
        } else { Fail "配置里的 clientRoot 不存在：$cr" }
    } else { Fail "配置里没有 clientRoot" }
}
if (-not $Codespace) { Fail "不知道 codespace 名（配置里没有、也没用 -Codespace 传）"; $Codespace = "?" }

# ---------- ③ 凭据 ----------
$tok = Join-Path $HOME ".gh-token"
if (Test-Path $tok) { Pass "PAT 文件存在：$tok" } else { Fail "缺 $tok（GitHub PAT，需要 codespace + repo scope）" }
$gh = Get-Command gh -ErrorAction SilentlyContinue
if ($gh) { Pass ("gh 可用：" + $gh.Source) } else { Fail "没有 gh ⇒ winget install --id GitHub.cli -e" }
if ($gh) {
    $login = (& gh api /user --jq .login 2>$null)
    if ($login) { Pass "gh 已认证：$login" }
    else {
        Fail "gh 未认证"
        Hint "把 PAT 写进 gh 凭据文件（**不要**用 gh auth login --with-token：本 PAT 缺 read:org，实测会被拒）："
        Hint '  $d = Join-Path $env:APPDATA "GitHub CLI"; New-Item -ItemType Directory -Force $d | Out-Null'
        Hint '  "github.com:`n    oauth_token: $((Get-Content "$HOME\.gh-token" -Raw).Trim())`n" | Set-Content (Join-Path $d "hosts.yml")'
    }
}

# ---------- ④ 云端可达 ----------
if ($gh -and $login -and $Codespace -ne "?") {
    $st = (& gh codespace list --json name,state 2>$null | ConvertFrom-Json)
    $me = $st | Where-Object { $_.name -eq $Codespace }
    if ($me) { Pass ("codespace '$Codespace' 状态 = " + $me.state) } else { Fail "codespace '$Codespace' 不在你的列表里（名字写错？或用 gh codespace list 查）" }
    $ls = (& gh codespace ssh -c $Codespace -- 'ls -d /home/vscode/bus/to-win /home/vscode/bus/to-cloud /home/vscode/client-info /home/vscode/outbox 2>/dev/null | wc -l' 2>$null)
    if ($ls -and ($ls -join "").Trim() -eq "4") { Pass "云端信箱四个目录都在" }
    else {
        Fail "云端信箱目录不全（得到 '$ls'）"
        Hint "让云端侧建：gh codespace ssh -c $Codespace -- 'mkdir -p ~/bus/to-win ~/bus/to-cloud ~/client-info ~/outbox'"
    }
    # ---------- ⑤ 读信箱 ----------
    $inbox = (& gh codespace ssh -c $Codespace -- 'ls -1 /home/vscode/bus/to-win 2>/dev/null' 2>$null) -join "`n"
    if ($inbox -match "\S") { Pass ("读信箱 OK，里面有：" + (($inbox -split "`n" | Where-Object { $_ -match '\S' }) -join ", ")) }
    else { Fail "读信箱失败或为空" }
    # ---------- ⑥ 写信箱（关键）----------
    $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
    $probeName = "selftest-$($env:COMPUTERNAME)-$stamp.md"
    $tmp = Join-Path $env:TEMP $probeName
    "# 探针`n由 $env:COMPUTERNAME 在 $stamp UTC 写入（client-agent -SelfTest）" | Set-Content -Encoding UTF8 $tmp
    & gh codespace cp -e $tmp "remote:/home/vscode/bus/to-cloud/$probeName" -c $Codespace 2>$null | Out-Null
    $back = (& gh codespace ssh -c $Codespace -- "cat /home/vscode/bus/to-cloud/$probeName 2>/dev/null" 2>$null) -join "`n"
    if ($back -match "探针") { Pass "写信箱 OK 并读回（/home/vscode/bus/to-cloud/$probeName）" } else { Fail "写信箱失败（回执送不出去！）"; Hint "看 gh codespace cp 的原始报错；远端路径必须绝对" }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    # ---------- ⑦ 附件通道 ----------
    if ($cfg -and $cfg.clientRoot) {
        $ll = Join-Path $cfg.clientRoot "logs\latest.log"
        if (Test-Path $ll) {
            $n = (Get-Item $ll).Length
            & gh codespace ssh -c $Codespace -- "mkdir -p /home/vscode/client-info/selftest" 2>$null | Out-Null
            & gh codespace cp -e $ll "remote:/home/vscode/client-info/selftest/latest.log" -c $Codespace 2>$null | Out-Null
            $rn = (& gh codespace ssh -c $Codespace -- 'stat -c %s /home/vscode/client-info/selftest/latest.log 2>/dev/null' 2>$null) -join ""
            if ($rn -and [int]($rn.Trim()) -eq $n) { Pass "附件通道 OK（latest.log $n 字节，两端一致）" }
            else { Fail "附件通道异常：本地 $n 字节 / 远端 '$($rn.Trim())'" }
        } else { Hint "跳过附件测试（没有 logs\latest.log）" }
    }
}

Write-Host ""
$script:buf += ""
$script:buf += ("=== 汇总：" + $script:ok + " 项通过 / " + $script:bad + " 项失败 ===")
$script:buf | Set-Content -Encoding UTF8 $script:LogPath
Write-Host ("日志（UTF-8，可直接发我路径）：" + $script:LogPath) -ForegroundColor Cyan
if ($script:bad -eq 0) { Say "=== 全绿：$($script:ok) 项通过 => 可以唤醒 agent ===" "Green"; exit 0 }
else { Say "=== $($script:bad) 项失败 / $($script:ok) 项通过 => 先按上面的提示修，不要先怀疑 agent ===" "Red"; exit 1 }
