﻿<#
.SYNOPSIS
  新设备一次性安装：装 DSH、导入管家 preset、**发现本机客户端路径并写进配置文件**。
  由 `client-agent.cmd -Install` 调用；可重复跑（幂等）。
#>
[CmdletBinding()]
param(
    [string]$Codespace = "humble-tribble-97pv59gw5rg62prg5",
    [string]$ClientRoot = "",
    [string]$DshVersion = "0.1.5-rc.3"
)
$ErrorActionPreference = "Continue"
function Info($m) { Write-Host "[install] $m" }
function Warn($m) { Write-Host "[install] !! $m" -ForegroundColor Yellow }

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Warn "没有 node（先装 Node.js 22+）"; exit 2 }
Info ("node = " + (node --version))
if (-not (Get-Command dsh -ErrorAction SilentlyContinue)) {
    Info "装 DSH（锁 $DshVersion）..."
    npm i -g "@deepseek-ai/dsh@$DshVersion" | Out-Null
    if (-not (Get-Command dsh -ErrorAction SilentlyContinue)) { Warn "dsh 装完但不在 PATH（重开一个终端再跑）"; exit 2 }
} else { Info ("dsh = " + ((dsh --version) 2>&1)) }

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
$cfg = @{
    codespace  = $Codespace
    clientRoot = $ClientRoot
    mailbox    = @{ toWin = "/home/vscode/bus/to-win"; toCloud = "/home/vscode/bus/to-cloud"
                    clientInfo = "/home/vscode/client-info"; outbox = "/home/vscode/outbox" }
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
