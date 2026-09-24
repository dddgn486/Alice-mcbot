<#
.SYNOPSIS
  云端一写、本地自动唤醒：轮询云端信箱 `bus/to-win/`，**有新消息才**唤起管家 agent。

.DESCRIPTION
  为什么需要它（2026-09-24 云端实测 + 用户裁定）：
    · 唤醒目前只能靠人（桌面版说「读信箱」/ 双击 `client-agent.cmd`）；云端**没有入站通道**
      ⇒ 云端写好请求后，本地不知道，要等有人去叫。
    · 本脚本用**纯确定性**逻辑补这一段：每 N 秒用 gh 列一次云端信箱，与云端 `.done` 对比，
      **有未处理的新消息才唤起** `client-agent.cmd`（headless 单发，默认任务＝读信箱并照做）。
    · ⭐ **没有新消息时零 LLM 调用**（根本不启动 dsh）⇒ **零 token 成本**；只有真有事才花钱。

  成本口径：一轮 = 1 次 `gh codespace ssh`（几 KB 出网、0 次模型调用）。默认 300 s ⇒ 一天约 288 次。

  三种跑法：
    ① 手动一次：`bus-watch.cmd -Once`
    ② 前台挂着：`bus-watch.cmd`（Ctrl-C 停；或另开窗口 `bus-watch.cmd -Stop`）
    ③ ⭐ 登录自启：`bus-watch.cmd -Install`（隐藏窗口后台跑）；取消：`bus-watch.cmd -Uninstall`

  纪律（都是踩过的坑）：
    · `.done` 是**管家的账**（由管家追加）⇒ 本脚本**只读，绝不写**；本脚本自己的"已尝试"记在本地状态文件里。
    · 网络/服务抖动**静默重试**（连续失败只在第一次记一行日志，恢复时记一行），不刷日志。
    · **一次只唤醒一个 agent**：单实例 pid 文件 + 唤醒锁（唤醒期间阻塞，不并发）。
    · 不用 `pgrep -f` 自检（模式会匹配到自己）⇒ 用 pid 文件 + `Win32_Process.CommandLine` 核对**指定 pid**。
    · codespace 名与信箱路径**从 `%USERPROFILE%\.alice-client.json` 读**，不硬编码。
    · ⚠️ **必须新开一个会话**才会加载新的人设；本脚本本身与人设无关（它是确定性的）。

.PARAMETER Codespace
  codespace 名；空 = 读 `%USERPROFILE%\.alice-client.json` 的 `codespace` 字段。

.PARAMETER RemoteBus
  云端信箱目录；空 = 读同一文件的 `mailbox.toWin`（默认 `/home/vscode/bus/to-win`）。

.PARAMETER IntervalSec
  轮询间隔（秒），默认 300。

.PARAMETER RetryAfterMin
  同一条消息两次唤醒尝试之间的最小间隔（分钟），默认 30（防止管家失败后连环唤醒）。

.PARAMETER Once
  只跑一轮就退出（自检用）。

.PARAMETER DryRun
  只报告"会做什么"，**绝不唤起** agent（自检用）。

.PARAMETER Stop
  停掉正在跑的 bus-watch（读 pid 文件，核对确实是我们自己再杀）。

.PARAMETER Install / Uninstall
  注册/取消"登录自启"计划任务（隐藏窗口）。

.EXAMPLE
  bus-watch.cmd -Once
  bus-watch.cmd -DryRun
  bus-watch.cmd -Install
  bus-watch.cmd -Stop
#>
[CmdletBinding()]
param(
    [string]$Codespace = "",
    [string]$RemoteBus = "",
    [int]$IntervalSec = 300,
    [int]$RetryAfterMin = 30,
    [int]$QuietRetrySec = 60,
    [string]$LogFile = "",
    [string]$PidFile = "",
    [string]$StateFile = "",
    [switch]$Once,
    [switch]$DryRun,
    [switch]$Stop,
    [switch]$Install,
    [switch]$Uninstall
)

$ErrorActionPreference = "Continue"
$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path
$TaskName = "alice-bus-watch"
if (-not $LogFile)   { $LogFile   = Join-Path $env:TEMP "bus-watch.log" }
if (-not $PidFile)   { $PidFile   = Join-Path $env:TEMP "bus-watch.pid" }
if (-not $StateFile) { $StateFile = Join-Path $env:TEMP "bus-watch.state.json" }
$WakeLock = Join-Path $env:TEMP "bus-watch.wakelock"
$CfgPath  = Join-Path $HOME ".alice-client.json"
$TokenFile = Join-Path $HOME ".gh-token"
$MaxLogMB = 5

function Info($m) { $s = "[" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "][bus-watch] " + $m; Write-Host $s }
function Warn($m) { $s = "[" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "][bus-watch] !! " + $m; Write-Host $s -ForegroundColor Yellow }
function Add-Log([string]$m) {
    # 日志只写"状态变化"与"真有事"两类，抖动不刷屏（见 .DESCRIPTION）
    try {
        if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt ($MaxLogMB * 1MB))) {
            Move-Item $LogFile ($LogFile + ".1") -Force -ErrorAction SilentlyContinue
        }
        Add-Content -Path $LogFile -Value ("[" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "][bus-watch] " + $m) -Encoding UTF8
    } catch { }
}

# ---------- gh（裸 gh 不可用时退回全路径；实测：管家会话会继承旧 PATH 快照） ----------
function Resolve-Gh {
    $c = Get-Command gh -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $g = "C:\Program Files\GitHub CLI\gh.exe"
    if (-not (Test-Path $g)) { $g = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe" }
    if (Test-Path $g) { $env:Path = (Split-Path $g) + ";" + $env:Path; return $g }
    return $null
}
$Gh = Resolve-Gh
if (-not $Gh) { Warn "找不到 gh（装法：winget install --id GitHub.cli -e）"; exit 2 }
if (-not $env:GH_TOKEN -and (Test-Path $TokenFile)) { $env:GH_TOKEN = (Get-Content $TokenFile -Raw).Trim() }

# ---------- 机器相关事实一律从配置文件读 ----------
$cfg = $null
if (Test-Path $CfgPath) { try { $cfg = Get-Content $CfgPath -Raw | ConvertFrom-Json } catch { Warn "配置读不了（$CfgPath）：$($_.Exception.Message)" } }
if (-not $Codespace) {
    if ($cfg -and $cfg.codespace) { $Codespace = $cfg.codespace }
    else { Warn "没有 codespace 名：先跑 client-agent.cmd -Install，或用 -Codespace 指定"; exit 3 }
}
if (-not $RemoteBus) {
    if ($cfg -and $cfg.mailbox -and $cfg.mailbox.toWin) { $RemoteBus = $cfg.mailbox.toWin }
    else { $RemoteBus = "/home/vscode/bus/to-win" }
}

function Get-WatcherPid {
    if (-not (Test-Path $PidFile)) { return $null }
    $p = (Get-Content $PidFile -Raw).Trim()
    if ($p -notmatch '^\d+$') { return $null }
    $proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $p) -ErrorAction SilentlyContinue
    if ($proc -and $proc.CommandLine -and ($proc.CommandLine -match 'bus-watch')) { return [int]$p }
    return $null
}
function Save-State($s) { try { $s | ConvertTo-Json -Depth 5 | Set-Content -Path $StateFile -Encoding UTF8 } catch { } }
function Load-State {
    $s = @{ lastCheck = ""; failing = $false; cool = @{} }
    if (Test-Path $StateFile) {
        try {
            $j = Get-Content $StateFile -Raw | ConvertFrom-Json
            if ($j.lastCheck) { $s.lastCheck = [string]$j.lastCheck }
            if ($j.failing)   { $s.failing   = [bool]$j.failing }
            if ($j.cool) { foreach ($k in $j.cool.PSObject.Properties.Name) { $s.cool[$k] = [string]$j.cool.$k } }
        } catch { Warn "状态文件损坏，重新开始" }
    }
    return $s
}

# ---------- -Stop ----------
if ($Stop) {
    $p = Get-WatcherPid
    if ($p) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue; Remove-Item $PidFile -Force -ErrorAction SilentlyContinue; Info "已停掉 bus-watch（pid $p）" }
    else { Info "没有在跑的 bus-watch（pid 文件：$PidFile）" }
    exit 0
}

# ---------- -Install / -Uninstall（登录自启，隐藏窗口） ----------
if ($Install) {
    $cmd = Join-Path $HERE "bus-watch.cmd"
    if (-not (Test-Path $cmd)) { Warn "找不到 $cmd"; exit 2 }
    $act = New-ScheduledTaskAction -Execute "cmd.exe" -Argument ("/c `"" + $cmd + "`"")
    $trg = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    Register-ScheduledTask -TaskName $TaskName -Action $act -Trigger $trg -Settings $set -Force | Out-Null
    Info "已注册登录自启：$TaskName（每 ${IntervalSec}s 检查一次；取消：bus-watch.cmd -Uninstall）"
    Info "日志：$LogFile    状态：$StateFile"
    exit 0
}
if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Info "已取消登录自启：$TaskName（正在跑的那个进程不受影响，用 -Stop 停）"
    exit 0
}

# ---------- 单实例（-Once 不受限，但仍受唤醒锁保护） ----------
if (-not $Once) {
    $other = Get-WatcherPid
    if ($other -and ($other -ne $PID)) { Info "已有 bus-watch 在跑（pid $other），本次退出"; exit 0 }
}

$state = Load-State
if (-not $Once) { $PID | Set-Content -Path $PidFile -Encoding ASCII }

# ---------- 一轮：列信箱 + 读 .done（只读！）→ 算未处理 ----------
function Get-NewMessages {
    # ⚠️ 远端脚本必须以 `true` 收尾：`.done` 可能还不存在，`cat` 会返回非 0 ⇒ 若据此判失败，
    #    正常的空信箱会被误判成"检查失败"（自检时踩过：-Once 静默无输出）。改用显式标记判成功。
    $script = "ls -1p " + $RemoteBus + " 2>/dev/null; echo '###DONE###'; cat " + $RemoteBus + "/.done 2>/dev/null; true"
    $raw = & $Gh codespace ssh -c $Codespace -- $script 2>$null
    $txt = ($raw | Out-String)
    if ($txt -notmatch '###DONE###') { throw ("看信箱失败：gh 没返回预期标记（退出码 " + $LASTEXITCODE + "）") }
    $parts = $txt -split '###DONE###'
    $names = @()
    foreach ($l in ($parts[0] -split "`n")) {
        $n = $l.Trim()
        if (-not $n) { continue }
        if ($n -like '*/') { continue }                 # 目录不算消息
        if ($n -eq '.done' -or $n.StartsWith('.')) { continue }
        $names += $n
    }
    $done = $parts[1]
    $new = @()
    foreach ($n in $names) {
        # .done 的每行是管家写的"已处理文件名"（可能带额外字段）⇒ 用包含判断更稳
        if ($done -and ($done -match [regex]::Escape($n))) { continue }
        $new += $n
    }
    return $new
}
function In-Cooldown([string]$name) {
    if (-not $state.cool.ContainsKey($name)) { return $false }
    try {
        $t = [datetime]::Parse($state.cool[$name])
        return ((Get-Date) - $t).TotalMinutes -lt $RetryAfterMin
    } catch { return $false }
}
function Wake-Locked {
    if (-not (Test-Path $WakeLock)) { return $false }
    try {
        $j = Get-Content $WakeLock -Raw | ConvertFrom-Json
        $proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$j.pid) -ErrorAction SilentlyContinue
        return [bool]$proc
    } catch { return $false }
}

$round = 0
do {
    $round++
    $woke = $false
    try {
        $new = Get-NewMessages
        if ($state.failing) { Add-Log "已恢复（gh/服务正常）"; $state.failing = $false }
        $state.lastCheck = (Get-Date).ToString("s")
        $todo = @($new | Where-Object { -not (In-Cooldown $_) })
        if ($todo.Count -eq 0) {
            if ($new.Count -gt 0) { Info ("有新消息但都在冷却中（$RetryAfterMin 分钟内试过）：" + ($new -join ", ")) }
            elseif ($Once) { Info "没有新消息 ⇒ 不唤起 agent（零 LLM 调用）" }
        } else {
            Info ("发现 " + $todo.Count + " 条未处理消息：" + ($todo -join ", "))
            Add-Log ("发现未处理消息：" + ($todo -join ", "))
            if ($DryRun) {
                Info "[dry-run] 会唤起：client-agent.cmd（默认任务＝读信箱并照做）"
            } elseif (Wake-Locked) {
                Warn "已有一次唤醒在进行中（锁：$WakeLock）⇒ 本轮跳过，下轮再看"
            } else {
                $agent = Join-Path $HERE "client-agent.cmd"
                if (-not (Test-Path $agent)) { throw "找不到 $agent" }
                @{ pid = $PID; at = (Get-Date).ToString("s") } | ConvertTo-Json | Set-Content -Path $WakeLock -Encoding UTF8
                try {
                    Add-Log ("=== 唤起 agent（新消息：" + ($todo -join ", ") + "）===")
                    cmd.exe /c ("`"" + $agent + "`" >> `"" + $LogFile + "`" 2>&1")
                    Add-Log ("=== agent 退出码 " + $LASTEXITCODE + " ===")
                } finally {
                    Remove-Item $WakeLock -Force -ErrorAction SilentlyContinue
                }
                $woke = $true
                foreach ($n in $todo) { $state.cool[$n] = (Get-Date).ToString("s") }
            }
        }
        Save-State $state
    } catch {
        # 静默重试：连续失败只在第一次记一行，恢复时再记一行
        if (-not $state.failing) { Add-Log ("检查失败（将静默重试）：" + $_.Exception.Message); $state.failing = $true }
        Save-State $state
        if (-not $Once) { Start-Sleep -Seconds $QuietRetrySec; continue }
    }
    if (-not $Once) { Start-Sleep -Seconds $IntervalSec }
} while (-not $Once)

if (-not $Once -and (Test-Path $PidFile)) { Remove-Item $PidFile -Force -ErrorAction SilentlyContinue }
exit 0
