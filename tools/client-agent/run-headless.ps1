<#
.SYNOPSIS
  Alice 无头回归电池的 **Windows 原生驱动**（不需要 WSL / bash）。

.DESCRIPTION
  为什么有它（用户 2026-10-01 定：无头测试在**自有设备**上跑）：
    `tools/headless-battery.sh` 是 bash 脚本（用 pgrep/mkdir/cp/seq 等）⇒ 新设备若没装 WSL 就跑不了。
    而真正的机制只有一行（`tools/headless-battery.sh:371`，逐字）：
        java -Xmx3G "-Dalice.headless.battery=$MODE" "@user_jvm_args.txt" "@libraries/…/unix_args.txt" nogui
    判决落在 `<服务端目录>/headless-result.txt`（三份之一，另两份是 stdout 与 logs/latest.log）。
  ⇒ 本脚本把这套机制**原样**搬到 PowerShell：`unix_args.txt` → `win_args.txt`，其余一致。

  它做九件事（全部幂等，可重复跑）：
    ① 检查前提：java / forge 安装器 / 客户端模组目录 / 客户端存档
    ② `--install`：装 Forge 生产服务端（缺才算）
    ③ 建世界母本 `world-pristine`（从客户端存档**复制一份**，缺才算；⛔ 不动原存档）
    ④ 每轮重置世界：`world` ← `world-pristine`
    ⑤ 钉死 `server.properties`（⭐ difficulty=peaceful 等**先把文件建出来再钉** —— 见 CLOUD_MIGRATION §14 缺陷 2）
    ⑥ 装模组：Alice jar + 客户端那套（除 alice 自己/备份/客户端专属）
    ⑦ 起服务端（带 `-Dalice.headless.battery=<MODE>`），**轮询** headless-result.txt
    ⑧ 解析判决（PASS/FAIL/DEGRADED/无判决）并打印 `Regression] SUMMARY` 那行
    ⑨ 收尾：进程还在就终止；日志归档到 `<Repo>\run\headless-logs\`

  退出码（与 bash 版同口径）：0=PASS 1=FAIL 2=DEGRADED 3=没等到判决 4=起不来 5=脚本自身/解析问题

.PARAMETER Mode
  跑哪些步：`core`（默认）· `full` · `single:<步名>` · `list_modules`

.PARAMETER ServerDir
  服务端目录；默认 `D:\JAVA_projects\alice-server`。

.PARAMETER ClientRoot
  固定客户端目录（提供 `mods\` 与 `saves\新的世界`）；默认 `D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`。

.PARAMETER Install
  只做"一次性准备"：装服务端 + 建母本，然后退出（不跑电池）。

.PARAMETER TimeoutSec
  等判决的超时；默认 900（本地 CORE ≈ 250 s，留足余量）。

.PARAMETER KeepWorld
  保留上一轮的世界（不复位）。查现场用。

.EXAMPLE
  run-headless.ps1 -Install                 # 新设备第一次：装服务端 + 建母本
  run-headless.ps1                          # 跑 CORE
  run-headless.ps1 -Mode single:capability_gate
#>
[CmdletBinding()]
param(
    [string]$Mode = "core",
    [string]$ServerDir = "D:\JAVA_projects\alice-server",
    [string]$ClientRoot = "D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10",
    [string]$Repo = "",
    [string]$ForgeVersion = "",
    [string]$JavaExe = "",        # 手动指定 java.exe（默认自动挑：优先恰好 17）
    [int]$PreferredJavaMajor = 17, # ⭐ MC 1.20.1 的目标 Java（本机 bash 电池用的就是 17）
    [string]$ProxyUrl = "",        # 本地代理（如 http://127.0.0.1:7897）；空 = 从系统代理读
    [switch]$Install,
    [int]$TimeoutSec = 900,
    [int]$MaxHeapMB = 3072,
    [switch]$KeepWorld,
    [switch]$NoMods
)
$ErrorActionPreference = "Continue"
# ⭐ 自己刷新 PATH：Java 刚装完只在**新终端**可见（同 client-agent 的理由）
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')

function Say($m)  { Write-Host ("[headless] " + $m) }
function Warn($m) { Write-Host ("[headless] !! " + $m) -ForegroundColor Yellow }
function Die($m, $code) { Write-Host ("[headless] 错误：" + $m) -ForegroundColor Red; exit $code }

# ---------- 仓库位置（用于 find Alice jar / 归档日志）----------
if (-not $Repo) {
    foreach ($c in @("D:\JAVA_projects\alice", "C:\JAVA_projects\alice", (Join-Path $PSScriptRoot ".."))) {
        if (Test-Path (Join-Path $c "build.gradle")) { $Repo = (Resolve-Path $c).Path; break }
    }
}
if (-not $Repo) { Warn "没自动找到 Alice 仓库（找 build.gradle）⇒ 用 -Repo 指定；本轮不装 Alice jar" }

# ---------- ① 前提：⭐ 必须挑 Java 17+（不许盲信 PATH 上的 java）----------
# 为什么（2026-10-02 本机冒烟实测，逐字证据）：本机 PATH 上的 java 是 **16.0.2**，
# 服务端启动日志直接写 `ModLauncher … java version 16.0.2` ⇒ **Forge 1.20.1 要 17+** ⇒
# 启动即死、**永远不会写出判决**（症状：进程起来了、日志只有 ModLauncher 一行、无 verdict）。
# ⇒ 判据 = 主动枚举候选、读版本、**只接受 17+**、优先最高版本；都不行就响亮失败并给出装法。
$script:PreferredJavaMajor = $PreferredJavaMajor
function Get-JavaMajor([string]$exe) {
    try {
        $out = (& $exe -version 2>&1 | Out-String)
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}
function Resolve-Java {
    $cands = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $cands.Add((Join-Path $env:JAVA_HOME "bin\java.exe")) }
    # ⭐ 顺序有讲究：**标准 JDK 优先**（Adoptium/Zulu/Corretto/Microsoft/Java 目录），
    #    **JetBrains Runtime (`jbr`) 排最后** —— 2026-10-02 本机实测：不排的话它会被选中
    #    （`D:\idea-2026.2.0.1\jbr`，major=25），而 JBR 是给 IDE 用的、与标准 JDK 行为有差异。
    foreach ($root in @("C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java", "C:\Program Files\Microsoft",
                        "C:\Program Files\Zulu", "C:\Program Files\Amazon Corretto",
                        "C:\Program Files\BellSoft", "D:\Java")) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk-?1[789]|jdk-?2[0-9]' } |
            ForEach-Object { $cands.Add((Join-Path $_.FullName "bin\java.exe")) }
    }
    Get-ChildItem "C:\Program Files\*\*\bin\java.exe" -ErrorAction SilentlyContinue |
        ForEach-Object { $cands.Add($_.FullName) }
    # 最低优先级：JBR（IDE 自带）与 PATH 上的 java
    Get-ChildItem "D:\*\jbr\bin\java.exe", "C:\Program Files\*\jbr\bin\java.exe" -ErrorAction SilentlyContinue |
        ForEach-Object { $cands.Add($_.FullName) }
    $jc = Get-Command java -ErrorAction SilentlyContinue
    if ($jc) { $cands.Add($jc.Source) }

    # ⭐ 选版本策略（2026-10-02 实测）：**优先恰好 17** —— Minecraft 1.20.1 的目标版本是 17，
    #    而本机 bash 版电池用的就是 17（WSL 的 java = 17.0.20.1）。新设备首轮 CORE 用 Java **21**
    #    跑出 42/44（`mine_regression`/`decision_contract` 两条 FAIL），与基线 41/41 不一致
    #    ⇒ 怀疑跨 Java 版本行为差异，故默认钉 17；只有没有 17 时才退到"17 以上最高的"。
    $exact = $null; $exactMajor = 0
    $higher = $null; $higherMajor = 0
    foreach ($c in ($cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)) {
        $m = Get-JavaMajor $c
        if ($m -eq $script:PreferredJavaMajor) { if (-not $exact) { $exact = $c; $exactMajor = $m } }
        elseif ($m -gt $script:PreferredJavaMajor -and $m -gt $higherMajor) { $higher = $c; $higherMajor = $m }
    }
    if ($exact) { return @{ Path = $exact; Major = $exactMajor } }
    if ($higher) { return @{ Path = $higher; Major = $higherMajor } }
    $best = $null; $bestMajor = 0
    foreach ($c in ($cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)) {
        $m = Get-JavaMajor $c
        if ($m -ge 17 -and $m -gt $bestMajor) { $best = $c; $bestMajor = $m }
    }
    if ($best) { return @{ Path = $best; Major = $bestMajor } }
    return $null
}
if ($JavaExe) {
    if (-not (Test-Path $JavaExe)) { Die "-JavaExe 指的文件不存在：$JavaExe" 4 }
    $jm = Get-JavaMajor $JavaExe
    if ($jm -lt 17) { Die "-JavaExe 指定的是 Java $jm（Forge 1.20.1 要 17+）：$JavaExe" 4 }
    $j = @{ Path = $JavaExe; Major = $jm }
}
$j = if ($j) { $j } else { Resolve-Java }
if (-not $j) {
    $anyPathJava = (Get-Command java -ErrorAction SilentlyContinue).Source
    $anyMajor = if ($anyPathJava) { Get-JavaMajor $anyPathJava } else { 0 }
    Die ("找不到 Java 17+（Forge 1.20.1 硬要求）。PATH 上的 java = '$anyPathJava'（major=$anyMajor）" +
         "；装法：winget install EclipseAdoptium.Temurin.17.JDK，或用 -JavaExe 指定") 4
}
$javaExe = $j.Path
Say "java = $javaExe（major=$($j.Major)；策略=优先 $PreferredJavaMajor，因为 MC 1.20.1 的目标版本是 17）"

# ---------- ①b ⭐ Java 的代理（2026-10-02 实测：不设这个，Forge 安装器会**卡死**）----------
# 为什么单独一段：`gh`/`git` 认 HTTP(S)_PROXY **环境变量**，但 **Java 不认** ——
# 它只认 `-Dhttp.proxyHost/-Dhttp.proxyPort/-Dhttps.proxyHost/-Dhttps.proxyPort`（或 JAVA_TOOL_OPTIONS）。
# 实测症状（新设备首跑）：安装器停在
#   `DownloadUtils.downloadLibrary` → `Downloading library from https://maven.minecraftforge.net/...`
# 三分钟不动、反复超时重试；加 JAVA_TOOL_OPTIONS 后**立刻越过**该点、开始正常下载
# （stderr 逐字：`Picked up JAVA_TOOL_OPTIONS: -Dhttp.proxyHost=127.0.0.1 …`）。
if (-not $ProxyUrl) {
    try {
        $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
        if ($reg -and ([int]$reg.ProxyEnable -eq 1) -and $reg.ProxyServer) {
            $srv = [string]$reg.ProxyServer
            if ($srv -notmatch '^https?://') { $srv = "http://$srv" }
            $ProxyUrl = $srv
        }
    } catch { }
}
if ($ProxyUrl) {
    if ($ProxyUrl -notmatch '^https?://([^:/]+)(?::(\d+))?') { Warn "代理格式看不懂：$ProxyUrl ⇒ 忽略"; $ProxyUrl = "" }
    else {
        $ph = $Matches[1]; $pp = if ($Matches[2]) { $Matches[2] } else { "80" }
        $jit = "-Dhttp.proxyHost=$ph -Dhttp.proxyPort=$pp -Dhttps.proxyHost=$ph -Dhttps.proxyPort=$pp"
        if ($env:JAVA_TOOL_OPTIONS -notlike "*proxyHost*") { $env:JAVA_TOOL_OPTIONS = $jit }
        Say "Java 代理已设（下载依赖必需）：$jit"
    }
} else { Warn "没检测到本地代理 ⇒ 若 Forge 下载卡住，用 -ProxyUrl http://127.0.0.1:7897 重跑" }

$modsDir = Join-Path $ServerDir "mods"
$pristine = Join-Path $ServerDir "world-pristine"
$world = Join-Path $ServerDir "world"

# Forge 版本：从已装的 win_args.txt 反推；否则用参数/默认
if (-not $ForgeVersion) {
    $found = Get-ChildItem (Join-Path $ServerDir "libraries\net\minecraftforge\forge") -Directory -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { $ForgeVersion = $found.Name } else { $ForgeVersion = "1.20.1-47.4.10" }
}
$winArgs = Join-Path $ServerDir ("libraries\net\minecraftforge\forge\$ForgeVersion\win_args.txt")
$unixArgs = Join-Path $ServerDir ("libraries\net\minecraftforge\forge\$ForgeVersion\unix_args.txt")
$argsFile = if (Test-Path $winArgs) { $winArgs } elseif (Test-Path $unixArgs) { $unixArgs } else { $null }

Say "服务端目录 = $ServerDir"
Say "Forge 版本 = $ForgeVersion"
Say "参数文件 = $(if ($argsFile) { $argsFile } else { '<缺 ⇒ 需要 --install>' })"

# ---------- ② 一次性准备 ----------
if (-not $argsFile) {
    Say "── 装 Forge 生产服务端（一次性）"
    New-Item -ItemType Directory -Force -Path $ServerDir | Out-Null
    $installer = Get-ChildItem (Join-Path $ServerDir "forge-installer*.jar"), (Join-Path $Repo "forge-installer*.jar") -ErrorAction SilentlyContinue |
                 Sort-Object Length -Descending | Select-Object -First 1
    if (-not $installer) {
        $url = "https://maven.minecraftforge.net/net/minecraftforge/forge/$ForgeVersion/forge-$ForgeVersion-installer.jar"
        $installer = Join-Path $ServerDir "forge-installer.jar"
        Say "下载安装器：$url"
        try { Invoke-WebRequest -Uri $url -OutFile $installer -UseBasicParsing } catch { Die "下载失败：$($_.Exception.Message)（可手工放置 forge-installer.jar 到 $ServerDir）" 4 }
    } else { Say "用现成安装器：$($installer.FullName)" }

    if (-not (Test-Path (Join-Path $ServerDir "eula.txt"))) {
        "eula=true" | Set-Content -Encoding ASCII (Join-Path $ServerDir "eula.txt")
    }
    Push-Location $ServerDir
    Say "运行安装器（会拉 150–200 MB 库，几分钟）…"
    # ⚠️ 2026-10-02 实测：**不要**写 `& $javaExe -jar <jar> --installServer`
    #    —— 新设备上 PowerShell 把 `--installServer` 传成了 JVM 选项，报
    #       `Unrecognized option: --installServer` / `Could not create the Java Virtual Machine`。
    #    正解 = Start-Process + -ArgumentList 数组（实测跑通）。
    $instOut = Join-Path $ServerDir "forge-install.out.log"
    $instErr = Join-Path $ServerDir "forge-install.err.log"
    $ip = Start-Process -FilePath $javaExe -ArgumentList @("-jar", $installer.FullName, "--installServer") `
          -WorkingDirectory $ServerDir -PassThru -Wait -NoNewWindow `
          -RedirectStandardOutput $instOut -RedirectStandardError $instErr -ErrorAction SilentlyContinue
    Say "  安装器退出码 = $($ip.ExitCode)"
    Get-Content $instOut -Tail 12 -ErrorAction SilentlyContinue | ForEach-Object { Say "  $_" }
    Pop-Location
    $argsFile = if (Test-Path $winArgs) { $winArgs } elseif (Test-Path $unixArgs) { $unixArgs } else { $null }
    if (-not $argsFile) { Die "安装后仍找不到 win_args.txt（看上面安装器输出）" 4 }
    Say "安装完成：$argsFile"
}

# ---------- ③ 世界母本 ----------
$saveSrc = Join-Path $ClientRoot "saves\新的世界"
if (-not (Test-Path $pristine)) {
    if (-not (Test-Path $saveSrc)) { Die "客户端存档不存在：$saveSrc（用 -ClientRoot 指定）" 4 }
    Say "── 建世界母本（一次性复制，⛔ 不动原存档）：$saveSrc → $pristine"
    Copy-Item $saveSrc $pristine -Recurse -Force
    Get-ChildItem $pristine -Recurse -File -Filter "session.lock" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    Say "母本文件数 = $((Get-ChildItem $pristine -Recurse -File | Measure-Object).Count)"
} else { Say "世界母本已存在 ⇒ 复用：$pristine" }

if ($Install) { Say "── -Install 完成（服务端 + 母本就绪）"; exit 0 }

# ---------- ④ 每轮重置世界 ----------
if (-not $KeepWorld) {
    if (Test-Path $world) { Remove-Item $world -Recurse -Force -ErrorAction SilentlyContinue }
    Say "重置世界：母本 → $world"
    Copy-Item $pristine $world -Recurse -Force
} else { Say "保留上一轮世界（-KeepWorld）" }

# ---------- ④b ⭐⭐ 世界洁净度（**必须与 tools/headless-battery.sh:246-295 逐步一致**）----------
# 为什么单独一段（2026-10-02 新设备实测的真因）：夹具世界是**玩家存档的副本** ⇒
# 它**继承玩家的第三方状态**（FTB Chunks 认领）。而认领内的破坏/放置会被 FTB **静默取消**
# ⇒ 夹具拿到"树砍不动"的世界、却**报成内核失败码**（`headless-battery.sh` 注释逐字：
# `lumber_job` 连红 35 轮的真因，`D-409`/`D-413`）。
# 实测症状：新设备 CORE 比基线多一条红 `decision_contract=FAIL reason=菜单含 lumber 候选`
# —— 而且 **Java 17 / Java 21 跑出来完全一样**（排除 JVM），故按 bash 版补齐这三步。

# (a) 场景数据包：以**仓库版**为准 + 清同命名空间旧包 + 断言只剩一个提供者（`D-412`）
$scenesSrc = if ($Repo) { Join-Path $Repo "tools\test-scenes\alice_test" } else { $null }
if ($scenesSrc -and (Test-Path $scenesSrc)) {
    $dpDir = Join-Path $world "datapacks"
    New-Item -ItemType Directory -Force -Path $dpDir | Out-Null
    $dp = Join-Path $dpDir "alice_test"
    Remove-Item -LiteralPath $dp -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $scenesSrc -Destination $dp -Recurse -Force
    foreach ($other in (Get-ChildItem -LiteralPath $dpDir -Directory -ErrorAction SilentlyContinue)) {
        if ($other.Name -eq "alice_test") { continue }
        if (Test-Path (Join-Path $other.FullName "data\alice_test")) {
            Remove-Item -LiteralPath $other.FullName -Recurse -Force -ErrorAction SilentlyContinue
            Say "  ⚠️ 清掉同命名空间的旧数据包：$($other.Name)（它会**静默盖住**本轮场景 —— D-412）"
        }
    }
    $providers = @(Get-ChildItem -LiteralPath $dpDir -Directory -ErrorAction SilentlyContinue |
                   Where-Object { Test-Path (Join-Path $_.FullName "data\alice_test") }).Count
    if ($providers -ne 1) { Die "场景数据包提供者应为 1 个，实测 $providers 个（同命名空间的包会互相盖住 —— D-412）" 5 }
    Say "场景数据包就位（提供者 = 1）"
} else { Warn "找不到仓库的 tools\test-scenes\alice_test ⇒ 场景类步骤可能假红（用 -Repo 指定仓库）" }

# (b) 第三方认领（`D-409`/`D-413`）—— ⭐ 本条就是 `decision_contract` 那条红的解药
$ftbDir = Join-Path $world "ftbchunks"
if (Test-Path $ftbDir) {
    $snbt = @(Get-ChildItem -LiteralPath $ftbDir -File -Filter "*.snbt" -ErrorAction SilentlyContinue)
    if ($snbt.Count -gt 0) {
        $snbt | Remove-Item -Force -ErrorAction SilentlyContinue
        Say "已清第三方认领（$($snbt.Count) 份 ftbchunks/*.snbt ⇒ 本轮夹具世界无认领；D-409/D-413）"
    } else { Say "ftbchunks 下没有 *.snbt（无需清）" }
}

# (c) Alice 的持久化状态一律清零（`D-235`）：假人 / 转移账本 / 区域状态 / 权限 / 安全区等
$dataDir = Join-Path $world "data"
if (Test-Path $dataDir) {
    $adat = @(Get-ChildItem -LiteralPath $dataDir -File -Filter "alice_*.dat" -ErrorAction SilentlyContinue)
    if ($adat.Count -gt 0) {
        $adat | Remove-Item -Force -ErrorAction SilentlyContinue
        Say "已清 Alice 持久化状态（$($adat.Count) 份 world/data/alice_*.dat ⇒ 干净起点）"
    }
}

# ---------- ⑤ server.properties（先建文件再钉）----------
$props = Join-Path $ServerDir "server.properties"
if (-not (Test-Path $props)) { New-Item -ItemType File -Path $props -Force | Out-Null }
$pins = @{ "difficulty" = "peaceful"; "online-mode" = "false"; "spawn-monsters" = "false"; "pvp" = "false"; "allow-flight" = "true"; "spawn-protection" = "0" }
$lines = @(Get-Content $props -ErrorAction SilentlyContinue)
foreach ($k in $pins.Keys) {
    $hit = $false
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match ("^" + [regex]::Escape($k) + "=")) { $lines[$i] = "$k=$($pins[$k])"; $hit = $true } }
    if (-not $hit) { $lines += "$k=$($pins[$k])" }
}
$lines | Set-Content -Encoding ASCII $props
Say "夹具洁净度（已钉）：$(($pins.Keys | ForEach-Object { "$_=$($pins[$_])" }) -join ' ')"

# ---------- ⑥ 装模组 ----------
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null
# ⭐ 与 bash 版一致：**先清空** mods 目录（`rm -f "$MODS"/*.jar`）—— 跨轮残留模组会污染判决
$oldJars = @(Get-ChildItem -LiteralPath $modsDir -File -Filter "*.jar" -ErrorAction SilentlyContinue)
if ($oldJars.Count -gt 0) { $oldJars | Remove-Item -Force -ErrorAction SilentlyContinue; Say "清掉上一轮的 $($oldJars.Count) 个 jar（干净起点）" }
$clientMods = Join-Path $ClientRoot "mods"
if (-not $NoMods) {
    if (-not (Test-Path $clientMods)) { Warn "客户端模组目录不存在：$clientMods ⇒ 只装 Alice jar（craft 类步骤会**假红**）" }
    else {
        # ⚠️ 云端实测的坑（CLOUD_MIGRATION §9-39）：不给客户端模组 ⇒ craft 类步骤假红
        # ⭐ 客户端专属模组名单 —— **必须与 tools/headless-battery.sh:128 的 `CLIENT_ONLY_DEFAULT` 一致**。
        # 2026-10-02 实测（新设备首跑崩服）：漏掉它 ⇒ `jecharacters` 在专用服务端上
        # `NoClassDefFoundError: net/minecraft/client/searchtree/SuffixArray` ⇒
        # `Failed to complete lifecycle event CONSTRUCT` ⇒ 服务端 12 秒退出、**永远等不到判决**。
        # （`D-324`：Xaero 世界地图是纯客户端；JEI 与拼音搜索同理）
        $clientOnly = @(
            "[通用拼音搜索] jecharacters-1.20.1-forge-4.6.11.jar",
            "[JEI物品管理器] jei-1.20.1-forge-15.58.0.209.jar",
            "xaeroworldmap-forge-1.20.1-1.46.0.jar"
        )
        foreach ($j in (Get-ChildItem $clientMods -Filter *.jar -ErrorAction SilentlyContinue)) {
            if ($j.Name -like "alice-*.jar" -or $j.Name -like "*.bak.*") { continue }
            if ($clientOnly -contains $j.Name) { Say "  跳过客户端专属：$($j.Name)"; continue }
            # ⭐ 2026-10-02 实测：必须用 -LiteralPath —— 模组名里的 `[` `]`
            #    （如 `[JEI物品管理器] jei-….jar`）在 -Path 里是**通配符字符类** ⇒ Copy-Item
            #    静默匹配不到 ⇒ 那只模组**没进服务端**（实测漏 4 个：FTB×2 / JEI / jecharacters）。
            Copy-Item -LiteralPath $j.FullName -Destination (Join-Path $modsDir $j.Name) -Force
        }
    }
}
# Alice jar：优先用仓库构建产物；⭐ 没有就**回退用客户端那份**（测试机不构建 —— 用户 2026-10-02 定：
# 新设备只负责跑测试，jar 从本机构建好后拷过来；实测新设备仓库里 build\libs 不存在）。
$art = $null
if ($Repo) {
    $art = Get-ChildItem (Join-Path $Repo "build\libs") -Filter "alice-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*sources*" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($art) { Say "Alice 工件（仓库构建产物）：$($art.Name)" }
}
if (-not $art -and (Test-Path $clientMods)) {
    $art = Get-ChildItem -LiteralPath $clientMods -Filter "alice-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*.bak.*" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($art) { Warn "仓库没有构建产物 ⇒ **回退用客户端那份 Alice jar**：$($art.Name)（先确认它是最新的）" }
}
if ($art) { Copy-Item -LiteralPath $art.FullName -Destination (Join-Path $modsDir $art.Name) -Force }
else { Warn "找不到 Alice jar（仓库 build\libs 与客户端 mods 都没有）⇒ 电池不会跑起来" }
Say "mods 共 $((Get-ChildItem -LiteralPath $modsDir -Filter *.jar | Measure-Object).Count) 个"

# ---------- ⑦ 起服务端 ----------
$result = Join-Path $ServerDir "headless-result.txt"
$logDir = Join-Path $ServerDir "logs"
$serverLog = Join-Path $logDir "latest.log"
Remove-Item $result -Force -ErrorAction SilentlyContinue
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdoutLog = Join-Path $ServerDir "headless-stdout-$stamp.log"

Say "── 启动无头服务端：mode=$Mode timeout=${TimeoutSec}s"
$argList = @("-Xmx$($MaxHeapMB)M", "-Dalice.headless.battery=$Mode", "@user_jvm_args.txt", "@$argsFile", "nogui")
$proc = Start-Process -FilePath $javaExe -ArgumentList $argList -WorkingDirectory $ServerDir -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError ($stdoutLog + ".err") -ErrorAction SilentlyContinue
if (-not $proc) { Die "起不来（Start-Process 失败）" 4 }
Say "进程 pid = $($proc.Id)"

# ---------- 轮询判决 ----------
$verdict = ""
$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    Start-Sleep -Seconds 2
    if (Test-Path $result) {
        $m = Select-String -Path $result -Pattern 'verdict=([A-Za-z_]+)' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($m) { $verdict = $m.Matches[0].Groups[1].Value }
    }
    if (-not $verdict -and (Test-Path $serverLog)) {
        $m = Select-String -Path $serverLog -Pattern 'Headless\] RESULT verdict=([A-Za-z_]+)' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($m) { $verdict = $m.Matches[0].Groups[1].Value }
    }
    if ($verdict) { Say "判决行已出现：verdict=$verdict（+$([int]$sw.Elapsed.TotalSeconds)s）"; break }
    if ($proc.HasExited) { Say "服务端已退出（exit=$($proc.ExitCode)）"; break }
}
$elapsed = [int]$sw.Elapsed.TotalSeconds

# 给 20 s 自己收尾；收不掉就杀（不影响判决）
if ($verdict) {
    for ($i = 0; $i -lt 20; $i++) { if ($proc.HasExited) { break }; Start-Sleep -Seconds 1 }
}
if (-not $proc.HasExited) {
    Warn "进程仍在跑 ⇒ 终止（判决已落盘，不影响结论）"
    try { $proc.Kill($true) } catch { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
}

# ---------- ⑧ 判决（退出码口径与 bash 版逐字一致）----------
$code = switch ($verdict) {
    "PASS"         { 0 }
    "FAIL"         { 1 }
    "DEGRADED"     { 2 }
    "list_modules" { 0 }
    ""             { 3 }
    default        { 5 }
}
Say "──── 结果 ────"
Say "verdict=$(if ($verdict) { $verdict } else { '<无>' }) exit=$code 用时=${elapsed}s"
$sum = Select-String -Path $serverLog -Pattern 'Regression\] SUMMARY' -ErrorAction SilentlyContinue | Select-Object -Last 1
if ($sum) { Say $sum.Line.Trim() }
$nonPass = Select-String -Path $serverLog -Pattern '非 PASS 步' -ErrorAction SilentlyContinue | Select-Object -Last 1
if ($nonPass) { Say $nonPass.Line.Trim() }
Say "前提(effective)：" + ((Select-String -Path $props -Pattern '^(difficulty|spawn-monsters|pvp|allow-flight|online-mode|spawn-protection)=' | ForEach-Object { $_.Line }) -join ' ')

# ---------- ⑨ 归档日志 ----------
if ($Repo) {
    $dst = Join-Path $Repo "run\headless-logs"
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    foreach ($f in @($serverLog, $stdoutLog, $result)) {
        if (Test-Path $f) { Copy-Item $f (Join-Path $dst ("$stamp-" + [IO.Path]::GetFileName($f))) -Force -ErrorAction SilentlyContinue }
    }
    Say "日志已归档：$dst"
}
exit $code
