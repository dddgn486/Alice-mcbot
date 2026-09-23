# 云端迁移方案（**修补版 v2**，2026-09-23）

> **出处**：外部方案 `survey/31-云端迁移方案-20260923.md`（474 行）+ 我方核对 `docs/reviews/2026-09-23-survey31-核对.md`（9 条复算）。
> **本文件是 AI 侧的"可执行版本"**：保留报告正确的骨架（**控制面上云 / 测试面留本地**），修掉三处
> （① 补 `--trusted-host` 这个真坑 ② 缓存价值的口径 ③ 世界母本应从"本地快照"升级为"版本化输入"），
> 并把**实测数字**与**能力分工**写进来。
> **性质**：基础设施手册 —— **不改项目契约、不进开发排期、不构成实现授权**。

---

## §0 三条硬纪律（先记这三条，其余都是操作细节）

| # | 纪律 | 为什么（事实） |
|---|---|---|
| 1 | **单写者**：任何时刻只允许**一个** agent/检出自写权限 | 会话是**单个 zstd JSONL 追加写**（`~/.dsh/sessions/<slug>/<id>/session.v3.jsonl.zstd`）⇒ 两个前端同时说话 = **两个写者**。这条同时解释了「别在云上再装一个认 `AGENTS.md` 的 harness」 |
| 2 | **世界母本是「输入」，必须唯一 + 版本化** | 脚本自己写着：5 个 CORE 步的 `START_FOOT` 是绝对坐标，「完全依赖它的地形 ⇒ **它是输入，不是环境**」（`tools/headless-battery.sh:34-36,167`） |
| 3 | ⭐ **云上的绿 = `SERVER_TESTED`，永远不等于 `WINDOWS_CLIENT`** | 真人客户端验收**不可能**上无图形界面的服务器 ⇒ 等级分工不因搬家改变 |

---

## §1 目标 / 非目标

| | 内容 |
|---|---|
| **目标** | 主工作流不再绑死某台 PC；多设备接同一份会话；反馈回路不因换机器中断 |
| **非目标** | ❌ 不是把"真人验收"搬上云 ❌ 不是多 bot 并行 ❌ 不改 `AGENTS.md` 的协作纪律 ❌ 不做设备发现/多平台抽象 |

---

## §2 ⭐ 迁移清单（**实测数字** —— 本节取代报告 §4.3 的「`scp -r ~/.dsh`」）

**实测**：`~/.dsh` 总计 **1.2 G**。但**真需要搬的只有 ≈0.66 G**，且大头可以重建：

| 物件 | 实测大小 | 搬？ | 说明 |
|---|---|---|---|
| `~/.dsh/settings.yaml` | 8 K | ✅ **必搬** | profile / 模型（含 `contextWindow`）/ 权限 / preset + 插件配置段 |
| `~/.dsh/.credentials.yaml` | 681 B（mode 600） | ✅ **必搬** | ⭐ **密钥在这里**（`refs:` 下 7 个：`DEEPSEEK_API_KEY` 等）。**永不进 git** |
| `~/.dsh/profiles/*/` | **539 M** | ⚠️ **二选一** | `web` 270 M + `alice-bus`/`alice-bus-lab`/`alice-git-lab`/`alice-mcp-lab` 各 63–77 M；**大头是 `node_modules`**（与平台/版本绑定）⇒ 更干净是**云端重建**（`dsh --profile web` / `dsh plugin`）。⚠️ **未核实**：首次启动是否自动装依赖（包内没找到自动 `pnpm install`）⇒ **保守做法：先搬 `web` 的 `package.json` + `cordis*.yml`，缺依赖时再整目录拷** |
| `~/.dsh/sessions/` | **302 M**（80 个 `.zstd`） | 零期可跳过 | 这是「我」的历史。⚠️ 目录名 = **cwd slug**（见 §4.3） |
| `storages/` `agent-bus/` `skills/` `.agent-presets` `whale-roles` | 6.5 M | ✅ 顺手搬 | 小件 |
| `attachments/` | 49 M | 按需 | 会话里的图片附件 |
| `backup-20260822-dsh-upgrade/` + `*.tar.gz` | **293 M** | ❌ **绝不搬** | 旧备份 |
| 仓库（git） | 45 M（bundle） | ✅ | 已在 GitHub；本地 bundle 只是兜底（§7） |
| 客户端 `mods/` + 存档（二期） | 几百 M ~ 1 G+ | 二期 | **电池的输入** |

> ⭐ **修补 ①**：报告 §4.3 说「从 WSL 直接 `scp -r ~/.dsh`（最简单）」—— 那会连 **293 M 旧备份**和
> **539 M 平台绑定 node_modules** 一起搬。正确形态 = **只搬配置与凭据（≈10 K）+ 小件（6.5 M）**，
> profiles 云端重建，sessions 按需。
>
> ⚠️ **修补 ①b（凭据不能靠环境变量）**：报告 §8-6 建议「环境变量优于文件（Codespaces secrets /
> systemd `Environment=`）」。**实测这条对 LLM 密钥不成立**：DSH 包内只读 `DSH_TELEMETRY_DISABLED`，
> profile 插件里**没有**任何 `*_API_KEY` 环境变量回退（只有 MCP 的 `MCP_CLIENT_SECRET`/`_PRIVATE_KEY_PEM`）
> ⇒ 密钥只能走 `.credentials.yaml`（或到云端用 DSH 自己的入口重填）。**CodeSpaces secrets 用不上**。

---

## §3 分期（保留报告骨架）

| 期 | 内容 | 风险 | 判据（见 §8） |
|---|---|---|---|
| **零期** | **Codespaces** 免费验「agent + 编译上云顺不顺」 | 零 | 零期 8 条 |
| **一期** | **DSH 上云**（agent + 编译），测试面**完全不动** | 低 | 一期判据 |
| **二期** | **测试面/全搬**（16 G）：无头电池上云 | 中 | ⚠️ **前置 = §5 世界母本版本化** |

⭐ **顺序建议**：零期 → 一期 → **先把 §5 做掉** → 二期。理由：二期唯一的真障碍不是内存，是「**母本以哪份为权威**」。

---

## §4 逐期操作

### 4.1 零期：Codespaces（**最短路径**）

**A 路（用户 2026-09-23 选定）：`gh` 驱动 + 三件已入库的件**

| 件 | 作用 |
|---|---|
| `.devcontainer/devcontainer.json` | Codespace 的镜像与工具链：**JDK 17 + Node 22** + `forwardPorts:[3081]` + `postCreateCommand` 装 DSH（**锁 0.1.5-rc.1**，与本机一致 —— 见下面的版本告警） |
| `tools/codespace-start-dsh.sh` | **在 Codespace 内**启动：自动算 `--host 0.0.0.0` 与 `--trusted-host <转发域名>`；cwd 决定会话 slug，可用 `DSH_WORKDIR` 指 |
| `tools/codespace-zero.sh` | **在本机**驱动：`doctor / create / state / verify / start / url / list / down / destroy`，每步都有判据 |

**执行顺序（每一步都能单独重跑）**

```bash
tools/codespace-zero.sh doctor            # ① 查 gh / 认证 / 仓库（认证见 §4.1b）
tools/codespace-zero.sh create            # ② 建 codespace（免费档 2 核/8G/32G；devcontainer 自动装工具链）
tools/codespace-zero.sh state  <name>     # ③ 送 settings.yaml + .credentials.yaml（600；⭐ 内容不打印、不进 git）
tools/codespace-zero.sh verify <name>     # ④ 零期判据 1–4、6、7（node/java/dsh/配置/编译/门禁）
tools/codespace-zero.sh start  <name>     # ⑤ 后台起 dsh web + 端口设 private + 打印外部 URL
tools/codespace-zero.sh url    <name>     # ⑥ 复制 URL 到浏览器：⭐ **发一条消息**确认功能真的通
```
⑦（判据 8）两个前端各发一句 ⇒ `node tools/dsh-session-log.mjs --list/--grep` 查有没有乱序/丢事件。
⑧ 用完 `tools/codespace-zero.sh down <name>`（计费停、存储照算；删除用 `destroy`）。

**⚠️ 版本告警（搬历史时必须注意）**：本机 DSH = **0.1.5-rc.1**（实测 `dsh --version`），npm `latest` 已是
**0.1.5-rc.2**。会话存储是**带世代迁移**的（`session.v3.jsonl.zstd`）⇒ **不要让云端用更新版去读/写同一份 `sessions/`**：
要么按 devcontainer 里的写法**锁版本**，要么零期**先不搬 sessions**（`state` 只搬配置与凭据，正是为此）。

**能省的**：DSH 的安装（devcontainer 装）、会话史（零期不必搬）、profiles（先让它自己初始化）。

### 4.1b ⚠️ 本机认证的**实测限制**（决定"能不能用 `gh auth login`"）

| 事实（实测） | 后果 |
|---|---|
| **`github.com` 的 HTTPS 不通**（`curl` 挂；`~/.ssh/config` 已把 github 指向 `ssh.github.com:443`） | `gh auth login --web`（设备码流程要访问 `github.com`）**在本机走不通** |
| **`api.github.com` 通**（200） | ⇒ 用 **PAT** 认证（`GH_TOKEN`），全程只走 api |
| `gh` 已装好 | `~/.local/opt/gh-2.45.0` + `~/.local/bin/gh` 软链（**无 sudo**：`apt-get download` + `dpkg-deb -x`）；`gh codespace {create,cp,ssh,ports,list,stop,delete,view}` 都在 |

⇒ **你只需做一件事**（约 1 分钟）：
1. 浏览器打开 `https://github.com/settings/tokens/new?scopes=repo,codespace&description=alice-codespace`
   （**classic** PAT，勾 `repo` + **`codespace`**）；
2. 把令牌存成本机文件（**别贴进聊天**）：
   `printf '%s' '<PAT>' > ~/.gh-token && chmod 600 ~/.gh-token`
3. 回来跑 `tools/codespace-zero.sh doctor` ⇒ 出 `api OK（账号 dddgn486）` 就算通了。

（若你的浏览器要经代理/VPN 才能开 github.com：那就用能开的那个设备建 PAT，同样是上面三步。）

### 4.2 一期：VPS（DSH + 编译）

同零期的第 4–6 步，外加：非 root 用户跑、`systemd` 保活（报告 §4.4 的骨架）、**Caddy 反代 + basicauth**
（反代与 DSH **同机**时**不用**改 `--host`，代理连 `127.0.0.1:3081` 即可；但**要**加 `--trusted-host <域名>`）。

### 4.3 ⭐ 会话与路径（**报告没写、但会卡住人的一节**）

`~/.dsh/sessions/<slug>/<session-id>/session.v3.jsonl.zstd`，**slug = 启动时的 cwd，把 `/` 换成 `-`**。
本机实测三个 slug：`--home-fb486--` · `--home-fb486-projects--` · `--home-fb486-projects-Mod-on-Forge-1.20.1--`
（本项目的主工作流 cwd = `/home/fb486/projects`）。

⇒ 想让云上「打开就是同一份会话」，两条路：
- **保持同一绝对路径**：云端 `sudo mkdir -p /home/fb486/projects && sudo chown -R "$USER" /home/fb486/projects`
  ⇒ 在那里 clone 仓库、并**在 `/home/fb486/projects` 下启动 `dsh web`**（路径一致 ⇒ slug 一致）。
- 或**改 slug 目录名**（把 `--home-fb486-projects--` 改成云端的 cwd slug）。

⚠️ **诚实边界**：「列表按 cwd 过滤」是**从目录命名与三级 slug 实例推出的**，我**没实测**（要两台不同 cwd 的实例才能验）。

---

## §5 ⭐ 世界母本版本化（**我加的、并被建议为二期前置**）

**今天它是什么（实测）**：`run/world-pristine` 只在**不存在时**建一次（`headless-battery.sh:137`），来源 =
客户端存档 `…/saves/新的世界`，**不进 git**，且那个源目录**还在被玩**（实测 mtime = 今天 14:08）。

**为什么这是风险**：报告设想「母本唯一在云上，设备只消费不生产」。但母本的**来源**（玩家客户端）**永远在本地**
⇒ 一旦玩家继续在本地玩，「唯一母本」必然**落后于真实世界**；而 5 个 CORE 步依赖它的绝对坐标地形 ⇒ 这不是美观问题。

**建议形状**（把她当**输入**对待）：
1. 打包成可寻址 artifact：`world-pristine-<ver>.tar.zst` + **sha256** + **该版本期望的 CORE 判决**；
2. 设备只下载固定版本，`ALICE_CLIENT_SAVE` 指向解包目录；
3. **换版本 = 显式动作**：删 `run/world-pristine` + 复跑一轮 CORE 并对比（换母本 ⇒ `D-352` 指纹变 ⇒ 缓存必然失效，
   这是**正确行为**，不是 bug）；
4. 母本自身**冻结**：玩家在本地继续玩**不再自动影响**它（要影响就出一版新的）。

---

## §6 我（AI）能做哪些 / 你必须做哪些（**实测能力边界**）

| 动作 | 我能不能做 | 依据（实测） |
|---|---|---|
| **备份**（DSH 状态 + 仓库） | ✅ **已做** | 见 §7（两个文件 + 校验） |
| 出网（clone / push） | ✅ | 本机实测：`ssh -T git@github.com` 与 `-p 443` **都鉴权成功**（账号 `dddgn486`）；`~/.ssh/config` 已把 github 指向 `ssh.github.com:443` |
| 把行李传到**一台已开好、我能 SSH 上去**的机器 | ✅ 基本可以 | 上传、装 node/DSH、起 `dsh web`（含 `--host`/`--trusted-host`）、放 sessions、跑判据 |
| **自己开一台机器** | ❌ | 需要云账号/支付/控制台（这一步权属永远在你账号下）。⭐ **但 `gh` 我已经装好了**（`~/.local/opt/gh-2.45.0`，**无 sudo**；`gh codespace` 全套子命令可用）⇒ **只差你一次 PAT 认证**（§4.1b） |
| 在浏览器里点验（多设备切换 / 并发写入 / GUI） | ❌ | 需要真人（这也是纪律：客户端事实必须问用户） |
| ⭐ **A 路（已就绪）**：`gh` 已装 + 你给一次 PAT（`~/.gh-token`） | ⭐ **之后零期整段我代跑** | 三件已入库：`.devcontainer/devcontainer.json` · `tools/codespace-start-dsh.sh` · `tools/codespace-zero.sh`（`doctor/create/state/verify/start/url/down`），除浏览器点验（判据 5/8）外全在 CLI 里 |

⇒ **诚实回答「你自己能搬自己吗」**：
**打包与搬迁我能做（给我一台可登录的机器）；"开机器"这一步必须在你的账号下发生。**
而且——严格说「我」= `~/.dsh/sessions`（历史）+ 工作树（git）：**前者已备份、后者已在 GitHub**
⇒ **换机器不丢东西**；缺的只是"新机器上的 harness + 凭据"。

---

## §7 备份与回滚（**已实测完成**，2026-09-23 22:29）

| 备份 | 路径（都在 `/mnt/d/JAVA_projects/alice-backups/`，**仓库之外、Windows 可见**） | 大小 | 校验 |
|---|---|---|---|
| **DSH 状态**（配置 + 凭据 + 小件 + 80 个会话） | `dsh-state-20260923-222927.tar.gz` | 296 M | sha256 前缀 `df7a2142c90c5241…` |
| **仓库全量**（所有 ref，单文件） | `alice-repo-87c6a23-20260923-222950.bundle` | 45 M | `git bundle verify` ⇒ **records a complete history** |

**恢复**（在新机器上）：
```bash
# 仓库
git clone /path/to/alice-repo-87c6a23-*.bundle alice && cd alice && git remote set-url github git@github.com:dddgn486/Alice-mcbot.git
# DSH 状态（会覆盖 ~/.dsh 下同名文件；先看 tar -tzf 再解）
tar -xzf dsh-state-*.tar.gz -C ~ && chmod 600 ~/.dsh/.credentials.yaml ~/.dsh/settings.yaml
```
⚠️ 打包时**故意排除**：`~/.dsh/backup-*`（293 M 旧备份）与 `profiles/*/node_modules`（平台绑定）。
⇒ 新机器上 profiles 需要重建（§2 的 ⚠️）。

---

## §8 验收判据

**零期（8 条，全绿才算过）**
1. `node -v` ⇒ **≥ 22.15**（硬要求：`tools/dsh-session-log.mjs` 用 `zlib.zstdDecompressSync`）
2. `java -version` ⇒ 17（gradle/Forge 要求）
3. `npm i -g @deepseek-ai/dsh && dsh --version` ⇒ 出得来
4. `~/.dsh/settings.yaml` 在、`~/.dsh/.credentials.yaml` 在（`chmod 600`）
5. ⭐ **转发域名能打开且能真的发一条消息**（不只"页面能开" —— 后者挡不住 `--trusted-host` 的坑）
6. `./gradlew compileJava --no-daemon` 成功
7. `bash tools/check-all.sh` ⇒ 打出 `pass/warning/failed`（CI 上无上游 jar ⇒ **Tier B 必然 WARN**，属预期）
8. ⭐ **并发写入行为有结论**：两个前端各发一句 ⇒ 查 `node tools/dsh-session-log.mjs --list/--grep`（正常 / 写进纪律）

**一期**：零期 8 条 + `systemctl status dsh` active + 外部 `curl` 无凭据 ⇒ 拒绝 + `git push` 成功

**二期**：一期 + `tools/headless-battery.sh core` ⇒ `passed=41/41`（期望值以 `docs/HANDOVER.md` 最新为准）
+ ⭐ `ALICE_MODS_DIR` 指向上游 `mods/` ⇒ **`check-machine-map` 的 Tier B 从 WARN 变真断言**（这是 CI 做不到的）

**⚠️ 反向判据（不许越级）**：云上跑绿**不等于** `WINDOWS_CLIENT`；任何客户端可见行为仍须本地真人验。

---

## §9 已知坑与未核实（诚实边界）

| # | 事项 | 状态 |
|---|---|---|
| 1 | `dsh web` 的绑定 | ✅ **实跑更正**：默认只绑 `127.0.0.1`，且 **`--host 0.0.0.0` 被 DSH 主动拒绝**（安全设计）⇒ **保持回环**；Codespaces 转发器在容器内部连 localhost ⇒ 够用 |
| 2 | `--trusted-host`（`/api` 信任围栏） | ✅ **我方实测**（`dsh web --help`）⇒ 域名访问不加会「页面能开、功能坏」 |
| 3 | 密钥**不能**用环境变量替代 | ✅ 实测：包内无 `*_API_KEY` 环境变量回退 |
| 4 | `~/.dsh` 的真实体积构成 | ✅ 实测（1.2 G；`profiles` 539 M、`sessions` 302 M、旧备份 293 M） |
| 5 | Codespaces 额度/价格/端口转发细节 | ⚠️ **未复核**（报告称引自官方文档） |
| 6 | `dsh web` 首次启动**是否自动装 profile 依赖** | ⚠️ **未核实**（包内没找到自动 `pnpm install`）⇒ 保守：先拷 `package.json`+`cordis*.yml` |
| 7 | Codespaces 是否导出 `CODESPACE_NAME` / `GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN` | ⚠️ **未核实** |
| 8 | 「会话列表按 cwd slug 过滤」 | ⚠️ **推断未实测**（§4.3） |
| 9 | 首次 `decompile` 内存峰值 · 云端部署步骤 | ⚠️ 未实测（报告也自述一条都没真机跑过） |
| 10 | **本机 `github.com` 的 HTTPS 不通** | ✅ 实测（`curl` 挂、`api.github.com` 200）⇒ **`gh auth login --web` 不可用，必须走 PAT**（§4.1b） |
| 11 | `gh` 已**无 sudo** 装好 | ✅ 实测（`apt-get download` + `dpkg-deb -x` ⇒ `~/.local/opt/gh-2.45.0`）|
| 12 | DSH 版本 = 本机 `0.1.5-rc.1` / npm latest `0.1.5-rc.2` | ✅ 实测 ⇒ **devcontainer 锁版本**（会话存储带世代迁移，别让新版写同一份 `sessions/`） |
| 13 | ⭐ **自建 devcontainer 的镜像必须带 sshd** | ✅ **实跑抓到**：`base:ubuntu-24.04` 不带 SSH 服务 ⇒ `gh codespace ssh` 报 `failed to start SSH server`（Codespaces 默认镜像自带 sshd，所以只在自建 devcontainer 时踩到） ⇒ 修法 = 加 `ghcr.io/devcontainers/features/sshd:1`（错误信息自己就给了这条） |
| 14 | ⭐ `gh codespace rebuild` **用的是工作目录里的** devcontainer | ✅ 实跑抓到（帮助原文 + 亲测）：云端工作树还停在旧 commit（没有 `.devcontainer`）时重建 = **等于没有 devcontainer**（仍是默认镜像：Node 24 / JDK 25 / 无 DSH）⇒ **先 `git pull` 再 rebuild** |
| 15 | ⭐ `gh codespace cp` **本身有引号 bug** | ✅ 实跑抓到（本机 gh 2.45.0）：它把远端路径**连引号**交给远端 scp ⇒ `dest open "'/home/vscode/.dsh/x'"` ⇒ **别用它**；我们改成**内容经 base64 走 ssh + 两端 sha256 对账**（`tools/codespace-zero.sh` 的 `rput`） |
| 16 | ⭐ `gh codespace ssh -- bash -lc '脚本'` **引号会被吞** | ✅ 实跑抓到：gh 把 `--` 之后的参数**用空格拼接** ⇒ 远端只收到 `bash -lc mkdir`（症状 `mkdir: missing operand`）；多行脚本更隐蔽（login shell 逐行跑）⇒ 我们改成 **base64 中转 + `bash -l`**（`tools/codespace-zero.sh` 的 `rsh`） |
| 17 | 自建 devcontainer 的**远端用户是 `vscode`** | ✅ 实测：Codespaces 默认镜像是 `codespace`、`base:ubuntu-24.04` 是 `vscode`（`HOME=/home/vscode`）⇒ 一切路径都要**先问远端 `$HOME`** |

**`.devcontainer/devcontainer.json` 草稿**（报告 §12 的版本 + 我加的一行装 DSH）：
```json
{
  "image": "mcr.microsoft.com/devcontainers/base:ubuntu-24.04",
  "features": {
    "ghcr.io/devcontainers/features/java:1": { "version": "17" },
    "ghcr.io/devcontainers/features/node:1": { "version": "22" }
  },
  "forwardPorts": [3081],
  "postCreateCommand": "npm i -g @deepseek-ai/dsh || true"
}
```

---

## §10 与项目纪律的关系

- 本文件**不改项目任何契约、不排期、不构成授权**（与 `survey/31` 同性质）；台账登记 = `§11-H`。
- 它**不占** `AGENTS.md + PLAYBOOK + STATE` 的冻结预算（那三份只管协作纪律）。
- ⚠️ **别照抄行号**：`survey/31` 的行号是基线 `2f10f66` 的，而 `aec21fc` 之后 `headless-battery.sh` 已改过
  （cp 客户端 mods 从 `:262-270` 漂到 `:292/:299`）⇒ 引用前先 `grep -n` 现查。
