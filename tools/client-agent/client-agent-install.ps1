<#
.SYNOPSIS
  新设备一次性安装：装 DSH、导入管家 preset、**发现本机客户端路径并写进配置文件**。
  由 `client-agent.cmd -Install` 调用；可重复跑（幂等）。
#>
[CmdletBinding()]
param(
    [string]$Codespace = "humble-tribble-97pv59gw5rg62prg5",
    [string]$ClientRoot = "",
    [string]$DshVersion = "0.1.5-rc.3",
    [string]$ProxyUrl = ""   # 本地代理，如 http://127.0.0.1:7897；空 = 自动从系统代理读
)
$ErrorActionPreference = "Continue"

# ⭐ 必须自己刷新 PATH：winget/npm 刚装完的东西只在**新终端**里可见，
#    从别处（WSL 调用、计划任务、刚装完就双击）启动时 PATH 是旧的 ⇒ 会出现"明明装了却说没有"。
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')

function Info($m) { Write-Host "[install] $m" }
function Warn($m) { Write-Host "[install] !! $m" -ForegroundColor Yellow }

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Warn "没有 node（先装 Node.js 22+）"; exit 2 }
Info ("node = " + (node --version))
$dshOk = $false
if (Get-Command dsh -ErrorAction SilentlyContinue) { Info ("全局 dsh = " + ((dsh --version) 2>&1)); $dshOk = $true }
elseif (Get-Command npx -ErrorAction SilentlyContinue) {
    # ⭐ 不强制装全局包：npx 缓存里可能已有（本机 Windows 就是 npx 跑的 rc.2）
    foreach ($v in @($DshVersion, "0.1.5-rc.2", "0.1.5-rc.3")) {
        $probe = (& npx --no-install "@deepseek-ai/dsh@$v" --help 2>&1) -join " "
        if ($probe -match "profile|Usage") { Info "npx 缓存里已有 @deepseek-ai/dsh@$v ⇒ 跳过全局安装（client-agent.cmd 会自动走 npx）"; $dshOk = $true; break }
    }
}
if (-not $dshOk) {
    Info "装 DSH（锁 $DshVersion，约几分钟）..."
    npm i -g "@deepseek-ai/dsh@$DshVersion" | Out-Null
    if (-not (Get-Command dsh -ErrorAction SilentlyContinue)) { Warn "dsh 装完但不在 PATH（重开终端再跑）"; exit 2 }
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$preset = Join-Path $here "presets\alice-client-master"
$dst = Join-Path $HOME ".dsh\.agent-presets\alice-client-master"
if (Test-Path $preset) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    Remove-Item $dst -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item $preset $dst -Recurse -Force
    Info "preset 已导入：$dst"
} else { Warn "包内没有 presets\alice-client-master（要在解压后的包里跑）" }

if (-not $ClientRoot) {
    $cands = @()
    foreach ($base in @("D:\JAVA_projects\worldedit-test\versions","C:\JAVA_projects\worldedit-test\versions","$env:APPDATA\.minecraft\versions")) {
        if (Test-Path $base) {
            $cands += Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Where-Object {
                $_.Name -like "1.20.1-Forge_*" -and (Test-Path (Join-Path $_.FullName "logs")) -and (Test-Path (Join-Path $_.FullName "mods"))
            }
        }
    }
    if ($cands.Count -ge 1) {
        $ClientRoot = ($cands | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
        Info "扫到客户端：$ClientRoot"
    } else {
        Warn "没扫到 versions\1.20.1-Forge_<ver>（含 logs/ + mods/）"
        $ClientRoot = (Read-Host "请粘贴客户端安装目录").Trim()
    }
}
# ---------- 本地代理（⭐ 关键：gh 是 Go，只认 HTTP(S)_PROXY，**不读** Windows 系统代理） ----------
# 实测（2026-09-24）：Windows 开着本地代理（注册表 ProxyEnable=1 / ProxyServer=127.0.0.1:7897）时，
# 浏览器能上 GitHub，但 gh 直连会超时 ⇒ host 管家读信箱、bus-watch 巡检都会**间歇性失败**。
# ⚠️ 这里写的是**用户级**环境变量（新开的终端/计划任务才继承；本脚本内也会立即生效）。
$proxyUrl = ""
if ($ProxyUrl) { $proxyUrl = $ProxyUrl }
else {
    $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
    if ($reg -and ([int]$reg.ProxyEnable -eq 1) -and $reg.ProxyServer) {
        $srv = [string]$reg.ProxyServer
        if ($srv -notmatch '^https?://') { $srv = "http://$srv" }
        $proxyUrl = $srv
    }
}
if ($proxyUrl) {
    Info "检测到本地代理：$proxyUrl ⇒ 写入配置 + 设用户级 HTTP(S)_PROXY（gh 只认环境变量，不认系统代理）"
    foreach ($k in @("HTTP_PROXY","HTTPS_PROXY")) { [Environment]::SetEnvironmentVariable($k, $proxyUrl, "User") }
    [Environment]::SetEnvironmentVariable("NO_PROXY", "localhost,127.0.0.1,::1", "User")
    $env:HTTP_PROXY = $proxyUrl; $env:HTTPS_PROXY = $proxyUrl
} else {
    Warn "没检测到本地代理（gh 直连）。若你在用代理，用 -ProxyUrl http://127.0.0.1:7897 再跑一次本脚本"
}

$cfg = @{
    codespace  = $Codespace
    clientRoot = $ClientRoot
    mailbox    = @{ toWin = "/home/vscode/bus/to-win"; toCloud = "/home/vscode/bus/to-cloud"
                    clientInfo = "/home/vscode/client-info"; outbox = "/home/vscode/outbox" }
    proxy      = $proxyUrl
    writtenAt  = (Get-Date).ToString("s"); machine = $env:COMPUTERNAME
}
$cfgPath = Join-Path $HOME ".alice-client.json"
$cfg | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $cfgPath
Info "配置已写：$cfgPath"

$tok = Join-Path $HOME ".gh-token"
if (Test-Path $tok) {
    # 实测：PAT scope = codespace, repo（没有 read:org）⇒ `gh auth login --with-token` 会被 gh 拒绝。
    # 正解 = 把 token 写进 gh 自己的凭据文件（Windows: %APPDATA%\GitHub CLI\hosts.yml）。
    $ghDir = Join-Path $env:APPDATA "GitHub CLI"
    New-Item -ItemType Directory -Force -Path $ghDir | Out-Null
    $hosts = Join-Path $ghDir "hosts.yml"
    if (Test-Path $hosts) { Copy-Item $hosts "$hosts.bak-pre-alice" -Force }
    $pat = (Get-Content $tok -Raw).Trim()
    ("github.com:`n    oauth_token: $pat`n    git_protocol: ssh`n") | Set-Content -Encoding UTF8 $hosts
    $login = (& gh api /user --jq .login 2>$null)
    if ($login) { Info "gh 认证 OK：$login（凭据写入 $hosts）" } else { Warn "gh 不可用：兜底设置环境变量 GH_TOKEN" }
} else { Warn "缺 $tok（GitHub PAT，需要 codespace + repo scope）—— 连不上云端信箱" }

# 让管家 preset 成为本机默认（备份原 settings.yaml）
$st = Join-Path $HOME ".dsh\settings.yaml"
if (Test-Path $st) {
    Copy-Item $st "$st.bak-pre-client-agent" -Force
    $y = Get-Content $st -Raw
    if ($y -match "(?m)^agent-presets:\s*$") {
        $y = [regex]::Replace($y, "(?m)^(agent-presets:\s*\n(?:\s+.*\n)*?\s+default:\s*)\S+", "`$1alice-client-master")
    } else { $y = $y.TrimEnd() + "`nagent-presets:`n  default: alice-client-master`n" }
    Set-Content -Encoding UTF8 $st $y
    Info "默认 preset → alice-client-master（原 settings 已备份）"
} else { Warn "没有 %USERPROFILE%\.dsh\settings.yaml（先在 DSH 里跑一次生成）" }
if (-not (Test-Path (Join-Path $HOME ".dsh\.credentials.yaml"))) { Warn "缺 %USERPROFILE%\.dsh\.credentials.yaml（API key）—— 调不了模型" }
Info "完成。试跑: client-agent.cmd `"读信箱并照做`""
