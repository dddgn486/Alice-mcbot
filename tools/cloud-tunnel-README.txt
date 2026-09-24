云端 DSH 隧道 —— 新设备用法（只用 Windows 自带 PowerShell，不需要 WSL）
=====================================================================

一、最快路径
  1) 把整个文件夹放到任意位置（例如桌面）。
  2) 双击 codespace-tunnel.cmd，或在 PowerShell 里执行：
         .\codespace-tunnel.cmd -Open
  3) 浏览器打开 http://127.0.0.1:3181/?token=...   ← 地址栏必须是 127.0.0.1

二、第一次用会有两个一次性步骤（脚本自己会问）
  · 没装 gh：脚本问 "现在装吗? [y/N]" → 输入 y（用 winget 装到 C:\Program Files\GitHub CLI）
  · 没有 PAT：脚本提示粘贴一次。PAT 要在能访问 github.com 的设备/网络上生成：
        https://github.com/settings/tokens/new?scopes=repo,codespace
    粘贴后脚本存到 %USERPROFILE%\.gh-token，并尽量收紧为仅本人可读写。

三、为什么一定要 127.0.0.1（重要，别跳过）
  DSH 客户端把「设置镜像」的持久化绑在页面 hostname 上：
        persistence = isLoopback ? "host" : "memory"
  用 https://<名字>-3081.app.github.dev 打开 = 非回环 ⇒ 对话能用，但
  〔设置 → 模型〕会报 "settings are unavailable in this browser"，〔插件配置〕打不开。
  走本脚本的 127.0.0.1 ⇒ 设置 / 模型 / 插件配置都正常。

四、日常命令
  .\codespace-tunnel.cmd -Open            挂隧道 + 打开页面
  .\codespace-tunnel.cmd -Stop            停掉转发
  .\codespace-tunnel.cmd -LocalPort 3182  3181 被占用时换端口
  每次「云端服务重启」，令牌都会变 ⇒ 重跑一次拿新链接。

五、出问题先看这几条
  · 页面显示 authentication required    → 你开的是不带 ?token= 的链接（Ports 面板那条必然如此）
  · 显示 settings are unavailable ...   → 入口不是回环（用了 HTTPS 转发域名）⇒ 用本脚本打印的链接
  · 127.0.0.1:<端口> 连不上             → 端口被占（换 -LocalPort）；或 codespace 被空闲停掉（重跑本脚本）
  · 报 not recognized as ... script file → 用本文件夹里的 .cmd，或写绝对路径（-File 不搜 PATH）
  · gh 找不到                            → 装完 gh 要新开一个终端，或让脚本用 winget 装

六、安全
  · PAT 存在 %USERPROFILE%\.gh-token，只在本机：别提交到 git、别贴到聊天里。
  · 隧道只绑 127.0.0.1（走 ssh 路线）；只有当机器上没有 ssh.exe 时才退到 gh 原生转发（那种会绑所有网卡）。
  · 用完想彻底断开：先 -Stop，再去 https://github.com/codespaces 停掉该 codespace。

七、客户端管家 agent（可选，云端开发的配套）
  · client-agent.cmd -Install   在 Windows 装 DSH(锁 0.1.5-rc.3) + 导入 preset（需 node，本机已有）
  · client-agent.cmd            唤醒本地管家 agent：读云端信箱 ~/bus/to-win 并照做
  · client-agent.cmd "上传最近一次测试的日志和截图"
  · client-info-watch.cmd       可选的自动 watcher（管家 agent 也可以调它做增量推日志）
  详见仓库 docs/CLIENT_AGENT_CHANNEL.md（信箱协议 / 回执四段 / 纪律）

八、包里的东西
  codespace-tunnel.cmd   包装脚本（从任何当前目录都能调；找不到 pwsh 会自动退回 Windows PowerShell）
  codespace-tunnel.ps1   主脚本（UTF-8 with BOM，Windows PowerShell 5.1 解析通过）
  怎么用.txt             本文件
  本机WSL专用/           只在那台 WSL 开发机上用的回迁脚本，新设备不需要
