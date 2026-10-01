<#
.SYNOPSIS
  把本机最近一轮无头电池的**判决**写成一封信，投到云端信箱（`~/bus/to-cloud/`）。

.DESCRIPTION
  为什么需要它（2026-10-02 用户裁定）：回家后主工作流在**云端**，而云端**不在**设备的虚拟局域网里
  ⇒ 主工作流 **ssh 不到这台设备** ⇒ "结果怎么回到云端"只能靠**设备主动投递**。
  本脚本就是那一步：它**不算 LLM**、幂等、失败也不破坏任何东西。

  信里带的东西（都是**可核对读数**，不是散文）：
    · 判决（verdict / exit / 用时）· 步级 SUMMARY 行（逐字）
    · **红集合**，以及它是否 ⊆ `docs/EXPECTED_REDS.md`（"红的步必须有主"那一套）
    · 本轮实际用的 jar / Java / 机器名 —— 少了这三样，云端无法判断"这是哪一版跑出来的"

.PARAMETER Codespace
  codespace 名；空 = 读 `%USERPROFILE%\.alice-client.json`。

.PARAMETER ServerDir
  服务端目录（`headless-result.txt` 在这儿）。

.PARAMETER Repo
  Alice 仓库；空 = 从脚本位置往上找 `build.gradle`（找 `docs/EXPECTED_REDS.md` 与 `run/headless-logs`）。

.PARAMETER DryRun
  只打印会投什么，不上传。

.EXAMPLE
  client-agent.cmd -Report
#>
[CmdletBinding()]
param(
    [string]$Codespace = "",
    [string]$ServerDir = "D:\JAVA_projects\alice-server",
    [string]$Repo = "",
    [switch]$DryRun,
    [switch]$Quiet
)
$ErrorActionPreference = "Continue"
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
function Say($m)  { if (-not $Quiet) { Write-Host ("[report] " + $m) } }
function Die($m,$c=5) { Write-Host ("[report] 错误：" + $m) -ForegroundColor Red; exit $c }

# ---------- 定位仓库 ----------
if (-not $Repo) {
    $c = $PSScriptRoot
    while ($c) {
        if (Test-Path (Join-Path $c "build.gradle")) { $Repo = $c; break }
        $p = Split-Path $c -Parent
        if ($p -eq $c) { break }
        $c = $p
    }
}
if (-not $Repo) { Die "没找到 Alice 仓库（找 build.gradle）⇒ 用 -Repo 指定" }

# ---------- ⭐ 定位"本轮读数源"：**一个文件**里同时拿判决与步级读数 ----------
# ⚠️ 实测教训（2026-10-02）：判决与 SUMMARY 若从**两个不同文件**读，会产出
#    「verdict=FAIL 但红集合=无」这种**自相矛盾的回执** —— 正是 `silent-measurement-failure`
#    那一族（同一个量有多个副本，我读了甲、判定器用乙）。⇒ 一律**单一真相源**。
$archDir = Join-Path $Repo "run\headless-logs"
$srcLog = $null
if (Test-Path $archDir) {
    foreach ($c in (Get-ChildItem $archDir -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
        if ($c.Name -like "*-headless-result.txt") { continue }          # 判决文件本身没有 SUMMARY
        if ($c.Name -like "headless-receipt-*") { continue }             # ⛔ 别读自己写的回执（自指循环！）
        if (Select-String -LiteralPath $c.FullName -Pattern "Regression\] SUMMARY" -ErrorAction SilentlyContinue) { $srcLog = $c; break }
    }
}
$summary = ""
if ($srcLog) {
    $m = Select-String -LiteralPath $srcLog.FullName -Pattern "Regression\] SUMMARY" -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($m) {
        # ⭐ 必须**先剥 ANSI 颜色转义**：Forge 的日志带 `\e[32m…\e[m`，而锚定正则 `^…$`
        #    在带转义时会**静默匹配不上** ⇒ 实测产出过「verdict=FAIL 但红集合=空」的自相矛盾回执。
        $summary = ($m.Line -replace "\x1b\[[0-9;]*[A-Za-z]", "") -replace "\x1b\][^\x07]*\x07", ""
    }
}
$verdictSrc = if ($srcLog) { "读数源（判决＋步级同一文件）：归档日志 " + $srcLog.Name + "（" + $srcLog.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss") + "）" }
              else { "读数源：**无归档日志**" }

# 判决：优先取 SUMMARY 行尾的 `→ PASS|FAIL|DEGRADED`（与步级读数**同源**）
$verdict = ""
if ($summary -match "→\s*(PASS|FAIL|DEGRADED)\s*$") { $verdict = "verdict=" + $Matches[1] }

# 独立副本 = 判决文件（`<服务端目录>\headless-result.txt` 或归档里的 `*-headless-result.txt`）；
# ⛔ 只在两者**不一致**时出声，别静默挑一个。
$vfile = Join-Path $ServerDir "headless-result.txt"
if (-not (Test-Path $vfile) -and (Test-Path $archDir)) {
    $a = Get-ChildItem $archDir -Filter "*headless-result.txt" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($a) { $vfile = $a.FullName }
}
$vfileVerdict = ""
if (Test-Path $vfile) {
    $raw = Get-Content -LiteralPath $vfile -Raw -Encoding UTF8
    $vfileVerdict = (($raw -split "`r?`n" | Where-Object { $_ -match '^\s*verdict=' } | Select-Object -First 1)).Trim()
    if (-not $vfileVerdict) { $vfileVerdict = ($raw.Trim() -split "`r?`n")[0].Trim() }
}
$mismatch = ""
if ($verdict -and $vfileVerdict -and ($vfileVerdict -notmatch [regex]::Escape(($verdict -replace '^verdict=','')))) {
    $mismatch = "⚠️ **两个副本不一致**：归档 SUMMARY 说 ``$verdict``，判决文件 ``$vfile` 说 ``$vfileVerdict`` ⇒ ⛔ 不要当结论，回去看原始日志"
}
if (-not $verdict) { $verdict = if ($vfileVerdict) { $vfileVerdict + "（仅判决文件，没有步级 SUMMARY）" } else { "verdict=<未读到>" } }

# ---------- 步级读数：步数、PASS 数、**红集合**（全部从上面那**同一个** $summary 里数） ----------
$reds = @()
$passCount = 0; $stepCount = 0
if ($summary) {
    $tokens = @(($summary -split " ") | Where-Object { $_ -match "^[a-z_]+=[A-Z]+$" -and $_ -notmatch "^PROFILE=" })
    $stepCount = $tokens.Count
    $passCount = @($tokens | Where-Object { $_ -match "=PASS$" }).Count
    $reds = @($tokens | Where-Object { $_ -match "=FAIL$" } | ForEach-Object { ($_ -split "=")[0] } | Sort-Object -Unique)
}
if ($summary -and $stepCount -eq 0) { Write-Host "[report] ⚠️ 读到 SUMMARY 但数不出任何步 —— ⛔ 别当结论，先查读数源" -ForegroundColor Yellow }

# ---------- 红集合是否 ⊆ 已登记（"红的步必须有主"） ----------
$registered = @()
$erPath = Join-Path $Repo "docs\EXPECTED_REDS.md"
if (Test-Path $erPath) {
    foreach ($ln in (Get-Content -LiteralPath $erPath -Encoding UTF8)) {
        if ($ln -match '^\|\s*`([a-zA-Z0-9_]+)`') { $registered += $Matches[1] }
    }
    $registered = @($registered | Sort-Object -Unique)
}
$unregistered = @($reds | Where-Object { $registered -notcontains $_ })

# ---------- 本轮实际用的 jar / Java / 机器（云端判断"哪一版"必需） ----------
$jarLine = "（没找到）"
foreach ($cand in @((Join-Path $Repo "build\libs"), (Join-Path $ServerDir "mods"))) {
    if (-not (Test-Path $cand)) { continue }
    $j = Get-ChildItem -LiteralPath $cand -Filter "alice-*.jar" -ErrorAction SilentlyContinue |
         Where-Object { $_.Name -notlike "*.bak.*" -and $_.Name -notlike "*sources*" } |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($j) {
        $jarLine = "$($j.Name) · $([math]::Round($j.Length/1MB,2)) MB · 改动 $($j.LastWriteTime.ToString('yyyy-MM-dd HH:mm')) · sha256=" + (Get-FileHash -LiteralPath $j.FullName -Algorithm SHA256).Hash.Substring(0,16).ToLower()
        break
    }
}
# ⭐ Java 必须从**读数源日志**里取（那是**真跑电池的那个**）；
#    ⛔ 别用 `Get-Command java` —— 那只是**本机 PATH 上**的 java（实测本机 PATH 是 16，而电池跑的是 17）⇒ 会误导。
$javaLine = "（日志里没读到）"
if ($srcLog) {
    $jm = Select-String -LiteralPath $srcLog.FullName -Pattern 'java version "?([0-9][0-9._]*)"?' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jm -and $jm.Matches.Count -gt 0) { $javaLine = $jm.Matches[0].Groups[1].Value + "（来自本轮日志，即真跑的那个）" }
}
if ($javaLine -eq "（日志里没读到）") {
    $jc = Get-Command java -ErrorAction SilentlyContinue
    if ($jc) { $javaLine = ((& $jc.Source -version 2>&1 | Out-String).Trim() -split "`r?`n")[0] + " ⚠️（这是**本机 PATH** 上的，未必是本轮用的）" }
}
$redLine = if ($reds.Count -eq 0) { "无（全绿）" } else { ($reds -join " ") }

# ---------- 写信 ----------
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$host_ = $env:COMPUTERNAME
$body = @()
$body += "# 无头电池回执（$host_ / $stamp）"
$body += ""
$body += "| 项 | 读数 |"
$body += "|---|---|"
$body += "| 判决 | ``$verdict`` |"
$body += "| 步数 | $passCount / $stepCount PASS |"
$body += "| **红集合** | ``$redLine`` |"
$body += "| 红是否⊆已登记 | $(if ($reds.Count -eq 0) { '不适用（无红）' } elseif ($unregistered.Count -eq 0) { '**是**（全部有主）' } else { '**否** —— 未登记：' + ($unregistered -join ' ') }) |"
$body += "| 用的 jar | $jarLine |"
$body += "| 用的 Java | ``$javaLine`` |"
$body += "| 读数源 | $verdictSrc |"
if ($mismatch) { $body += ""; $body += $mismatch }
$body += ""
if ($summary) { $body += "## 步级 SUMMARY（逐字，别改）"; $body += ""; $body += '```'; $body += $summary; $body += '```' }
else { $body += '⚠️ 没读到 SUMMARY 行 ⇒ 这一轮**没有步级读数**，别把它当成"跑过了"。'; }
$body += ""
$body += "> 由 ``client-agent.cmd -Report`` 生成（不算 LLM）。$verdictSrc"
$text = ($body -join "`r`n")

$outDir = Join-Path $Repo "run\headless-logs"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
$localName = "headless-receipt-$stamp.md"
$localPath = Join-Path $outDir $localName
$enc = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($localPath, $text, $enc)
Say "信已写好：$localPath"
Say "判决：$verdict · 红集合：$redLine"
if ($unregistered.Count -gt 0) { Say "⚠️ 有未登记的红：$($unregistered -join ' ') —— 云端要按"未登记"处理" }

if ($DryRun) { Say "（DryRun）不上传"; exit 0 }

# ---------- 投递 ----------
$cfgPath = Join-Path $HOME ".alice-client.json"
if (-not $Codespace -and (Test-Path $cfgPath)) {
    try { $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json; if ($cfg.codespace) { $Codespace = [string]$cfg.codespace } } catch { }
}
if (-not $Codespace) { $Codespace = "alice-cloud-01-q7wr4q564jp6c997g" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Die "没有 gh ⇒ 装 GitHub CLI 或手动拷 $localPath 到云端 ~/bus/to-cloud/" }
if (-not $env:HTTPS_PROXY) {
    try {
        $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
        if ($reg -and ([int]$reg.ProxyEnable -eq 1) -and $reg.ProxyServer) {
            $srv = [string]$reg.ProxyServer
            if ($srv -notmatch '^https?://') { $srv = "http://$srv" }
            $env:HTTPS_PROXY = $srv; $env:HTTP_PROXY = $srv
        }
    } catch { }
}
$remote = "/home/vscode/bus/to-cloud/$localName"
& gh codespace cp -e $localPath "remote:$remote" -c $Codespace 2>&1 | ForEach-Object { Say ("  " + $_) }
if ($LASTEXITCODE -eq 0) { Say "已投递：$remote" ; exit 0 }
else { Say "投递失败（exit=$LASTEXITCODE）⇒ 信还在本机：$localPath（可手动拷到云端 ~/bus/to-cloud/）"; exit 1 }
