<#
.SYNOPSIS
  在新设备上把云端 DSH 的"回环入口"挂起来（**只用 Windows 自带的 PowerShell，不装 WSL**）。

.DESCRIPTION
  为什么必须走回环：DSH 客户端把设置镜像的持久化绑在页面 hostname 上
  （dsh-client-ui-settings/lib/client.js:1345  persistence = isLoopback ? "host" : "memory"）
  ⇒ 用 https://<名字>-3081.app.github.dev 打开时对话能用，但**模型/插件配置永远打不开**。
  本脚本把云端 3081 转发到本机 127.0.0.1:<本地端口> ⇒ 浏览器看到回环 ⇒ 设置页可用。

  第一次在新设备上跑它会：① 没有 gh 就问你一句、同意后用 winget 装；
  ② 没有 PAT 就提示你粘贴一次（存到 %USERPROFILE%\.gh-token，并尽量收紧 ACL）；③ 挂隧道并打印链接。

.PARAMETER Codespace
  codespace 名。默认是 Alice 零期用的那台。

.PARAMETER LocalPort
  本机端口，默认 3181（避开本机 DSH 的 3081）。

.PARAMETER Pat
  直接给 PAT（不想交互时用）。也可以走环境变量 DSH_PAT / GH_TOKEN。

.PARAMETER Open
  挂好后用默认浏览器打开链接。

.PARAMETER Stop
  停掉本脚本之前挂的转发，然后退出。

.EXAMPLE
  codespace-tunnel.cmd -Open
.EXAMPLE
  codespace-tunnel.cmd -Stop
#>
[CmdletBinding()]
param(
    [string]$Codespace = "humble-tribble-97pv59gw5rg62prg5",
    [int]$LocalPort = 3181,
    [int]$RemotePort = 3081,
    [string]$Pat = "",
    [switch]$Open,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$PidFile = Join-Path $HOME ".dsh-cloud-tunnel.pid"
$TokenFile = Join-Path $HOME ".gh-token"

function Info($m) { Write-Host "-> $m" }
function Warn($m) { Write-Host "!! $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "xx $m" -ForegroundColor Red; exit 1 }

# ---------- -Stop ----------
if ($Stop) {
    if (Test-Path $PidFile) {
        foreach ($p in (Get-Content $PidFile)) {
            if ($p -match '^\d+$') { Stop-Process -Id ([int]$p) -Force -ErrorAction SilentlyContinue }
        }
        Remove-Item $PidFile -Force
        Info "已停掉记录的转发进程"
    } else { Info "没有记录（$PidFile）" }
    exit 0
}

# ---------- 0) gh ----------
function Resolve-Gh {
    $cmd = Get-Command gh -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $guess = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
    if (Test-Path $guess) { $env:Path = (Split-Path $guess) + ";" + $env:Path; return $guess }
    return $null
}
$gh = Resolve-Gh
if (-not $gh) {
    Write-Host "没有找到 gh（GitHub CLI）。它可以用 winget 装（约 15 MB，装在 $env:ProgramFiles\GitHub CLI）。"
    $ans = Read-Host "现在装吗? [y/N]"
    if ($ans -match '^(y|Y)') {
        $wp = Get-Command winget -ErrorAction SilentlyContinue
        if (-not $wp) { Die "这台机器没有 winget; 请手动装 gh 后重跑 (https://cli.github.com/)" }
        & winget install --id GitHub.cli -e --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -ne 0) { Die "winget 安装失败（退出码 $LASTEXITCODE）" }
        $env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")
        $gh = Resolve-Gh
        if (-not $gh) { Warn "装完了但这个窗口还没拿到 PATH; 请关掉窗口重开一次再跑本脚本"; exit 2 }
    } else {
        Die "已取消; 手动装法: winget install --id GitHub.cli -e"
    }
}
Info "gh = $(& $gh --version | Select-Object -First 1)"

# ---------- 1) 认证（PAT） ----------
if (-not $env:GH_TOKEN) {
    $cand = ""
    if ($Pat) { $cand = $Pat } elseif ($env:DSH_PAT) { $cand = $env:DSH_PAT }
    elseif (Test-Path $TokenFile) { $cand = (Get-Content $TokenFile -Raw).Trim(); Info "认证 = PAT（读自 $TokenFile，内容不打印）" }
    if (-not $cand) {
        Write-Host ""
        Write-Host "需要一次性 GitHub PAT（classic, scope 至少 codespace; 建议 repo+codespace）。"
        Write-Host "在能访问 github.com 的设备上生成: https://github.com/settings/tokens/new?scopes=repo,codespace"
        Write-Host "（本机网络若不通 github.com，这一步要在别的设备上做，然后把 PAT 带过来）"
        $cand = (Read-Host "粘贴 PAT（输入内容会显示，粘完回车）").Trim()
        if (-not $cand) { Die "没有 PAT，无法继续" }
        Set-Content -NoNewline -Path $TokenFile -Value $cand
        cmd /c "icacls `"$TokenFile`" /inheritance:r /grant:r `"$env:USERNAME:(R,W)`"" | Out-Null
        Info "已存到 $TokenFile（并尽量收紧为仅本人可读写）"
    }
    $env:GH_TOKEN = $cand
}
$login = (& $gh api /user --jq .login 2>$null)
if (-not $login) { Die "认证不通（PAT 过期 / 缺 codespace scope / 网络到不了 api.github.com）" }
Info "账号 = $login"

# ---------- 2) 确保远端 dsh web 在跑，并取回令牌 ----------
# gh 会把 `--` 之后的参数用空格拼成一个字符串 ⇒ 这里**只传一个参数**（PowerShell 串 = 一个参数）。
$remote = "cd /workspaces/Alice-mcbot 2>/dev/null || cd ~/projects/alice; " +
          "pgrep -f 'bin/dsh web' >/dev/null || (nohup env DSH_WORKDIR=/workspaces tools/codespace-start-dsh.sh >> ~/dsh-web.log 2>&1 &); " +
          "sleep 8; grep -o 'token=[A-Za-z0-9_-]*' ~/dsh-web.log | tail -1"
Info "唤醒 codespace + 确保远端服务在跑（首次 10-30 秒）..."
$token = $null
$tokenLine = (& $gh codespace ssh -c $Codespace -- $remote 2>$null | Select-Object -Last 1)
if ($tokenLine -match 'token=([A-Za-z0-9_-]+)') { $token = $Matches[1] }
if (-not $token) { Warn "没读到令牌（服务可能刚重启）⇒ 稍后重跑本脚本，或看远端 ~/dsh-web.log" }

# ---------- 3) 挂转发（优先 ssh -L = 只绑回环） ----------
if (Test-Path $PidFile) {
    foreach ($p in (Get-Content $PidFile)) { if ($p -match '^\d+$') { Stop-Process -Id ([int]$p) -Force -ErrorAction SilentlyContinue } }
    Remove-Item $PidFile -Force
}
$newPids = @()
if (Get-Command ssh -ErrorAction SilentlyContinue) {
    Info "挂 ssh 转发（127.0.0.1:$LocalPort -> 云端 $RemotePort，只绑回环）..."
    $proc = Start-Process -FilePath $gh -WindowStyle Hidden -PassThru -ArgumentList @(
        "codespace","ssh","-c",$Codespace,"--","-N",
        "-o","ServerAliveInterval=30","-o","ServerAliveCountMax=6","-o","ExitOnForwardFailure=yes",
        "-L","$LocalPort`:127.0.0.1:$RemotePort")
    $newPids += $proc.Id
} else {
    Warn "没有 ssh.exe ⇒ 退到 gh 原生转发（注意: 它绑在所有网卡，局域网内可访问该端口）"
    $proc = Start-Process -FilePath $gh -WindowStyle Hidden -PassThru -ArgumentList @(
        "codespace","ports","forward","$RemotePort`:$LocalPort","-c",$Codespace)
    $newPids += $proc.Id
}
$newPids | Set-Content $PidFile

# ---------- 4) 等就绪 + 打印链接 ----------
$base = "http://127.0.0.1:$LocalPort/"
$code = $null
foreach ($i in 1..20) {
    Start-Sleep -Seconds 2
    $code = (& curl.exe -s -o NUL -w "%{http_code}" --max-time 6 $base 2>$null)
    if ($code -eq "401" -or $code -eq "200" -or $code -eq "303") { break }
}
if ($code -eq "401" -or $code -eq "200" -or $code -eq "303") {
    Info "本地 $LocalPort 已就绪（HTTP $code；401 = 缺令牌，正常）"
} else {
    Warn "本地 $LocalPort 还没就绪（最后一次 HTTP=$code）。稍等重跑，或该端口被占用（用 -LocalPort 换一个）。"
}
$url = if ($token) { "$base`?token=$token" } else { $base }

Write-Host ""
Write-Host "  == 浏览器打开（回环入口 ⇒ 设置页才可用）==" -ForegroundColor Green
Write-Host ""
Write-Host "      $url"
Write-Host ""
Write-Host "  - 每次远端服务重启，令牌都会变 ⇒ 重跑本脚本拿新链接" -ForegroundColor DarkGray
Write-Host "  - 关掉转发: codespace-tunnel.cmd -Stop" -ForegroundColor DarkGray
Write-Host "  - 端口 $LocalPort 被占用时: -LocalPort 3182" -ForegroundColor DarkGray
Write-Host ""

if ($Open) { Start-Process $url }
