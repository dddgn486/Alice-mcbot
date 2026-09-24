# 新设备测试手册：客户端管家 agent（逐字照做版）

> 目标：在一台**新 Windows 机器**上把"本地 agent ↔ 云端信箱"跑通，并让它按你的要求上传客户端测试数据。
> 设计背景见 `docs/CLIENT_AGENT_CHANNEL.md`；本文只讲**怎么测**。
> ⚠️ 先记住一条：**上传不需要全自动** —— 你什么时候唤醒它、让它传什么，由你决定。

---

## 0. 前提（新设备上必须有的东西）

| 需要 | 怎么确认 | 没有怎么办 |
|---|---|---|
| Node.js 22+ | `node --version` | `winget install OpenJS.NodeJS.LTS` |
| GitHub CLI | `gh --version` | `winget install --id GitHub.cli -e` |
| PAT 文件 `%USERPROFILE%\.gh-token` | `dir %USERPROFILE%\.gh-token` | 从这台机器拷一份（**需 `codespace` + `repo` scope**）；或新建 PAT |
| 模型凭据 `%USERPROFILE%\.dsh\.credentials.yaml` | `dir %USERPROFILE%\.dsh\.credentials.yaml` | 从这台机器拷一份（**含 API key，别进 git**） |
| **客户端本体**（`versions\1.20.1-Forge_*`，含 `logs\` 与 `mods\`） | 安装脚本会自己扫 | 没有客户端也能装，但"传日志/截图"就没内容可传 |

⚠️ **PATH 陷阱**：winget/npm **刚装完的东西**只有**新开的终端**才看得见。仓库里的三个脚本已经会自己刷新 PATH（`Machine` + `User`），所以从 `.cmd` 启动不会踩这个坑；但你**手动敲命令**时如果报"找不到 gh/dsh"，先关掉终端重开一个。

---

## 1. 把包拷过去（1 分钟）

从这台机器拷 `alice-cloud-tunnel-<日期>.zip`（桌面 + `D:\JAVA_projects\alice-backups\` 各有一份）到新设备，**解压**（别在 zip 里直接跑）。

包里你会看到：`codespace-tunnel.*`（看云端 UI）、`client-agent.*`（**本手册的主角**）、`presets\alice-client-master\`、`client-info-watch.*`（可选的自动 watcher，管家 agent 可以不靠它）。

---

## 2. 安装（`client-agent.cmd -Install`，2–5 分钟）

在新设备上打开 **cmd**，`cd` 到解压目录，然后：

```bat
client-agent.cmd -Install
```

它会依次做 5 件事（每件都打印 `[install] …`）：

| 步骤 | 做什么 | 你要注意 |
|---|---|---|
| ① | 检查 node；没有 `dsh` 就 `npm i -g @deepseek-ai/dsh@0.1.5-rc.3` | 第一次装会下 200+ 依赖，慢是正常的 |
| ② | 把 `presets\alice-client-master\` 拷进 `%USERPROFILE%\.dsh\.agent-presets\` | preset = **目录**（`preset.yml` + `agent.cordis.yml`） |
| ③ | **自己扫客户端**：`D:\JAVA_projects\worldedit-test\versions` 等位置里找 `1.20.1-Forge_*` 且同时有 `logs\`+`mods\` 的目录 | 扫不到会**停下来让你粘贴路径**（这是设计好的，别惊讶） |
| ④ | 把 codespace 名 + 客户端路径 + 信箱路径写进 `%USERPROFILE%\.alice-client.json` | ⭐ 这是 agent 的**唯一事实来源**（提示词里不写死路径） |
| ⑤ | 用 PAT 写 gh 凭据（`%APPDATA%\GitHub CLI\hosts.yml`，原文件先备份）并 `gh api /user` 验证；再把默认 preset 设成 `alice-client-master`（`settings.yaml` 先备份） | 若提示"缺 `.credentials.yaml`" ⇒ 现在拷过去 |

**预期结尾**：`[install] 完成。试跑: client-agent.cmd "读信箱并照做"`。

> ⚠️ 这台机器的 Windows 侧**还没跑过 `-Install`**（我只在 WSL 侧用同样的逻辑手动验证过）⇒ 第一次跑若报错，**把整段输出贴给我**（见 §8）。

---

## 3. 自检（`client-agent.cmd -SelfTest`）—— **不花钱，先证明管道通**

```bat
client-agent.cmd -SelfTest
```

它按"本机 → 云端"的顺序逐项验，**每一项都会打印 OK/FAIL 和修法**：

| 项 | 通过时的样子 | 失败时怎么办 |
|---|---|---|
| node / dsh | `[ OK ] node v24…` `[ OK ] dsh 可用：…` | dsh 没有 ⇒ 回到 §2 |
| 配置 | `[ OK ] 配置存在：…\.alice-client.json` | 没有 ⇒ 回 §2（不要手写，让安装脚本写） |
| 客户端形状 | `[ OK ] 客户端目录形状正确：D:\…（含 logs/ 与 mods/）` | 形状不对 ⇒ 用 `-ClientRoot` 指定或回 §2 粘贴路径 |
| PAT / gh | `[ OK ] PAT 文件存在` `[ OK ] gh 可用` `[ OK ] gh 已认证：dddgn486` | 未认证 ⇒ 照它打印的两行命令把 PAT 写进 `hosts.yml`（**别用 `gh auth login`**：本 PAT 缺 `read:org`，实测会被拒） |
| codespace | `[ OK ] codespace 'humble-…' 状态 = Available` | 名字错 ⇒ 用 `gh codespace list` 查；状态 `Shutdown` 也正常（ssh 会自动唤醒） |
| 信箱目录 | `[ OK ] 云端信箱四个目录都在` | 让云端建：`gh codespace ssh -c <名> -- 'mkdir -p ~/bus/to-win ~/bus/to-cloud ~/client-info ~/outbox'` |
| 读信箱 | `[ OK ] 读信箱 OK，里面有：…selfcheck.md` | 空/失败 ⇒ 看 gh 原始报错 |
| **写信箱** ⭐ | `[ OK ] 写信箱 OK 并读回（/home/vscode/bus/to-cloud/selftest-….md）` | 这一项红 = **回执送不出去**，必须先修（远端路径必须绝对） |
| 附件通道 | `[ OK ] 附件通道 OK（latest.log 12345 字节，两端一致）` | 字节不一致 ⇒ 传输被截断，先别传大文件 |

**最后一行**要么 `=== 全绿：N 项通过 => 可以唤醒 agent ===`（退出码 0），要么 `=== N 项失败 => 先按上面的提示修，不要先怀疑 agent ===`（退出码 1）。
脚本会写一份 **UTF-8 日志**并打印路径（`%TEMP%\client-agent-selftest-<时间>.log`）⇒ **把那个路径发我，我直接读**（不用你复制粘贴控制台）。

> 已实测的"装之前"状态（这台机器的 Windows 侧）：`node ✓ / gh ✓ / PAT ✓ / dsh ✗ / 配置 ✗ / gh 未认证 ✗` ⇒ 自检会准确报出来并给修法。

---

## 4. 唤醒 agent（单发，跑一次就退出）

```bat
client-agent.cmd                                  :: 默认任务 = 读信箱并照做
client-agent.cmd "只读信箱并写回执，不要上传任何附件"   :: 第一次建议用这条（最保守）
```

底层 = `dsh --profile headless "<任务>"`（跑一次、打印结果、退出）。
**通过的标准（在云端看，不在这台机器上看）**：`~/bus/to-cloud/` 里多出一份 `.md`，内含四段：
`做了什么 / 上传了什么（本地路径→远端路径+字节）/ 没做什么与原因 / 需要用户操作的`，且 `~/bus/to-win/.done` 记下了已处理的消息名。

我在本机 WSL 上已跑通这条（第 3 次试跑，回执落在云端）⇒ 你在新设备上应当看到同样的形状。

---

## 5. 真实数据测试（点名上传）

先在**云端**放一条请求（我这边或你手动）：

```bash
# 云端（codespace 内）或让云端 agent 写
cat > ~/bus/to-win/$(date -u +%Y%m%d-%H%M%S)-need-client-data.md <<'EOF'
# 请求：客户端测试数据（tag = test1）
- 要：logs/latest.log（全文）+ 最近 3 张 screenshots/*.png + logs/debug.log（全文）
- 存放：/home/vscode/client-info/test1/
- 回执：~/bus/to-cloud/，四段
EOF
```

然后在新设备上唤醒：

```bat
client-agent.cmd
```

**通过标准**（在云端核）：

1. `ls -l ~/client-info/test1/` 有文件，且**字节数与本机一致**；
2. 回执里写清了每个文件的"本地路径 → 远端路径 + 字节数"；
3. **能从云端读**：`cat ~/client-info/test1/logs/latest.log | head` 有内容；
4. **能从云端看截图**：让云端 agent 用 `read_image` 打开 `~/client-info/test1/screenshots/*.png` ⇒ 它应当**描述得出画面**
   （这是"截图数据传过去有用"的判据，不是文件存在就算过）。

⚠️ 别让它整目录上传：截图单张 5–7 M、69 张共 252 M（实测）。首次建议只点 3 张。

---

## 6. 反向通道（云端产物 → 新设备）

```bash
# 云端：把要给客户端的文件放进 outbox
cp alice-1.0.0-1.20.1.jar ~/outbox/
```
```bat
:: 新设备：拉回来（默认落到 D:\JAVA_projects\alice-outbox）
client-agent.cmd -PullOutbox
```

---

## 7. 失败对照表（症状 → 原因 → 修法）

| 症状 | 真实原因 | 修法 |
|---|---|---|
| `dsh`/`gh` "not found" 但它确实装了 | 旧 PATH（winget/npm 装完要新终端） | 关掉终端重开；或直接用 `client-agent.cmd`（它自刷 PATH） |
| `gh auth status` 未登录 / `gh: To get started…` | PAT 只在文件里、没进 gh 的凭据库 | 按自检给的命令写 `%APPDATA%\GitHub CLI\hosts.yml` |
| `missing required scope 'read:org'` | 用了 `gh auth login --with-token` | **别用它**（本 PAT scope = `codespace, repo`）；改用上面那招 |
| agent 说"信箱不存在" | 它去找**本机** `~/bus` 了（提示词歧义的历史缺陷） | 确认 preset 是 `alice-client-master`（`settings.yaml → agent-presets.default`），人设已写明"信箱在云端、本机不许建" |
| agent 报的客户端路径不对 | 它扫到了原版 `.minecraft` | 直接改 `%USERPROFILE%\.alice-client.json` 里的 `clientRoot`（这是唯一事实来源） |
| 回执写出了但云端没有 | 写到了本机 | 看 `-SelfTest` 的"写信箱"那一项；远端路径必须绝对 |
| `gh codespace cp -r` 后远端多了一层目录 | 同名远端目录已存在时 `cp -r` 会嵌套 | 先 `rm -rf` 远端那份，或逐文件指定目标名 |
| 上传后字节数不符 | 传输截断/被当文本处理 | 二进制一律走 `gh codespace cp -e`；文本用 `cat >>` 追加 |
| 新设备上 `-Install` 报错 | ⚠️ 这条路径**从没在真机跑过** | 把输出贴我（§8），我改脚本 |

---

## 8. 要回给我的证据（贴在聊天里就行）

1. `client-agent.cmd -Install` 的**完整输出**（第一次跑必给）；
2. `client-agent.cmd -SelfTest` 打印的**日志文件路径**（我直接读，不用你复制内容）；
3. 唤醒 agent 后：**云端** `~/bus/to-cloud/` 里那份回执的文件名（我这边能读原文）；
4. 若测试数据上传了：`~/client-info/<tag>/` 的文件清单 + 你对"截图里应该看到什么"的一句话描述（好让我判断画面是否传对）；
5. 任何"和预期不一样"的地方：**你看到的** vs **你以为会看到的**，以及能不能复现。

---

## 9. 诚实边界（哪些已经验过、哪些没有）

| 项 | 状态 |
|---|---|
| preset 格式 / 导入路径 | ✅ 已验证（与本机可用 preset 结构一致，18 个顶层行不变） |
| 自检脚本在**真 Windows PS 5.1** 上运行、报错与提示可用、UTF-8 日志可读 | ✅ 已实测（"装之前"状态：3 通过 / 4 失败，读得到修法） |
| PATH 自刷新（winget/npm 装完立刻可用） | ✅ 修完并复测（修之前它误报"没有 gh"） |
| 信箱协议（读、写、回执、`.done`、附件 cp + 字节核对） | ✅ 本机 WSL 端到端跑通（第三次试跑成功） |
| 打印/往返链路里 `gh codespace cp -e` 的绝对路径要求、`cp -r` 嵌套坑 | ✅ 实测踩过并记档 |
| **新设备上的 `-Install`（npm 装 DSH + 自动发现客户端 + 写配置 + 写 gh 凭据 + 设默认 preset）** | ❌ **从未在真机执行过** —— 这就是本手册要测的东西 |
| 新设备上 `dsh --profile headless` 真跑 agent | ❌ 未做（依赖上一项） |
| 截图从新设备传到云端并被 `read_image` 正确读出 | ❌ 未做（§5 第 4 条） |
