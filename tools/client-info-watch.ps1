<#
.SYNOPSIS
  客户端测试数据**自动上传**：新日志 / 新截图 / 崩溃报告自动出现在云端 codespace 的 ~/client-info/<本次标记>/，
  你在 Windows 上什么都不用做（挂一次就一直在跑）。

.DESCRIPTION
  为什么这样设计（都是实测数字，2026-09-24）：
    · `logs/latest.log` 68 K、`logs/debug.log` 1.2 M ⇒ **按字节偏移增量追尾**（只传新增那一段，追加到远端同名文件）
    · `screenshots/` 69 张共 **252 M**，单张 5–7 M ⇒ **只推本次没推过的**，并**默认缩到宽 1600**（云端 AI 的 read_image 本来也会缩）
    · `crash-reports/` 很小 ⇒ 变了就推
  云端侧就是**普通文件** ⇒ AI 可以直接 read / read_image（截图能"看"）。

  自动的三种程度（按需选）：
    ① 手动跑一次：`client-info-watch.cmd -Once`
    ② 测试期间挂着：`client-info-watch.cmd`（前台；关掉窗口即停）
    ③ ⭐ **登录自启**（真·自动）：`client-info-watch.cmd -Install` —— 注册当前用户登录时任务，隐藏窗口后台跑；`-Uninstall` 取消

.PARAMETER Codespace
  codespace 名（默认 Alice 零期那台）。

.PARAMETER ClientRoot
  固定客户端目录（默认 `D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`）。

.PARAMETER RemoteDir
  云端根目录（默认 `/home/vscode/client-info`），每次运行落在 `<RemoteDir>\<Tag>\`。

.PARAMETER Tag
  本次测试标记；不给就用时间戳。告诉云端 AI "读 <Tag> 那批"即可。

.PARAMETER Screenshots
  `new`（默认，只推新增）/ `all`（同 new，但忽略"已推过"记录）/ `off`（不推截图）。

.PARAMETER Once
  只跑一轮就退出（适合"测完推一次"）。

.PARAMETER LiveLog
  同时把 `logs/latest.log` 的最新若干行写进 `live.log`（更小、更快看一眼）；默认关。

.EXAMPLE
  client-info-watch.cmd -Install          # 装成登录自启（推荐）
  client-info-watch.cmd -Once             # 只推一轮
  client-info-watch.cmd -Stop             # 停掉正在跑的守护
  client-info-watch.cmd -PullOutbox       # 反向：把云端 ~/outbox 拉到本机（AI 产出的 jar 等）
#>
[CmdletBinding()]
param(
    [string]$Codespace = "humble-tribble-97pv59gw5rg62prg5",
    [string]$ClientRoot = "D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10",
    [string]$RemoteDir  = "/home/vscode/client-info",
    [string]$Tag = "",
    [int]$IntervalSec = 10,
    [ValidateSet("off","new","all")][string]$Screenshots = "new",
    [int]$DownscaleMaxWidth = 1600,
    [int]$MaxScreenshotMB = 12,
    [switch]$Once,
    [switch]$LiveLog,
    [switch]$Stop,
    [switch]$Install,
    [switch]$Uninstall,
    [switch]$PullOutbox,
    [string]$PullTo = "D:\JAVA_projects\alice-outbox"
)

$ErrorActionPreference = "Continue"
$PidFile   = Join-Path $HOME ".dsh-client-info.pid"
$StateFile = Join-Path $HOME ".dsh-client-info.state.json"
$TaskName  = "alice-client-info-watch"
$TokenFile = Join-Path $HOME ".gh-token"
$LogDir    = Join-Path $ClientRoot "logs"

function Info($m) { Write-Host ("[" + (Get-Date -Format "HH:mm:ss") + "] " + $m) }
function Warn($m) { Write-Host ("[" + (Get-Date -Format "HH:mm:ss") + "] !! " + $m) -ForegroundColor Yellow }

# ---------- gh + PAT（与隧道脚本同一套凭据） ----------
function Resolve-Gh {
    $c = Get-Command gh -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $g = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
    if (Test-Path $g) { $env:Path = (Split-Path $g) + ";" + $env:Path; return $g }
    return $null
}
$Gh = Resolve-Gh
if (-not $Gh) { Warn "找不到 gh（装法：winget install --id GitHub.cli -e）"; exit 2 }
if (-not $env:GH_TOKEN -and (Test-Path $TokenFile)) { $env:GH_TOKEN = (Get-Content $TokenFile -Raw).Trim() }
if (-not $env:GH_TOKEN) { Warn "没有 PAT（先跑一次 client-info-watch 的同目录脚本 codespace-tunnel.cmd 生成，或设 GH_TOKEN）"; exit 3 }

function Remote-Exec([string]$script) {
    # gh 会把 `--` 之后的参数用空格拼接 ⇒ 这里只传**一个**参数（PS 串 = 一个参数）
    & $Gh codespace ssh -c $Codespace -- $script 2>$null
}
function Remote-Put([string]$local, [string]$remote) {
    & $Gh codespace cp -e $local "remote:$remote" -c $Codespace 2>$null | Out-Null
}

# ---------- -Stop ----------
if ($Stop) {
    if (Test-Path $PidFile) {
        foreach ($p in (Get-Content $PidFile)) { if ($p -match '^\d+$') { Stop-Process -Id ([int]$p) -Force -ErrorAction SilentlyContinue } }
        Remove-Item $PidFile -Force
        Info "已停掉守护"
    } else { Info "没有记录（$PidFile）" }
    exit 0
}

# ---------- -Install / -Uninstall（登录自启） ----------
$Self = $MyInvocation.MyCommand.Path
if ($Install) {
    $args = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Self`" -Codespace $Codespace -ClientRoot `"$ClientRoot`""
    $act = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $args
    $trg = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    Register-ScheduledTask -TaskName $TaskName -Action $act -Trigger $trg -Settings $set -Force | Out-Null
    Info "已注册登录自启任务：$TaskName（取消：client-info-watch.cmd -Uninstall）"
    exit 0
}
if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Info "已取消自启任务：$TaskName"
    exit 0
}

# ---------- -PullOutbox（云端 → 本机） ----------
if ($PullOutbox) {
    New-Item -ItemType Directory -Force -Path $PullTo | Out-Null
    Info "拉取云端 outbox → $PullTo"
    & $Gh codespace ssh -c $Codespace -- "mkdir -p ~/outbox && ls ~/outbox" 2>$null | ForEach-Object {
        if ($_ -and $_ -notmatch '^\s*$') {
            & $Gh codespace cp -e "remote:/home/vscode/outbox/$_" $PullTo -c $Codespace 2>$null | Out-Null
            Info "  拉回 $_"
        }
    }
    exit 0
}

# ---------- 状态 ----------
$state = @{ offsets = @{}; shots = @{} }
if (Test-Path $StateFile) {
    try { $j = Get-Content $StateFile -Raw | ConvertFrom-Json
          foreach ($k in $j.offsets.PSObject.Properties.Name) { $state.offsets[$k] = [long]$j.offsets.$k }
          foreach ($k in $j.shots.PSObject.Properties.Name)   { $state.shots[$k] = $true } } catch { Warn "状态文件损坏，重新开始" }
}
function Save-State { $state | ConvertTo-Json -Depth 5 | Set-Content -NoNewline $StateFile }

if (-not $Tag) { $Tag = Get-Date -Format "yyyyMMdd-HHmmss" }
$RD = "$RemoteDir/$Tag"
Info "本次标记 Tag=$Tag  ⇒ 云端 $RD"
Remote-Exec "mkdir -p $RD/logs $RD/screenshots $RD/crash-reports; echo ok" | Out-Null

# 追尾一个日志文件：只传新增字节，追加到远端
function Push-Tail([string]$local, [string]$remoteName) {
    if (-not (Test-Path $local)) { return }
    $off = 0
    if ($state.offsets.ContainsKey($local)) { $off = [long]$state.offsets[$local] }
    $len = (Get-Item $local).Length
    if ($len -lt $off) { $off = 0 }              # 日志被轮转/清空 ⇒ 重来
    if ($len -eq $off) { return }
    $fs = [IO.File]::Open($local, 'Open', 'Read', 'ReadWrite')
    try {
        $fs.Seek($off, 'Begin') | Out-Null
        $buf = New-Object byte[] ($len - $off)
        $read = $fs.Read($buf, 0, $buf.Length)
    } finally { $fs.Close() }
    if ($read -le 0) { return }
    $tmp = Join-Path $env:TEMP ("ci-" + [IO.Path]::GetFileName($local) + ".part")
    if ($read -lt $buf.Length) { $buf = $buf[0..($read-1)] }
    [IO.File]::WriteAllBytes($tmp, $buf)
    $cmdline = "gh codespace ssh -c $Codespace -- ""cat >> $RD/logs/$remoteName"" < ""$tmp"""
    cmd.exe /c $cmdline 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $state.offsets[$local] = [long]$len
        Info ("  日志 +" + [math]::Round(($read/1KB),1) + " KB → logs/$remoteName")
    } else { Warn "  日志推送失败（下一轮重试）" }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
}

# 截图：新增才推；默认先缩到 DownscaleMaxWidth
function Push-Shot([string]$local) {
    $name = [IO.Path]::GetFileName($local)
    $mb = (Get-Item $local).Length / 1MB
    if ($mb -gt $MaxScreenshotMB) { Warn "  跳过超大截图 $name ($([math]::Round($mb,1)) MB)"; $state.shots[$local] = $true; return }
    $send = $local
    if ($DownscaleMaxWidth -gt 0) {
        try {
            Add-Type -AssemblyName System.Drawing
            $img = [System.Drawing.Image]::FromFile($local)
            if ($img.Width -gt $DownscaleMaxWidth) {
                $h = [int]($img.Height * $DownscaleMaxWidth / $img.Width)
                $bmp = New-Object System.Drawing.Bitmap $DownscaleMaxWidth, $h
                $g = [System.Drawing.Graphics]::FromImage($bmp)
                $g.InterpolationMode = 'HighQualityBicubic'; $g.DrawImage($img, 0, 0, $DownscaleMaxWidth, $h)
                $small = Join-Path $env:TEMP ("shot-" + $name)
                $bmp.Save($small, [System.Drawing.Imaging.ImageFormat]::Png)
                $g.Dispose(); $bmp.Dispose(); $img.Dispose()
                $send = $small
            } else { $img.Dispose() }
        } catch { Warn "  缩图失败，按原图推：$name" ; $send = $local }
    }
    Remote-Put $send "$RD/screenshots/$name"
    if ($send -ne $local) { Remove-Item $send -Force -ErrorAction SilentlyContinue }
    $state.shots[$local] = $true
    Info ("  截图 $name → screenshots/$name（" + [math]::Round(((Get-Item $send -ErrorAction SilentlyContinue).Length/1KB),0) + " KB）")
}

function Push-Manifest {
    $m = @{
        tag = $Tag; host = $env:COMPUTERNAME; updated = (Get-Date).ToString("s")
        intervalSec = $IntervalSec; screenshots = $Screenshots
        logs = @(); shots = @(); crashes = @()
    }
    foreach ($f in @("latest.log","debug.log")) {
        $p = Join-Path $LogDir $f
        if (Test-Path $p) { $m.logs += @{ name = $f; localBytes = (Get-Item $p).Length; remote = "$RD/logs/$f"; pushedBytes = [long]($state.offsets[$p]) } }
    }
    $sd = Join-Path $ClientRoot "screenshots"
    if (Test-Path $sd) { $m.shots = @(Get-ChildItem $sd -Filter *.png -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 20 | ForEach-Object { @{ name = $_.Name; time = $_.LastWriteTime.ToString("s"); mb = [math]::Round($_.Length/1MB,1) } }) }
    $cd = Join-Path $ClientRoot "crash-reports"
    if (Test-Path $cd) { $m.crashes = @(Get-ChildItem $cd -Filter *.txt -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 5 | ForEach-Object { @{ name = $_.Name; time = $_.LastWriteTime.ToString("s") } }) }
    $mf = Join-Path $env:TEMP "ci-manifest.json"
    $m | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $mf
    Remote-Put $mf "$RD/manifest.json"
    Remove-Item $mf -Force -ErrorAction SilentlyContinue
}

Info "开始监视 $ClientRoot（每 ${IntervalSec}s 一轮；Ctrl-C 停）"
$myPid = $PID
if (-not (Test-Path $PidFile) -or $Once) { $myPid | Set-Content $PidFile }

$round = 0
do {
    $round++
    try {
        Push-Tail (Join-Path $LogDir "latest.log") "latest.log"
        Push-Tail (Join-Path $LogDir "debug.log")  "debug.log"
        if ($LiveLog -and (Test-Path (Join-Path $LogDir "latest.log"))) {
            $tail = Get-Content (Join-Path $LogDir "latest.log") -Tail 80 -ErrorAction SilentlyContinue
            $lt = Join-Path $env:TEMP "ci-live.log"; $tail | Set-Content -Encoding UTF8 $lt
            Remote-Put $lt "$RD/live.log"; Remove-Item $lt -Force -ErrorAction SilentlyContinue
        }
        $cd = Join-Path $ClientRoot "crash-reports"
        if (Test-Path $cd) {
            foreach ($f in Get-ChildItem $cd -Filter *.txt -ErrorAction SilentlyContinue) {
                if ($state.shots.ContainsKey($f.FullName)) { continue }
                if ($f.LastWriteTime -lt (Get-Date).AddDays(-2)) { continue }
                Remote-Put $f.FullName "$RD/crash-reports/$($f.Name)"
                $state.shots[$f.FullName] = $true
                Info ("  崩溃报告 $($f.Name)")
            }
        }
        if ($Screenshots -ne "off") {
            $sd = Join-Path $ClientRoot "screenshots"
            if (Test-Path $sd) {
                foreach ($f in Get-ChildItem $sd -Filter *.png -ErrorAction SilentlyContinue | Sort-Object LastWriteTime) {
                    if ($Screenshots -eq "new" -and $state.shots.ContainsKey($f.FullName)) { continue }
                    Push-Shot $f.FullName
                }
            }
        }
        Push-Manifest
        Save-State
    } catch { Warn "本轮异常：$($_.Exception.Message)" }
    if (-not $Once) { Start-Sleep -Seconds $IntervalSec }
} while (-not $Once)

Info "完成（共 $round 轮）"
if ($Once) { Remove-Item $PidFile -Force -ErrorAction SilentlyContinue }
