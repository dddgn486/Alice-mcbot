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

# ---------- 定位最新判决 ----------
$verdictFile = Join-Path $ServerDir "headless-result.txt"
if (-not (Test-Path $verdictFile)) {
    $alt = Get-ChildItem (Join-Path $Repo "run\headless-logs") -Filter "*headless-result.txt" -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($alt) { $verdictFile = $alt.FullName } else { Die "找不到判决文件（$ServerDir\headless-result.txt 和 run\headless-logs 都没有）⇒ 先跑 client-agent.cmd -Headless core" 3 }
}
$rawVerdict = (Get-Content -LiteralPath $verdictFile -Raw -Encoding UTF8)
$verdict = ($rawVerdict -split "`r?`n" | Where-Object { $_ -match '^\s*verdict=' } | Select-Object -First 1)
if (-not $verdict) { $verdict = ($rawVerdict.Trim() -split "`r?`n")[0] }

# ---------- 步级 SUMMARY（逐字；红集合从它数出来） ----------
$staleness = ""
$newest = Get-ChildItem (Join-Path $Repo "run\headless-logs") -Filter "*-latest.log" -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
$summary = ""
if ($newest) {
    $m = Select-String -LiteralPath $newest.FullName -Pattern "Regression\] SUMMARY" -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($m) { $summary = $m.Line }
    $staleness = "归档日志：" + $newest.Name + "（" + $newest.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss") + "）"
}
$reds = @()
if ($summary) {
    $reds = @(($summary -split " ") | Where-Object { $_ -match "^[a-z_]+=FAIL$" } | ForEach-Object { ($_ -split "=")[0] } | Sort-Object -Unique)
}
$passCount = 0; $stepCount = 0
if ($summary) {
    $steps = @(($summary -split " ") | Where-Object { $_ -match "^[a-z_]+=[A-Z]+$" -and $_ -notmatch "^PROFILE=" })
    $stepCount = $steps.Count
    $passCount = @($steps | Where-Object { $_ -match "=PASS$" }).Count
}

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
$javaLine = "（没找到）"
$jc = Get-Command java -ErrorAction SilentlyContinue
if ($jc) { $javaLine = ((& $jc.Source -version 2>&1 | Out-String).Trim() -split "`r?`n")[0] }
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
$body += "| $staleness | |"
$body += ""
if ($summary) { $body += "## 步级 SUMMARY（逐字，别改）"; $body += ""; $body += '```'; $body += $summary; $body += '```' }
else { $body += '⚠️ 没读到 SUMMARY 行 ⇒ 这一轮**没有步级读数**，别把它当成"跑过了"。'; }
$body += ""
$body += "> 由 ``client-agent.cmd -Report`` 生成（不算 LLM）。原始判决文件：``$verdictFile``"
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
