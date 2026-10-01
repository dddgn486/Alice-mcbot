<#
.SYNOPSIS
  客户端管家**排查入口**（确定性 · 零 LLM 花费）：照顺序验"本机 → 网络 → 凭据 → 云端"，
  每项打印 `[ OK ] / [FIX ] / [FAIL] / [SKIP]`，最后给一行结论。

.DESCRIPTION
  为什么有它（用户 2026-10-01 的要求）：
    「管家要理解云端和客户端的情况，就能尝试修复恢复，只需要给他几个排查入口」
    ⇒ 本脚本**只报事实 + 做幂等修复**，**不做聪明判断**。判断交给管家（或主工作流）看这些事实来做。

  两类用法：
    · 人看：`client-agent.cmd -Doctor`        —— 出问题先跑这个，全绿再唤醒 agent
    · 管家用：被唤醒后如果 gh/云端不通，**先跑 `-Doctor`** 拿事实，再决定下一步（见 preset 的故障处置段）

  分层（刻意从近到远，哪一层断就是哪一层的问题）：
    ① 本机层   node / dsh / 配置 / 客户端形状 / PAT 文件
    ② 网络层   代理（gh 是 Go，**只认 HTTP(S)_PROXY，不读 Windows 系统代理**）
    ③ 凭据层   gh 存在 / gh api /user 能否认证
    ④ 机器层   codespace 在不在、什么状态（Shutdown 就唤醒）
    ⑤ 服务层   远端 sshd 通不通、信箱四目录在不在、dsh web 在不在跑
    ⑥ 仓库层   远端 Alice 仓库的 HEAD（确认"我"跑的那份代码是哪一版）
    ⑦ 设备层   ⭐ **这台机器自己能不能干活**（Java 17 / 仓库 / jar / 存档 / mod / 服务端库 / 磁盘 / dsh / Java 代理）
               —— 加它的理由：回家后主工作流在云端、**ssh 不到这台设备**，排错只能靠设备上的管家

.PARAMETER Codespace
  codespace 名；空 = 读 `%USERPROFILE%\.alice-client.json` 的 `codespace` 字段。

.PARAMETER Repair
  启用幂等修复：刷代理环境变量 / 重写 gh 凭据 / 唤醒 codespace / 补信箱目录 / 起远端 dsh web。
  ⛔ **不做**破坏性动作：不重建 codespace、不删任何东西、不改源码。

.PARAMETER SetCodespace
  把新的 codespace 名写进所有该写的地方（各脚本默认值 + `.alice-client.json` + 本机 WSL 侧 tools/）。
  刻意做成**显式开关**：重建机器时先 `-SetCodespace <新名>`，再跑 `-Doctor -Repair`。

.PARAMETER DryRun
  只打印"会改什么"，不改任何文件/变量。

.PARAMETER Quiet
  不打提示行（`->`）与分节标题。

.EXAMPLE
  client-agent.cmd -Doctor
  client-agent.cmd -Doctor -Repair
  client-agent.cmd -Doctor -SetCodespace alice-cloud-01-xxxx -Repair
#>
[CmdletBinding()]
param(
    [string]$Codespace = "",
    [switch]$Repair,
    [string]$SetCodespace = "",
    [switch]$DryRun,
    [switch]$Quiet
)
$ErrorActionPreference = "Continue"

# ⭐ 必须自己刷新 PATH：winget/npm 刚装完的东西只在**新终端**里可见。
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')

$script:ok = 0; $script:fix = 0; $script:bad = 0; $script:skip = 0
function Head($m) { if (-not $Quiet) { Write-Host ""; Write-Host ("── " + $m + " " + ("─" * [Math]::Max(1, 46 - $m.Length))) -ForegroundColor DarkCyan } }
function Ok($m)   { $script:ok++;   Write-Host ("  [ OK ] " + $m) -ForegroundColor Green }
function Fixed($m){ $script:fix++;  Write-Host ("  [FIX ] " + $m) -ForegroundColor Cyan }
function Bad($m)  { $script:bad++;  Write-Host ("  [FAIL] " + $m) -ForegroundColor Red }
function Skip($m) { $script:skip++; Write-Host ("  [SKIP] " + $m) -ForegroundColor DarkGray }
function Note($m) { Write-Host ("         " + $m) -ForegroundColor DarkGray }

Write-Host ("=== 客户端管家排查（" + $env:COMPUTERNAME + " / " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "）===") -ForegroundColor Cyan
if ($Repair) { Note "模式：诊断 + 幂等修复（不做破坏性动作）" } else { Note "模式：只诊断（加 -Repair 才修）" }
if ($DryRun) { Note "DryRun：只说不做" }

# ============================ 通用工具 ============================

$script:ghCache = ""
function Resolve-Gh {
    if ($script:ghCache) { return $script:ghCache }
    $c = Get-Command gh -ErrorAction SilentlyContinue
    if ($c) { $script:ghCache = $c.Source; return $script:ghCache }
    foreach ($p in @((Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"), "C:\Program Files\GitHub CLI\gh.exe")) {
        if (Test-Path $p) { $env:Path = (Split-Path $p) + ";" + $env:Path; $script:ghCache = $p; return $p }
    }
    return $null
}

# ⭐ 远端执行必须走 base64：`gh codespace ssh -- <args>` 会把 `--` 之后的参数**用空格拼接**，
#    单个参数里的引号会丢（实测坑：`bash -lc mkdir`）⇒ 复杂脚本一律 base64 中转。
function Remote-Script([string]$Gh, [string]$Cs, [string]$Code) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Code))
    # ⭐ 弱网重试（实测 2026-10-01：同一台机器上 `gh codespace ssh` 约 1/5 次报
    #    `failed to invoke SSH RPC: DeadlineExceeded` ⇒ 单次失败**不代表云端坏了**，
    #    所以这里重试 3 次、每次退避 2/5 秒，并把最后一次的原始输出原样带出去给人看。
    $lastErr = ""
    for ($try = 1; $try -le 3; $try++) {
        $out = & $Gh codespace ssh -c $Cs -- "bash -lc 'echo $b64 | base64 -d | bash -l'" 2>&1
        $txt = (($out | Out-String) -replace "`r", "").Trim()
        if ($txt -and $txt -notmatch "DeadlineExceeded|i/o timeout|connection refused|SSH RPC") { return $txt }
        $lastErr = $txt
        if ($try -lt 3) { Start-Sleep -Seconds (2 * $try) }
    }
    return $lastErr
}

# 远端体检：把 tools/alice-cloudctl.sh 的内容喂进去跑（**不依赖远端已装它** ⇒ 没有鸡生蛋问题）
function Remote-Cloudctl([string]$Gh, [string]$Cs, [string]$Sub) {
    $cands = @(
        (Join-Path $PSScriptRoot "..\alice-cloudctl.sh"),
        (Join-Path $PSScriptRoot "alice-cloudctl.sh"),
        (Join-Path (Split-Path -Parent $PSScriptRoot) "alice-cloudctl.sh")
    )
    $src = $null
    foreach ($c in $cands) { if (Test-Path $c) { $src = (Resolve-Path $c).Path; break } }
    if (-not $src) { return $null }
    $body = (Get-Content $src -Raw) + "`n" + $Sub + "`n"
    return (Remote-Script $Gh $Cs $body)
}

$cfgPath = Join-Path $HOME ".alice-client.json"
$cfg = $null
$L = @{ ok = 0; fix = 0; bad = 0; skip = 0 }   # 各层计数（打印用）

# ============================ ① 本机层 ============================
Head "① 本机层"
if (Get-Command node -ErrorAction SilentlyContinue) { Ok ("node " + (node --version)) } else { Bad "没有 node（装 Node.js 22+：winget install OpenJS.NodeJS.LTS）" }

if (Get-Command dsh -ErrorAction SilentlyContinue) {
    $dv = ((& dsh --version 2>&1) -join " ").Trim()
    Ok ("dsh 可用：$dv")
    if ($dv -match "rc\.1|rc\.2") { Note "⚠️ 实测 rc.1/rc.2 的发布包**残缺**（少 dsh-sandbox-local ⇒ dsh web 起不来）；云端/客户端都用 rc.3" }
} else {
    Bad '没有全局 dsh ⇒ 跑 client-agent.cmd -Install（或让 cmd 走 npx 兜底）'
}

if (Test-Path $cfgPath) {
    Ok "配置存在：$cfgPath"
    try { $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json } catch { Bad "配置不是合法 JSON ⇒ 重跑 -Install" }
} else { Bad "缺 $cfgPath ⇒ 跑 client-agent.cmd -Install" }

if ($cfg) {
    if (-not $Codespace -and $cfg.codespace) { $Codespace = [string]$cfg.codespace }
    if ($cfg.clientRoot) {
        $cr = [string]$cfg.clientRoot
        if (Test-Path $cr) {
            $hasLogs = Test-Path (Join-Path $cr "logs"); $hasMods = Test-Path (Join-Path $cr "mods")
            if ($hasLogs -and $hasMods) { Ok "客户端目录形状正确：$cr" }
            else { Bad "客户端目录形状不对（logs=$hasLogs mods=$hasMods）：$cr" }
        } else { Bad "配置里的 clientRoot 不存在：$cr" }
    } else { Bad "配置里没有 clientRoot" }
}
if (-not $Codespace) { Bad "不知道 codespace 名（配置里没有、也没 -Codespace 传）" }

# ============================ ② 网络层（代理） ============================
Head "② 网络层（代理）"
$regProxy = ""
try {
    $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
    if ($reg -and ([int]$reg.ProxyEnable -eq 1) -and $reg.ProxyServer) {
        $regProxy = [string]$reg.ProxyServer
        if ($regProxy -notmatch '^https?://') { $regProxy = "http://$regProxy" }
    }
} catch { }
$envProxy = [Environment]::GetEnvironmentVariable('HTTPS_PROXY','User')
if ($regProxy)  { Note "系统代理（注册表）：$regProxy" }        else { Note "系统代理：未开启" }
if ($envProxy)  { Note "用户级 HTTPS_PROXY：$envProxy" }        else { Note "用户级 HTTPS_PROXY：未设" }

if ($regProxy -and $envProxy -and ($envProxy -ne $regProxy)) {
    if ($Repair) {
        if ($DryRun) { Fixed "（DryRun）会把 HTTPS_PROXY 从 $envProxy 改成 $regProxy" }
        else {
            foreach ($k in @("HTTP_PROXY","HTTPS_PROXY")) { [Environment]::SetEnvironmentVariable($k, $regProxy, "User") }
            $env:HTTP_PROXY = $regProxy; $env:HTTPS_PROXY = $regProxy
            if ($cfg) { $cfg | Add-Member -NotePropertyName proxy -NotePropertyValue $regProxy -Force; ($cfg | ConvertTo-Json -Depth 4) | Set-Content -Encoding UTF8 $cfgPath }
            Fixed "代理已换成注册表当前值：$regProxy（并更新 .alice-client.json）"
        }
    } else { Bad "代理不一致：注册表=$regProxy 但环境变量=$envProxy ⇒ 加 -Repair 修" }
} elseif ($regProxy) {
    if (-not $envProxy) {
        if ($Repair) { if (-not $DryRun) { foreach ($k in @("HTTP_PROXY","HTTPS_PROXY")) { [Environment]::SetEnvironmentVariable($k, $regProxy, "User") }; $env:HTTP_PROXY = $regProxy; $env:HTTPS_PROXY = $regProxy }; Fixed "已设用户级 HTTP(S)_PROXY=$regProxy" }
        else { Bad "系统开着代理但 gh 看不到（Go 不读系统代理）⇒ 加 -Repair 写用户级变量" }
    } else { Ok "代理已配置（$envProxy）" }
} else { Skip "没检测到本地代理（若你在用代理：-Doctor -Repair 会用注册表值补）" }
if (-not $env:HTTPS_PROXY) { $env:HTTPS_PROXY = $envProxy; $env:HTTP_PROXY = $envProxy }

# ============================ ③ 凭据层 ============================
Head "③ 凭据层"
$tok = Join-Path $HOME ".gh-token"
if (Test-Path $tok) { Ok "PAT 文件存在：$tok" } else { Bad "缺 $tok（GitHub PAT，需要 codespace + repo scope）" }
$Gh = Resolve-Gh
$login = ""
if ($Gh) {
    Ok ("gh 可用：" + $Gh)
    if (-not $env:GH_TOKEN -and (Test-Path $tok)) { $env:GH_TOKEN = (Get-Content $tok -Raw).Trim() }
    $login = ((& $Gh api /user --jq .login 2>$null) -join "").Trim()
    if ($login) { Ok "gh 已认证：$login" }
    else {
        Bad "gh 未认证 / 网络不通"
        if ($Repair -and (Test-Path $tok)) {
            if ($DryRun) { Fixed "（DryRun）会把 PAT 写进 %APPDATA%\GitHub CLI\hosts.yml" }
            else {
                $ghDir = Join-Path $env:APPDATA "GitHub CLI"; New-Item -ItemType Directory -Force -Path $ghDir | Out-Null
                $hosts = Join-Path $ghDir "hosts.yml"
                if (Test-Path $hosts) { Copy-Item $hosts "$hosts.bak-doctor" -Force }
                $pat = (Get-Content $tok -Raw).Trim()
                ("github.com:`n    oauth_token: $pat`n    git_protocol: ssh`n") | Set-Content -Encoding UTF8 $hosts
                $login = ((& $Gh api /user --jq .login 2>$null) -join "").Trim()
                if ($login) { Fixed "已用 PAT 重写 gh 凭据 ⇒ 认证 OK：$login" } else { Bad "重写凭据后仍不通 ⇒ 看上面的原始报错（网络？PAT 过期？）" }
            }
        } else { Note "加 -Repair 会用 %USERPROFILE%\.gh-token 重写 gh 凭据（不动别的）" }
    }
} else { Bad "没有 gh ⇒ winget install --id GitHub.cli -e（装完要新开终端）" }

# ============================ ④ 机器层（codespace） ============================
$csState = ""
if ($Gh -and $login -and $Codespace) {
    Head "④ 机器层（codespace）"
    Note "目标 codespace：$Codespace"
    $all = ((& $Gh codespace list --json name,state,machineName 2>$null) | Out-String)
    $mine = $null
    try { $mine = ($all | ConvertFrom-Json) | Where-Object { $_.name -eq $Codespace } } catch { }
    if (-not $mine) {
        Bad "codespace '$Codespace' 不在你的列表里（名字写错 / 已被删）"
        Note ("现有：" + (($all | ConvertFrom-Json | ForEach-Object { $_.name + "=" + $_.state }) -join ", "))
        Note "⇒ 重建（在 WSL 侧跑）：tools/codespace-zero.sh create 之后用 -SetCodespace <新名> 一键改本地所有引用"
    } else {
        $csState = $mine.state
        if ($csState -eq "Shutdown") {
            if ($Repair) {
                if ($DryRun) { Fixed "（DryRun）会唤醒 codespace" }
                else {
                    Fixed "codespace 是 Shutdown ⇒ 正在唤醒（gh api POST .../start）…"
                    $r = (& $Gh api -X POST "/user/codespaces/$Codespace/start" 2>&1 | Out-String)
                    for ($i = 0; $i -lt 30; $i++) {
                        Start-Sleep -Seconds 5
                        $s2 = ((& $Gh codespace list --json name,state 2>$null | Out-String) | ConvertFrom-Json | Where-Object { $_.name -eq $Codespace }).state
                        if ($s2 -eq "Available") { $csState = $s2; break }
                    }
                    if ($csState -eq "Available") { Fixed "已唤醒 ⇒ Available" } else { Bad "唤醒后状态仍 = $csState（等一下再跑 -Doctor，或看 https://github.com/codespaces）" }
                }
            } else { Bad "codespace 是 Shutdown（gh 2.45 没有 codespace start；加 -Repair 会用 API 唤醒）" }
        } else { Ok "codespace 状态 = $csState（$($mine.machineName)）" }
    }
} elseif (-not $Quiet) { Head "④ 机器层（codespace）"; Skip "（凭据不通 ⇒ 这一段测不了）" }

# ============================ ⑤ 服务层（远端体检） ============================
$head = $null
if ($Gh -and $login -and $csState -eq "Available") {
    Head "⑤ 服务层（远端）"
    $head = Remote-Script $Gh $Codespace 'hostname; id -un; echo "HOME=$HOME"'
    if ($head -match "HOME=") {
        Ok "sshd 通（$((($head -split "`n")[0]).Trim()) / $((($head -split "`n")[1]).Trim()) / $((($head -split "`n")[2]).Trim())）"
        $mb = Remote-Script $Gh $Codespace 'ls -d "$HOME/bus/to-win" "$HOME/bus/to-cloud" "$HOME/client-info" "$HOME/outbox" 2>/dev/null | wc -l'
        if (($mb.Trim() -eq "4")) { Ok "信箱四目录齐" }
        else {
            Bad "信箱目录不全（得到 '$($mb.Trim())'/4）"
            if ($Repair) {
                if ($DryRun) { Fixed "（DryRun）会 mkdir -p 四个信箱目录" }
                else {
                    Remote-Script $Gh $Codespace 'mkdir -p "$HOME/bus/to-win" "$HOME/bus/to-cloud" "$HOME/client-info" "$HOME/outbox"; echo done' | Out-Null
                    $mb2 = Remote-Script $Gh $Codespace 'ls -d "$HOME/bus/to-win" "$HOME/bus/to-cloud" "$HOME/client-info" "$HOME/outbox" 2>/dev/null | wc -l'
                    if ($mb2.Trim() -eq "4") { Fixed "已补建信箱四目录" } else { Bad "补建失败（得到 $($mb2.Trim())）" }
                }
            } else { Note "加 -Repair 会自动 mkdir -p 补齐" }
        }
        $web = Remote-Script $Gh $Codespace 'pgrep -f "[d]sh web" | head -1'
        if ($web -match '^\d+$') { Ok "远端 dsh web 在跑（pid=$($web.Trim())）" }
        else {
            Bad "远端 dsh web **没在跑**（页面会打不开）"
            if ($Repair) {
                if ($DryRun) { Fixed "（DryRun）会在远端跑 alice-cloudctl.sh fix 起服务" }
                else {
                    $th = "$Codespace-3081.app.github.dev"
                    Fixed "正在起远端服务（--trusted-host $th）…"
                    $r = Remote-Cloudctl $Gh $Codespace "DSH_TRUSTED_HOST=$th; export DSH_TRUSTED_HOST; fix"
                    if ($r) { ($r -split "`n") | Where-Object { $_ -match '^\[|^HOST=|^last_url=|TRUSTED_HOST=' } | ForEach-Object { Note $_ } }
                    $web2 = Remote-Script $Gh $Codespace 'pgrep -f "[d]sh web" | head -1'
                    if ($web2 -match '^\d+$') { Fixed "远端 dsh web 已起（pid=$($web2.Trim())）" } else { Bad "起服务失败 ⇒ 在远端跑 alice-cloudctl.sh status 看原始报错" }
                }
            } else { Note "加 -Repair 会在远端起它（远端得先有 dsh：npm i -g @deepseek-ai/dsh@0.1.5-rc.3）" }
        }
    } else {
        Bad "ssh 不进去（原始输出：$head）"
        Note "常见原因：codespace 刚唤醒还没就绪（等 1 分钟再跑）/ 自建 devcontainer 缺 sshd feature / 代理抖动"
    }
} elseif (-not $Quiet) { Head "⑤ 服务层（远端）"; Skip "（codespace 不可用 ⇒ 这一段测不了）" }

# ============================ ⑥ 仓库层 ============================
if ($Gh -and $login -and $csState -eq "Available") {
    Head "⑥ 仓库层（远端 Alice 代码在哪一版）"
    $g = Remote-Script $Gh $Codespace 'for d in "$HOME/projects/alice" "$HOME/projects" "/workspaces/Alice-mcbot"; do if [ -d "$d/.git" ]; then echo "REPO=$d"; git -C "$d" log -1 --format="HEAD=%h %ci"; git -C "$d" status --porcelain | head -5; git -C "$d" remote -v | head -2; break; fi; done'
    if ($g -match "REPO=") { ($g -split "`n") | Where-Object { $_ -match '\S' } | ForEach-Object { Note $_ }; Ok "已报出远端仓库位置与 HEAD" }
    else { Skip "远端没找到 Alice 仓库（还没 clone？）" }
}

# ============================ ⑦ -SetCodespace ============================
if ($SetCodespace) {
    Head "⑦ 把 codespace 名改成：$SetCodespace"
    # 该改的文件：本机 WSL 侧 tools/ 下所有出现旧名的地方 + %USERPROFILE%\.alice-client.json
    $old = if ($cfg -and $cfg.codespace) { [string]$cfg.codespace } else { "" }
    if (-not $old) { Bad "旧名字未知（配置里没有）⇒ 无法全局替换" }
    else {
        $targets = @()
        $wslTools = "\\wsl.localhost\Ubuntu\home\fb486\projects\alice\tools"
        if (Test-Path $wslTools) {
            $targets += Get-ChildItem -Path $wslTools -Recurse -File -Include *.ps1,*.cmd,*.sh,*.mjs -ErrorAction SilentlyContinue |
                        Where-Object { $_.FullName -notmatch '\\build\\|\\node_modules\\' }
        } else { Note "WSL 侧 tools/ 不可达（$wslTools）⇒ 只改 Windows 侧" }
        # 本机 WSL 侧的 client-agent 目录也在 tools 下，已被上面覆盖；这里再加 Windows 侧自身
        $targets += Get-ChildItem -Path $PSScriptRoot -Recurse -File -Include *.ps1,*.cmd -ErrorAction SilentlyContinue

        $changed = 0
        foreach ($f in ($targets | Sort-Object FullName -Unique)) {
            try {
                $txt = Get-Content $f.FullName -Raw -ErrorAction Stop
                if ($txt -and $txt.Contains($old)) {
                    if ($DryRun) { Note ("会改：" + $f.FullName) }
                    else {
                        # ⚠️ 保编码：.ps1/.cmd 要 UTF-8 with BOM / CRLF 才不会被 PS5.1 与 cmd.exe 误读
                        $new = $txt.Replace($old, $SetCodespace)
                        $enc = New-Object System.Text.UTF8Encoding($true)
                        [IO.File]::WriteAllText($f.FullName, $new, $enc)
                        $changed++
                    }
                }
            } catch { Note ("跳过（读不了）：" + $f.FullName + " :: " + $_.Exception.Message) }
        }
        if ($DryRun) { Fixed "（DryRun）共 $($targets.Count) 个候选文件，未改" }
        else { Fixed "已替换 $changed 个文件里的 codespace 名" }

        if ($cfg) {
            if ($DryRun) { Fixed "（DryRun）会更新 .alice-client.json" }
            else {
                $cfg.codespace = $SetCodespace
                ($cfg | ConvertTo-Json -Depth 4) | Set-Content -Encoding UTF8 $cfgPath
                Fixed "配置已更新：$cfgPath → $SetCodespace"
            }
        }
    }
}

# ============================ ⑦ 设备层（这台机器能不能干活）============================
# ⭐ 为什么单列一层（2026-10-02 用户裁定 + 实测拓扑）：回家后主工作流在**云端**，而云端
#    **不在**设备的虚拟局域网里 ⇒ **主工作流 ssh 不到这台设备** ⇒ 排错只能由**设备上的管家**做。
#    既有 ①–⑥ **全是"云端链路"**，没有一条在问"这台机器自己能不能干活" ⇒ 本层补上。
#    ⛔ 本层**不做网络访问**（除了 dsh 检查），所以云端断了它照样有结论。
Head "⑦ 设备层（这台机器能不能干活）"

$devRepo   = if ($cfg -and $cfg.repo)       { [string]$cfg.repo }       else { "D:\JAVA_projects\alice" }
$devRoot   = if ($cfg -and $cfg.clientRoot) { [string]$cfg.clientRoot } else { "D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10" }
$devServer = if ($cfg -and $cfg.serverDir)  { [string]$cfg.serverDir }  else { "D:\JAVA_projects\alice-server" }

# --- Java 17+（⛔ 不许盲信 PATH：实测本机 PATH 上是 **16.0.2** ⇒ Forge 1.20.1 启动即死、且**永远等不到判决**）---
$cands = New-Object System.Collections.Generic.List[string]
if ($env:JAVA_HOME) { $cands.Add((Join-Path $env:JAVA_HOME "bin\java.exe")) }
foreach ($r in @("C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java", "C:\Program Files\Zulu",
                 "C:\Program Files\Microsoft", "C:\Program Files\Amazon Corretto", "D:\Java")) {
    if (-not (Test-Path $r)) { continue }
    Get-ChildItem $r -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'jdk-?1[789]|jdk-?2[0-9]' } |
        ForEach-Object { $cands.Add((Join-Path $_.FullName "bin\java.exe")) }
}
Get-ChildItem "D:\*\jbr\bin\java.exe", "C:\Program Files\*\jbr\bin\java.exe" -ErrorAction SilentlyContinue |
    ForEach-Object { $cands.Add($_.FullName) }
$jc = Get-Command java -ErrorAction SilentlyContinue; if ($jc) { $cands.Add($jc.Source) }
$bestJ = $null; $bestM = 0; $j17 = $null
foreach ($c in ($cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)) {
    $m = 0
    try { $o = (& $c -version 2>&1 | Out-String); if ($o -match 'version "(\d+)') { $m = [int]$Matches[1] } } catch { }
    if ($m -eq 17 -and -not $j17) { $j17 = $c }
    if ($m -ge 17 -and $m -gt $bestM) { $bestJ = $c; $bestM = $m }
}
$useJava = if ($j17) { $j17 } else { $bestJ }
$useMaj  = if ($j17) { 17 } else { $bestM }
if ($useJava) { Ok ("Java $useMaj（优先 17，因为 MC 1.20.1 的目标版本是它）：$useJava") }
else { Bad "没有 Java 17+（Forge 1.20.1 硬要求；PATH 上若是 16 会**静默**跑到启动即死）⇒ winget install EclipseAdoptium.Temurin.17.JDK" }

# --- 仓库（源码 + 能否 clone/拉取）---
if (Test-Path (Join-Path $devRepo "build.gradle")) {
    $head = ""
    try { $head = (& git -C $devRepo log -1 --format="%h %s" 2>&1 | Out-String).Trim() } catch { }
    Ok "仓库存在：$devRepo（HEAD：$head）"
    # ⚠️ github 的 HTTPS 在这类网络上常被重置（实测 `Recv failure: Connection was reset`）⇒ 提前说出来
    try {
        $rc = (& git -C $devRepo ls-remote --exit-code origin HEAD 2>&1 | Out-String)
        # ⚠️ git 的报错走 stderr、且带换行 —— 只取第一行，否则后面整段输出会被拼进这一行里
        $rcOne = ($rc -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
        if ($LASTEXITCODE -eq 0) { Ok "git 能连 origin" }
        else { Bad "git 连不上 origin ⇒ 常见解：把 remote 换成 SSH（ssh.github.com:443），或改用 bundle/物理媒介同步"; Note ("读数：" + $rcOne) }
    } catch { Bad "git 连 origin 失败 ⇒ 同上" }
} else { Bad "找不到仓库：$devRepo（应先 clone；主工作流侧的 tools/ 才是最新的）" }

# --- Alice jar（仓库产物优先，客户端 mods 兜底 —— 测试机通常不构建）---
$jarSrc = ""
if (Test-Path (Join-Path $devRepo "build\libs")) {
    $a = Get-ChildItem (Join-Path $devRepo "build\libs") -Filter "alice-*.jar" -ErrorAction SilentlyContinue |
         Where-Object { $_.Name -notlike "*sources*" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($a) { $jarSrc = $a.FullName }
}
if (-not $jarSrc -and (Test-Path (Join-Path $devRoot "mods"))) {
    $a = Get-ChildItem -LiteralPath (Join-Path $devRoot "mods") -Filter "alice-*.jar" -ErrorAction SilentlyContinue |
         Where-Object { $_.Name -notlike "*.bak.*" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($a) { $jarSrc = $a.FullName }
}
if ($jarSrc) {
    $ji = Get-Item -LiteralPath $jarSrc
    Ok ("Alice jar：" + $ji.Name + "（" + $ji.LastWriteTime.ToString("yyyy-MM-dd HH:mm") + "，" + [math]::Round($ji.Length/1MB,2) + " MB）")
    Note "⚠️ 判断它新不新只能靠时间戳 —— 主工作流改完代码要**重新构建并拷过来**（测试机不构建）"
} else { Bad "找不到 Alice jar（仓库 build\libs 与客户端 mods 都没有）⇒ 让主工作流构建后拷过来" }

# --- 客户端根：存档 + mod ---
$saveDir = Join-Path $devRoot "saves"
$modDir  = Join-Path $devRoot "mods"
if (Test-Path $modDir) {
    $mj = @(Get-ChildItem -LiteralPath $modDir -Filter *.jar -ErrorAction SilentlyContinue)
    if ($mj.Count -ge 15) { Ok "客户端 mods：$($mj.Count) 个 jar" }
    else { Bad "客户端 mods 只有 $($mj.Count) 个 jar（预期 20：含 4 个名字带 [ ] 的）⇒ 用物理媒介补" }
} else { Bad "客户端 mods 目录不存在：$modDir" }
if (Test-Path $saveDir) {
    $sv = @(Get-ChildItem -LiteralPath $saveDir -Directory -ErrorAction SilentlyContinue)
    $withLevel = @($sv | Where-Object { Test-Path (Join-Path $_.FullName "level.dat") })
    if ($withLevel.Count -ge 1) { Ok "存档母本：$($withLevel[0].Name)（level.dat 在）" }
    else { Bad "saves\ 下没有带 level.dat 的存档 ⇒ 世界母本建不出来（电池会起不来）" }
} else { Bad "saves 目录不存在：$saveDir" }

# --- 服务端：**不必装**，但 libraries + win_args.txt 必须在（那是已装好的那份平移过来的）---
$wargs = Join-Path $devServer "libraries\net\minecraftforge\forge\1.20.1-47.4.10\win_args.txt"
if (Test-Path $wargs) {
    $libs = @(Get-ChildItem (Join-Path $devServer "libraries") -Recurse -File -ErrorAction SilentlyContinue)
    Ok "服务端库就位：$($libs.Count) 个文件 + win_args.txt"
} else { Bad "缺服务端库（$wargs）⇒ 把主工作流 alice-server\libraries 整棵拷过来即可，**不用跑 Forge 安装器**" }
if (Test-Path (Join-Path $devServer "world-pristine")) { Ok "世界母本已建（world-pristine）" } else { Note "世界母本还没建 ⇒ 第一次跑 -Headless 时会自动建" }

# --- 磁盘 ---
try {
    $dl = (Get-Item $devServer -ErrorAction SilentlyContinue).PSDrive.Name
    if (-not $dl) { $dl = "D" }
    $v = Get-Volume -DriveLetter $dl -ErrorAction SilentlyContinue
    if ($v) {
        $freeGB = [math]::Round($v.SizeRemaining/1GB, 1)
        if ($freeGB -ge 5) { Ok "磁盘 $dl`: 剩余 $freeGB GB" } else { Bad "磁盘 $dl`: 只剩 $freeGB GB（世界母本 + 每轮 world 要几 GB）" }
    }
} catch { Skip "磁盘检查跳过" }

# --- dsh（管家本体）---
if (Get-Command dsh -ErrorAction SilentlyContinue) { Ok "dsh 可用（管家本体在）" }
else {
    if ($Repair) {
        if (Get-Command npm -ErrorAction SilentlyContinue) {
            if ($DryRun) { Fixed "（DryRun）会 npm i -g @deepseek-ai/dsh@0.1.5-rc.3（约 3 分钟）" }
            else {
                Note "正在装 dsh（约 3 分钟，走本机网络）…"
                $p = Start-Process -FilePath "npm" -ArgumentList @("i","-g","@deepseek-ai/dsh@0.1.5-rc.3") -PassThru -Wait -NoNewWindow
                if ($p.ExitCode -eq 0) { Fixed "dsh 已装（重开一个终端才进 PATH）" } else { Bad "npm 装 dsh 失败（exit=$($p.ExitCode)）" }
            }
        } else { Bad "没有 npm ⇒ 先装 Node.js 22+" }
    } else { Bad "没有 dsh（管家跑不起来）⇒ 加 -Repair 自动装，或手动 npm i -g @deepseek-ai/dsh@0.1.5-rc.3" }
}

# --- 管家本体能不能**叫动模型**（⭐ 2026-10-02 新设备实测的真坎：装了 dsh 也跑不了）---
#    `dsh --profile headless "<任务>"` 需要两个文件；缺任一个 ⇒ 管家**起得来但答不出话**（不报错到肉眼可见）。
$dshHome = if ($env:DSH_HOME) { $env:DSH_HOME } else { Join-Path $HOME ".dsh" }
$setY = Join-Path $dshHome "settings.yaml"
$credY = Join-Path $dshHome ".credentials.yaml"
if (Test-Path $setY) { Ok "dsh settings.yaml 在（含模型/插件配置）" }
else { Bad "缺 $setY ⇒ 复制主工作流那份过来（它不含本机绝对路径，可直接搬）" }
if (Test-Path $credY) { Ok "dsh 凭据在（模型 API key）" }
else { Bad "缺 $credY ⇒ 管家**调不了模型**（装了 dsh 也白装）⇒ 从主工作流 $HOME/.dsh/.credentials.yaml 传过来（走物理媒介或同网段 scp，⛔ 别贴进聊天）" }
# 探针：能不能真答一句（只在 -Repair 时跑，避免体检本身花 token）
if ($Repair -and (Test-Path $setY) -and (Test-Path $credY) -and (Get-Command dsh -ErrorAction SilentlyContinue)) {
    if ($DryRun) { Fixed "（DryRun）会跑一次 dsh 探针（花几个 token）" }
    else {
        try {
            $probe = (& dsh --profile headless "Reply with exactly: STEWARD_OK" 2>&1 | Out-String)
            if ($probe -match "STEWARD_OK") { Fixed "管家探针通过：dsh 能叫动模型" }
            else { Bad ("管家探针没回预期文本 ⇒ dsh 起得来但答不出；读数：" + (($probe -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 1))) }
        } catch { Bad "管家探针异常 ⇒ 看 -Quiet 去掉后的原始报错" }
    }
} elseif (Get-Command dsh -ErrorAction SilentlyContinue) { Note "管家探针未跑（加 -Repair 会跑一次，花几个 token）" }

# --- Java 的代理（⛔ Java **不读** HTTP_PROXY 环境变量；只有 JAVA_TOOL_OPTIONS/-D 才算）---
if ($env:HTTPS_PROXY -or $regProxy) {
    $jto = [Environment]::GetEnvironmentVariable('JAVA_TOOL_OPTIONS','User')
    if ($jto -and $jto -match 'proxyHost') { Ok "JAVA_TOOL_OPTIONS 已设（Java 下载会走代理）：$jto" }
    elseif ($Repair) {
        $px = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $regProxy }
        if ($px -match '^https?://([^:/]+)(?::(\d+))?') {
            $jit = "-Dhttp.proxyHost=$($Matches[1]) -Dhttp.proxyPort=$(if($Matches[2]){$Matches[2]}else{'80'}) -Dhttps.proxyHost=$($Matches[1]) -Dhttps.proxyPort=$(if($Matches[2]){$Matches[2]}else{'80'})"
            if ($DryRun) { Fixed "（DryRun）会设 JAVA_TOOL_OPTIONS=$jit" }
            else { [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $jit, 'User'); $env:JAVA_TOOL_OPTIONS = $jit; Fixed "已设 JAVA_TOOL_OPTIONS（否则 Forge 装库会卡死）" }
        }
    } else { Bad "Java 看不到代理（Java 不读 HTTP_PROXY）⇒ 加 -Repair 写 JAVA_TOOL_OPTIONS" }
} else { Skip "本机没开代理 ⇒ 若下载卡死，再回来设 JAVA_TOOL_OPTIONS" }

# ============================ 结论 ============================
Write-Host ""
$verdict = if ($script:bad -eq 0) { "全绿" } else { "有 $($script:bad) 项 FAIL" }
$col = if ($script:bad -eq 0) { "Green" } else { "Red" }
Write-Host ("=== 结论：$verdict（OK=$script:ok FIX=$script:fix FAIL=$script:bad SKIP=$script:skip）===") -ForegroundColor $col
if ($script:bad -gt 0) {
    Note "下一步：① 带 -Repair 再跑一次（能自己修的都修掉）· ② 还红就把**整段输出**发给主工作流"
    Note "        ③ 最底层兜底：client-agent.cmd -SelfTest 会走一遍 gh↔云端的实测往返"
}
if ($script:bad -eq 0) { exit 0 } else { exit 1 }
