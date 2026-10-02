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


# ---------- ⭐ 编码感知读取（管家 2026-10-02 实测证实的真缺陷）----------
# 为什么必须有：归档日志在 Windows 上常是 **cp936(GBK)**，而 `Select-String` 的**默认解码**
# 会把 `→`(U+2192) 读成 **U+FFFD** ⇒ 尾锚正则 `→\s*(PASS|FAIL)\s*$` **静默匹配不上** ⇒
# SUMMARY 读成空 ⇒ 回执退化成"只有判决、没有步级读数"。⇒ **一律自己按显式编码读文本**。
function Read-TextSmart([string]$path) {
    $utf8 = New-Object System.Text.UTF8Encoding($false, $true)   # throwOnInvalidBytes
    try {
        $t = [IO.File]::ReadAllText($path, $utf8)
        if ($t -notmatch "\uFFFD") { return @{ Text = $t; Enc = "utf8" } }
    } catch { }
    try {
        $t936 = [IO.File]::ReadAllText($path, [Text.Encoding]::GetEncoding(936))
        return @{ Text = $t936; Enc = "cp936" }
    } catch { }
    return @{ Text = ([IO.File]::ReadAllText($path, [Text.Encoding]::Default)); Enc = "default" }
}
# 从**已解码文本**里取最后一条 SUMMARY（不再用 Select-String ⇒ 绕开它的解码）
function Get-LastSummaryLine([string]$text) {
    if (-not $text) { return "" }
    $lines = @($text -split "\r?\n" | Where-Object { $_ -match "Regression\] SUMMARY" })
    if ($lines.Count -eq 0) { return "" }
    $l = $lines[-1]
    # 剥 ANSI 颜色转义（Forge 日志带 `\e[32m…\e[m`，锚定正则会静默失配）
    return ($l -replace "\x1b\[[0-9;]*[A-Za-z]", "") -replace "\x1b\][^\x07]*\x07", ""
}

# ---------- ⭐ 定位"本轮读数源"：**一个文件**里同时拿判决与步级读数 ----------
# ⚠️ 实测教训（2026-10-02）：判决与 SUMMARY 若从**两个不同文件**读，会产出
#    「verdict=FAIL 但红集合=无」这种**自相矛盾的回执** —— 正是 `silent-measurement-failure`
#    那一族（同一个量有多个副本，我读了甲、判定器用乙）。⇒ 一律**单一真相源**。
$archDir = Join-Path $Repo "run\headless-logs"
# ⭐ 选源规则（管家 2026-10-02 实测修正）：
#   ① 按**时间戳前缀分组**（同一次运行归档成多个文件：`<TS>-latest.log` / `<TS>-headless-stdout-<TS>.log` /
#      `<TS>-headless-result.txt`）；② 取**最新那一组**；③ 组内**优先 `-latest.log`**
#      —— 因为 stdout 重定向件的 LastWriteTime 更晚，原先"按时间倒序取第一个命中的"会**选错文件**。
$srcLog = $null; $srcText = ""; $srcEnc = ""
if (Test-Path $archDir) {
    $cands = @(Get-ChildItem $archDir -File -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notlike "*-headless-result.txt" -and $_.Name -notlike "headless-receipt-*" })
    $groups = @{}
    foreach ($c in $cands) {
        $key = if ($c.Name -match '^(\d{8}-\d{6})') { $Matches[1] } else { "zzz_" + $c.LastWriteTime.ToString("yyyyMMddHHmmss") }
        if (-not $groups.ContainsKey($key)) { $groups[$key] = New-Object System.Collections.ArrayList }
        [void]$groups[$key].Add($c)
    }
    foreach ($key in ($groups.Keys | Sort-Object -Descending)) {
        $g = @($groups[$key])
        $pref = @($g | Where-Object { $_.Name -like "*-latest.log" })
        if ($pref.Count -eq 0) { $pref = @($g | Where-Object { $_.Name -notlike "*-headless-stdout-*" }) }
        if ($pref.Count -eq 0) { $pref = $g }
        foreach ($c in $pref) {
            $r = Read-TextSmart $c.FullName
            $ln = Get-LastSummaryLine $r.Text
            if ($ln) { $srcLog = $c; $srcText = $r.Text; $srcEnc = $r.Enc; break }
        }
        if ($srcLog) { break }
    }
}
$summary = Get-LastSummaryLine $srcText
$verdictSrc = if ($srcLog) { "读数源（判决＋步级同一文件）：归档日志 " + $srcLog.Name + "（" + $srcLog.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss") + "，解码=" + $srcEnc + "）" }
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
    # ⛔ 防 null：管家实测这里对空表达式调 `.Trim()` 直接崩（`You cannot call a method on a null-valued expression`）
    $raw = (Read-TextSmart $vfile).Text
    if ($raw) {
        $first = @($raw -split "`r?`n" | Where-Object { $_ -match '^\s*verdict=' } | Select-Object -First 1)
        if ($first.Count -gt 0 -and $first[0]) { $vfileVerdict = ([string]$first[0]).Trim() }
        if (-not $vfileVerdict) {
            $head = @($raw.Trim() -split "`r?`n" | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1)
            if ($head.Count -gt 0 -and $head[0]) { $vfileVerdict = ([string]$head[0]).Trim() }
        }
    }
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
        # ⛔ `Get-FileHash` 在此机**可能 MISSING**（管家实测根因：PSModulePath 把
        #    `Program Files\PowerShell\7\Modules` 排在 5.1 自带模块目录**之前** ⇒ Import-Module
        #    解析到 PS7 那份 7.0.0.0，其 ExportedFunctions **不含** Get-FileHash）
        #    ⇒ 一律走 .NET 兜底，别依赖那个 cmdlet。
        $hash = $null
        try { if (Get-Command Get-FileHash -ErrorAction SilentlyContinue) { $hash = (Get-FileHash -LiteralPath $j.FullName -Algorithm SHA256).Hash } } catch { }
        if (-not $hash) {
            try { $sha = [System.Security.Cryptography.SHA256]::Create(); $fs = [IO.File]::OpenRead($j.FullName); $hash = ([BitConverter]::ToString($sha.ComputeHash($fs)) -replace '-',''); $fs.Close(); $sha.Dispose() } catch { }
        }
        $hashShort = if ($hash) { $hash.Substring(0,16).ToLower() } else { "hash-unavailable" }
        $jarLine = "$($j.Name) · $([math]::Round($j.Length/1MB,2)) MB · 改动 $($j.LastWriteTime.ToString('yyyy-MM-dd HH:mm')) · sha256=" + $hashShort
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
# ⚠️ 别在这里 Die：**git 路径不需要 gh**（本机 `~/.ssh` 被挡 ⇒ gh 才是有问题的那条）。
#    没有 gh 只是少一条兜底，不该把已经写好的信判死。
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Say "（没有 gh ⇒ 只能走 git 路径）" }
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
# ⭐ 优先走 **git**（2026-10-02 实测后改）：本机 `~/.ssh` 的**内容读被系统层挡住**
#    （元数据正常、非 OneDrive 占位符；连 `cmd /c type` 都挂、`cmd /c copy` exit=1）
#    ⇒ `gh codespace cp`（要用那把密钥）**在本机是废的**。
#    git 只需 PAT + 代理，实测可用（探针提交已到远端）。⇒ git 为主、gh cp 为辅、都失败就落盘留痕。
$branch = "steward/inbox"
$blobName = "steward-inbox/$localName"
$gitOk = $false
Push-Location $Repo
try {
    # ⭐ **完全不碰工作树**：`git hash-object` + `mktree` + `commit-tree` 造一个只含这封信的提交，
    #    再 `push <commit>:refs/heads/<branch>`。为什么必须这样：
    #    本机工作树**是脏的**（管家有自己的未提交改动）⇒ `git checkout -B` 会直接失败或被拒；
    #    而且我们**绝不想**顺手把别的改动带进提交。
    $blob = (& git hash-object -w -- $localPath 2>$null | Out-String).Trim()
    if ($blob -match '^[0-9a-f]{40}$') {
        # ⭐⭐ 2026-10-02 本机实测修的两处（上一版**必然投不出去**，且报的是假原因）：
        #  (a) `mktree` **不接受带斜杠的路径** —— 逐字 `fatal: path steward-inbox/<name> contains slash`
        #      （exit=128）。而 `$blobName` 恰恰是 `steward-inbox/<file>` ⇒ 恒失败。
        #      ⇒ 必须**建嵌套树**：先建内层目录树（名 = 文件名），再建根树（名 = steward-inbox）。
        #  (b) 往原生命令喂 stdin 绝不能用字符串管道：PowerShell 5.1 追加的是 **CRLF**，
        #      而 `mktree` 逐行解析 ⇒ `\r` 被**吃进条目名**（实测 ls-tree 出 `"steward-inbox\r/file\r"`）。
        #      ⇒ 一律 `mktree -z` + **NUL 结尾的字节**走 stdin。
        #  另：`hash-object` 的 stderr **不能并进 stdout**（`2>&1`）—— 本仓 `core.autocrlf=true`
        #      会打一条 CRLF 警告，而 PS 5.1 把它变成 error record 拼在哈希前面（实测 566 字符），
        #      于是 `^[0-9a-f]{40}$` 校验恒失败、还误报成「仓库不可用？」。故这里用 `2>$null`。
        function Invoke-MktreeZ([string]$entry) {
            $psi = New-Object System.Diagnostics.ProcessStartInfo
            $psi.FileName = (Get-Command git).Source
            $psi.Arguments = "mktree -z"
            $psi.RedirectStandardInput = $true
            $psi.RedirectStandardOutput = $true
            $psi.RedirectStandardError = $true
            $psi.UseShellExecute = $false
            $psi.WorkingDirectory = $Repo
            $proc = [System.Diagnostics.Process]::Start($psi)
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($entry)
            $proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
            $proc.StandardInput.BaseStream.WriteByte([byte]0)
            $proc.StandardInput.BaseStream.Flush()
            $proc.StandardInput.Close()
            $so = $proc.StandardOutput.ReadToEnd()
            $se = $proc.StandardError.ReadToEnd()
            $proc.WaitForExit()
            if ($se) { Say ("  mktree stderr: " + $se.Trim()) }
            return $so.Trim()
        }
        $inner = Invoke-MktreeZ ("100644 blob $blob`t$localName")
        if ($inner -match '^[0-9a-f]{40}$') {
            $tree = Invoke-MktreeZ ("040000 tree $inner`tsteward-inbox")
        } else { $tree = "" }
        if ($tree -match '^[0-9a-f]{40}$') {
            # 父提交：优先远端分支（本地可能没有），没有就当首次提交
            & git fetch -q origin $branch 2>&1 | Out-Null
            $parent = (& git rev-parse -q --verify "refs/remotes/origin/$branch" 2>&1 | Out-String).Trim()
            $msg = "receipt: $localName / $verdict"
            if ($parent -match '^[0-9a-f]{40}$') { $commit = (& git commit-tree $tree -p $parent -m $msg 2>$null | Out-String).Trim() }
            else { $commit = (& git commit-tree $tree -m $msg 2>$null | Out-String).Trim() }
            if ($commit -match '^[0-9a-f]{40}$') {
                # ⭐ push 的凭证（2026-10-02 本机实测三轮才定案）：
                #  本机 git 的凭证链 = 系统级 `credential.helper=manager`（**Git Credential Manager**）
                #  ＋ 仓库级 `credential.helper=store`，而 store 里没有可用凭据 ⇒ **GCM 弹 GUI 登录窗**
                #  （用户实测看到 "Connect to GitHub"），无人值守时它会等约 20 s 才
                #  `fatal: User cancelled dialog` ⇒ 投递失败/卡死。
                #  ⛔ 不要用 `-c credential.helper=!<Windows绝对路径>`：git 用 **bash** 执行 `!`-helper，
                #     反斜杠被吃掉（实测 `C:UsersFb...: command not found`）⇒ 照样落到 GCM。
                #  ✅ 用 **GIT_ASKPASS + cmd 包装器**（git 直接 exec，不经 shell，无路径反斜杠问题），
                #     并把 `credential.credentialStore=` 置空以**断掉 GCM 的触发条件**。
                #  token 只经**环境变量**传入：不写磁盘明文、不进 argv（argv 会留在进程表里）。
                $helperDir = Join-Path $env:TEMP "alice-cred"
                $askPassCmd = Join-Path $helperDir "askpass.cmd"
                $askPassPs1 = Join-Path $helperDir "askpass.ps1"
                $tokFile = Join-Path $HOME ".gh-token"
                $token = ""
                if (Test-Path $tokFile) { try { $token = (Get-Content -LiteralPath $tokFile -Raw).Trim() } catch { $token = "" } }
                if ($token) {
                    New-Item -ItemType Directory -Force -Path $helperDir | Out-Null
                    # askpass.ps1 只回 token；%~1 保留 prompt 参数的引号（cmd 侧）
                    $apBody = "param([string]`$Prompt)`r`nif (`$Prompt -match 'Username') { Write-Output 'dddgn486' } else { Write-Output `$env:ALICE_GIT_TOKEN }`r`n"
                    [System.IO.File]::WriteAllText($askPassPs1, $apBody, (New-Object System.Text.UTF8Encoding($false)))
                    $apCmdBody = "@echo off`r`npowershell -NoProfile -ExecutionPolicy Bypass -File `"%~dp0askpass.ps1`" %~1`r`n"
                    [System.IO.File]::WriteAllText($askPassCmd, $apCmdBody, (New-Object System.Text.ASCIIEncoding))
                    $env:ALICE_GIT_TOKEN = $token
                    $env:GIT_ASKPASS = $askPassCmd
                    $env:GIT_TERMINAL_PROMPT = "0"
                    & git -c credential.credentialStore= -c credential.helper= push -q origin "$commit`:refs/heads/$branch" 2>$null
                    $env:ALICE_GIT_TOKEN = $null
                    $env:GIT_ASKPASS = $null
                    $env:GIT_TERMINAL_PROMPT = $null
                } else {
                    Say "读不到 $tokFile ⇒ 退回仓库自带凭证配置（可能弹登录窗）"
                    & git push -q origin "$commit`:refs/heads/$branch" 2>$null
                }
                if ($LASTEXITCODE -eq 0) {
                    $gitOk = $true
                    Say "已用 git 投递：分支 $branch（提交 $($commit.Substring(0,8))，内容 $blobName）"
                    Say "云端取法：git fetch github '$branch`:refs/remotes/github/$branch' ; 然后看 $blobName"
                } else { Say "git push 失败（exit=$LASTEXITCODE）⇒ 再试 gh cp" }
            } else { Say "commit-tree 失败 ⇒ 再试 gh cp" }
        } else { Say "mktree 失败 ⇒ 再试 gh cp" }
    } else { Say "hash-object 失败（仓库不可用？）⇒ 再试 gh cp" }
} catch { Say ("git 路径异常：" + $_.Exception.Message) }
Pop-Location
if ($gitOk) { exit 0 }

# 兜底：老的 gh codespace cp（只有密钥可读时才走得通）
if (Get-Command gh -ErrorAction SilentlyContinue) {
    $remote = "/home/vscode/bus/to-cloud/$localName"
    & gh codespace cp -e $localPath "remote:$remote" -c $Codespace 2>&1 | ForEach-Object { Say ("  " + $_) }
    if ($LASTEXITCODE -eq 0) { Say "已投递：$remote" ; exit 0 }
}
Say "两条路都没成 ⇒ 信还在本机：$localPath"
exit 1
