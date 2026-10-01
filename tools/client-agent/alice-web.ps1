<#
.SYNOPSIS
  最朴素的 DSH 启动/关闭入口 —— 前台跑，**Ctrl+C 就是关闭**。

.DESCRIPTION
  为什么要单独做这个（2026-10-02 用户要求）：`dsh` 带一堆参数启动**经常一堆问题**
  （实测本机 Windows 侧 `dsh web` 直接起不来：`web` profile 里挂的第三方皮肤包
  `@dsh-external/dsh-client-ui-skin-maid-atelier` 没装 ⇒ `cannot resolve profile bundle`）。
  ⇒ 这里只用**一个**参数 `--no-open`，其余全走默认；前台运行 ⇒ **Ctrl+C 关**，不留后台进程。

  用法（双击 `alice-web.cmd` 等价）：
      alice-web.ps1              启动（前台；地址含 ?token=… 会打印在下面，整行复制进浏览器）
      alice-web.ps1 -Stop        关闭（杀掉占用该端口的进程）—— 前台那次的 Ctrl+C 之外的后手

  ⛔ 不做的：不改 profile、不装插件、不写任何配置、不后台常驻。
#>
[CmdletBinding()]
param(
    [switch]$Stop,
    [int]$Port = 3081
)
$ErrorActionPreference = "Continue"

# ⭐ 必须自己刷新 PATH：npm/winget 刚装完的东西只在**新终端**里可见（ssh/老窗口里看不到）
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
# 本地代理：node 侧有些库认 HTTP(S)_PROXY（gh/git 也认；⛔ Java 不认，那是另一套）
try {
    $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
    if ($reg -and ([int]$reg.ProxyEnable -eq 1) -and $reg.ProxyServer) {
        $srv = [string]$reg.ProxyServer
        if ($srv -notmatch '^https?://') { $srv = "http://$srv" }
        if (-not $env:HTTPS_PROXY) { $env:HTTPS_PROXY = $srv }
        if (-not $env:HTTP_PROXY)  { $env:HTTP_PROXY  = $srv }
    }
} catch { }

$dsh = Get-Command dsh -ErrorAction SilentlyContinue

if ($Stop) {
    Write-Host "── 关闭 DSH web（端口 $Port）" -ForegroundColor DarkCyan
    $conns = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($conns.Count -eq 0) { Write-Host "  没人在监听 $Port ⇒ DSH web 没在跑（无需关闭）" -ForegroundColor Green; exit 0 }
    foreach ($c in $conns) {
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        $name = if ($proc) { $proc.ProcessName } else { "?" }
        try {
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction Stop
            Write-Host ("  已停止 pid=" + $c.OwningProcess + "（" + $name + "）") -ForegroundColor Cyan
        } catch { Write-Host ("  停不掉 pid=" + $c.OwningProcess + "：" + $_.Exception.Message) -ForegroundColor Red }
    }
    Start-Sleep -Milliseconds 500
    if (@(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count -eq 0) {
        Write-Host "  端口 $Port 已空出" -ForegroundColor Green; exit 0
    } else { Write-Host "  端口 $Port 仍被占用 ⇒ 用管理员权限再跑一次" -ForegroundColor Red; exit 1 }
}

if (-not $dsh) {
    Write-Host "  [x] 没有全局 dsh ⇒ 先装一次：" -ForegroundColor Red
    Write-Host "      npm i -g @deepseek-ai/dsh@0.1.5-rc.3" -ForegroundColor Yellow
    exit 2
}

Write-Host "============================================================" -ForegroundColor DarkCyan
Write-Host "  Alice / DSH 网页界面" -ForegroundColor Cyan
Write-Host ("  启动命令：dsh web --no-open   （端口 " + $Port + "）") -ForegroundColor Gray
Write-Host "  ● 下面会打印一条**带 ?token=… 的地址** —— 整行复制进浏览器" -ForegroundColor Gray
Write-Host "  ● 关闭：在这个窗口按 Ctrl+C（或另开一个窗口跑 alice-web.cmd -Stop）" -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor DarkCyan
Write-Host ""

# ⭐ 前台运行：Ctrl+C 直接停，不留后台进程。
#   输出同时落一份日志（`%USERPROFILE%\alice\dsh-web.log`）—— 地址找不到了可以回头 grep token。
$logDir = Join-Path $HOME "alice"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$log = Join-Path $logDir "dsh-web.log"
Write-Host ("  日志（含地址）：" + $log) -ForegroundColor DarkGray
Write-Host ""
& dsh web --no-open --port $Port 2>&1 | Tee-Object -FilePath $log
$code = $LASTEXITCODE
Write-Host ""
if ($code -eq 0 -or $null -eq $code) { Write-Host "[alice] dsh web 已退出（Ctrl+C 就是关闭）" -ForegroundColor Green }
else {
    Write-Host ("[alice] dsh web 退出码 = $code") -ForegroundColor Yellow
    Write-Host ("        常见真因：web profile 里的插件包没装 ⇒ 读上面的 cannot resolve profile bundle；") -ForegroundColor Gray
    Write-Host ("        按提示跑 dsh plugin --profile web install，或先 dsh --dump-config 看 profile 树。") -ForegroundColor Gray
}
exit $code
