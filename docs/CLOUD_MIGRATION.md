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
| `~/.dsh/settings.yaml` | 8 K | ⚠️ **搬不搬待定**（原写「必搬」） | profile / 模型（含 `contextWindow`）/ 权限 / preset + 插件配置段。**跨版本 schema 差异是否会打坏设置页仍未验证**（曾把它误当成设置页坏掉的原因，见 §9-26 的更正）⇒ 更稳的做法 = 只搬 `.credentials.yaml`，模型等在**回环入口**的设置页里重配 |
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
| `.devcontainer/devcontainer.json` | Codespace 的镜像与工具链：**JDK 17 + Node 22** + `forwardPorts:[3081]` + `postCreateCommand` 装 DSH（**锁 0.1.5-rc.3** —— ⭐ 实跑证明**发布的 rc.1/rc.2 是残缺的**，见 §9-18） |
| `tools/codespace-start-dsh.sh` | **在 Codespace 内**启动：回环绑定 + 自动算 `--trusted-host <转发域名>`；cwd 决定会话 slug，可用 `DSH_WORKDIR` 指 |
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

**⚠️ 版本告警（搬历史时必须注意）**：本机 DSH = **0.1.5-rc.1**（实测 `dsh --version`；且它是 **DSH 源码检出**，不是 npm 安装），npm 上其后还有 rc.2 / **rc.3**。会话存储是**带世代迁移**的（`session.v3.jsonl.zstd`）⇒ **不要让云端用更新版去读/写同一份 `sessions/`**：
⇒ 零期**先不搬 sessions**（`state` 只搬配置与凭据，正是为此）。
⭐ **实跑更正（2026-09-23）**：**发布的 `0.1.5-rc.1` / `rc.2` 装出来是残缺的** —— `@deepseek-ai/dsh` 的 72 个依赖装完只有 **120** 个插件，而 `dsh web` 需要的 `@deepseek-ai/dsh-sandbox-local` **不在其中** ⇒ 启动即 `plugin tree failed to load`。**可用的发布版 = `0.1.5-rc.3`**（实测 **240** 个插件、`dsh-sandbox-local` 在位、`dsh web` 正常监听 `127.0.0.1:3081`）。本机那份有 250 个插件是因为它是**源码检出** ⇒ 两边天然不能逐包对比。

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
5. ⭐ **能真的发一条消息**（不只"页面能开" —— 后者挡不住 `--trusted-host` 的坑）。⚠️ 但**「能对话」≠「设置能用」**：设置页需要**回环入口**（SSH 隧道），见 §9-24/25
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
| 12 | DSH 版本 = 本机 `0.1.5-rc.1`（**源码检出**）/ 可用的发布版 = `0.1.5-rc.3` | ✅ 实测 ⇒ **devcontainer 锁 rc.3**；搬 `sessions/` 前先想清世代（**零期不搬**） |
| 13 | ⭐ **自建 devcontainer 的镜像必须带 sshd** | ✅ **实跑抓到**：`base:ubuntu-24.04` 不带 SSH 服务 ⇒ `gh codespace ssh` 报 `failed to start SSH server`（Codespaces 默认镜像自带 sshd，所以只在自建 devcontainer 时踩到） ⇒ 修法 = 加 `ghcr.io/devcontainers/features/sshd:1`（错误信息自己就给了这条） |
| 14 | ⭐ `gh codespace rebuild` **用的是工作目录里的** devcontainer | ✅ 实跑抓到（帮助原文 + 亲测）：云端工作树还停在旧 commit（没有 `.devcontainer`）时重建 = **等于没有 devcontainer**（仍是默认镜像：Node 24 / JDK 25 / 无 DSH）⇒ **先 `git pull` 再 rebuild** |
| 15 | ⭐ `gh codespace cp` **本身有引号 bug** | ✅ 实跑抓到（本机 gh 2.45.0）：它把远端路径**连引号**交给远端 scp ⇒ `dest open "'/home/vscode/.dsh/x'"` ⇒ **别用它**；我们改成**内容经 base64 走 ssh + 两端 sha256 对账**（`tools/codespace-zero.sh` 的 `rput`） |
| 16 | ⭐ `gh codespace ssh -- bash -lc '脚本'` **引号会被吞** | ✅ 实跑抓到：gh 把 `--` 之后的参数**用空格拼接** ⇒ 远端只收到 `bash -lc mkdir`（症状 `mkdir: missing operand`）；多行脚本更隐蔽（login shell 逐行跑）⇒ 我们改成 **base64 中转 + `bash -l`**（`tools/codespace-zero.sh` 的 `rsh`） |
| 17 | 自建 devcontainer 的**远端用户是 `vscode`** | ✅ 实测：Codespaces 默认镜像是 `codespace`、`base:ubuntu-24.04` 是 `vscode`（`HOME=/home/vscode`）⇒ 一切路径都要**先问远端 `$HOME`** |
| 18 | ⭐⭐ **发布的 `0.1.5-rc.1`/`rc.2` 残缺**（少 `dsh-sandbox-local` ⇒ `dsh web` 起不来） | ✅ 实跑：同一台机器上 rc.1 = **120** 插件 + 启动失败；**rc.3 = 240** 插件 + 正常监听 ⇒ **devcontainer 锁 rc.3** |
| 19 | ⭐ `dsh web` **拒绝** `--host 0.0.0.0` | ✅ 实跑原文：`intentionally not supported yet for safety … use 127.0.0.1 instead` ⇒ **保持回环**（Codespaces 转发器在容器内部连 localhost ⇒ 够用） |
| 20 | 首次启动会**自建 profile**（`~/.dsh/profiles/web`，bundle = `[dsh-base, dsh-web-app]`） | ✅ 实跑：插件从 **CLI 自带的 `node_modules`** 解析 ⇒ **不需要**搬本机 270 M 的 `profiles/web`，也不用跑 `dsh plugin install` |
| 21 | ⭐ **令牌 URL 是必须的**，Ports 面板的裸链接永远显示 `authentication required` | ✅ 实跑：不带 token = **401**；`?token=…` = **303 + 种 cookie**（cookie 的 `authority` **绑转发域名**）⇒ 再请求 = **200**；cookie 有效期 30 天 ⇒ **只需带一次** |
| 24 | ⭐⭐⭐ **设置页只在回环入口可用**（走 HTTPS 转发域名时**对话能用、模型/插件配置永远打不开**） | ✅ **源码级**：`dsh-client-ui-settings/lib/client.js:1345` = `persistence = ctx.remote.$host.isLoopback ? "host" : "memory"`；`isLoopback` 见 `dsh-client-connection/lib/client.js:6344`（只认 `localhost` / `[::1]` / `127.x.x.x`）；`memory` 时镜像 `ensure()` 立刻返回（settings client:1252）⇒ `view` 恒为 undefined ⇒ 报 `settings are unavailable in this browser`。**这是 rc.3 的设计行为，不是我们配错**；用户实测症状完全吻合 |
| 25 | ⭐⭐⭐ **正确入口 = SSH 隧道**（`gh codespace ssh -c <名> -- -L 3181:127.0.0.1:3081`，再开 `http://127.0.0.1:3181/?token=…`） | ✅ 由 24 直接推出：隧道下页面 hostname = `127.0.0.1` ⇒ 设置可读写；顺带**不再需要 `--trusted-host`**、也不需要把端口设 private（回环本来就过围栏）⇒ 脚本子命令 = `tools/codespace-zero.sh tunnel` |
| 26 | ⚠️ **更正我先前的错误结论**：我曾用「挪走 `settings.yaml` 后对话成功」推断「是它打坏了设置页」 | ❌ **该推断无效**（A/B 判据选错：对话本来就能用，真正出问题的设置页**从未复测**）⇒ 真因是 24 的 `persistence`。教训 = **A/B 必须测「出问题的那件事」，不能拿代理指标替代** |
| 27 | ⭐ **「回环入口」不等于「本地服务」** | ✅ 实测三方对账：本机 `ss -ltnp` 显示 3181 的监听者是 **`ssh`**（`gh codespace ssh -c <名> -- -N -L 3181:127.0.0.1:3081`）= 纯端口转发；`dsh web` 进程在**云端**（`hostname=codespaces-a0f5bd`、2 核/7 G、PID 17230）；工作区 `/workspaces/Alice-mcbot`；**会话落在云端** `~/.dsh/sessions/--home-vscode-dsh-test--/session-3813eafe…`（本机 `~/.dsh/sessions/` 只有 `--home-fb486--` 等本地 slug） ⇒ **算力/数据/会话/模型调用全在云端，本机只出一个 TCP 入口**；代价 = 设置页需要本机挂一条 ssh（或 VS Code 端口转发） |
| 28 | ⚠️ 云端会话的 **cwd 由 UI 里的「工作区」决定** | 实测：云端两个会话的 slug = `--home-vscode-dsh-test--`（cwd 是个**空目录** `/home/vscode/dsh-test`）⇒ **云端 agent 看不到 Alice 仓库**；要它干活得把工作区选到 `/workspaces/Alice-mcbot`（或让 `DSH_WORKDIR` 与 UI 选择一致） |
| 29 | ⭐⭐ **`gh codespace edit -m` 只改元数据，不改正在跑的容器** | ✅ 实测：`edit -c <名> -m standardLinux32gb` 返回 0、`list` 也显示新机型，但容器里 `nproc` 仍是 **2**、`uptime` 还是原来那个容器 ⇒ **必须 stop + 再唤醒**才真正换机器（换完实测 `nproc=4`、`free=15G`）；⭐ 好在**容器状态全保留**（`.credentials.yaml`、profile、sessions、`~/.gradle`、工作区都在）⇒ 不用重搬凭据 |
| 30 | ⭐ **本仓库可选机型只有两档**（实测 API） | `basicLinux32gb`(2c/8G/32G) 与 `standardLinux32gb`(**4c/16G/32G**) ⇒ **16 G 就是上限**，正好对上 `survey/31 §14` 的「16 G 服务器」。额度口径（官方）：免费 **120 core-hours/月** ⇒ 4 核 ≈ **30 小时/月**运行时间 |
| 31 | ⭐ **云端跑无头电池需要什么**（实测尺寸） | ① 客户端存档 **93 M**（电池从它建母本 `run/world-pristine`；`run/` **整个被 gitignore**，不进 git） ② 客户端模组 **65 M**（电池要把它们放进服务端 `mods/`） ③ 服务端目录 **332 M 不用搬** —— 电池自己会建（下 Forge 装器 + `--installServer`，电池 `:114-125`） ④ Alice jar 由 gradle 在云上构建 ⇒ **净搬运 ≈ 160 M** |
| 22 | ⭐ 三个自定义插件**都在 npm 上**，云上可直接装 | ✅ 实测 `npm view`：`dsh-dafeiyu` **0.1.14** · `dsh-ears` **0.3.2** · `dsh-whale-widget` **0.3.11**（本机的 `dsh-whale-widget` 是 `link:/home/fb486/dsh-plugins/…` **开发覆盖**，发布版可用） ⇒ 装法 = `dsh plugin --profile web add <包>` **且把名字加进该 profile `package.json` 的 `dsh.profile.bundles`**（bundles 才是启动真正加载的层） |
| 23 | ⚠️ **pnpm v12 默认拦依赖的构建脚本**（`pnpm approve-builds`） | ✅ 实跑：`dsh plugin add` 报 `Ignored build scripts: @fugood/whisper.node@1.1.3` ⇒ **包仍装进 `node_modules`**，但 `dsh-ears` 的 whisper 原生件没构建（要用音频才受影响）；要放行得在 profile 的 `pnpm.onlyBuiltDependencies` 里显式列名 |

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

## §11 零期结果（2026-09-23，全部实跑）

**在跑的东西**（codespace `humble-tribble-97pv59gw5rg62prg5`，`basicLinux32gb` = 2 核/7 G/32 G，30 分钟空闲自动停）：

| 项 | 状态 |
|---|---|
| 工具链 | node `v22.23.2` · java `17.0.20.1` · dsh **`0.1.5-rc.3`**（发布的 rc.1/rc.2 残缺，见 §9-18） |
| 配置 | `.credentials.yaml` 已送（两端 sha256 一致）；**`settings.yaml` 已挪走**（备份 `~/.dsh/settings.yaml.foreign` / `.copied-from-local`） |
| 服务 | `dsh web --port 3081`（回环）+ `--trusted-host <转发域名>`，日志 `~/dsh-web.log` |
| 仓库 | `/workspaces/Alice-mcbot` @ `a295058`（与本地 master 同步） |
| 插件 | `dsh-ears`、`dsh-whale-widget` 在 `dsh.profile.bundles` 里（可加载）；**`dsh-dafeiyu` 已按用户要求移除**（用户：该插件从云端显示到本地麻烦） |

**两条入口（性质不同，别混）**：

| 入口 | 能干什么 | 代价 |
|---|---|---|
| `http://127.0.0.1:3181/?token=…`（`tools/codespace-zero.sh tunnel` 前台 / **`tunnel-bg` 常驻**）| ✅ 全部（含设置/模型/插件配置） | 需要本机挂着隧道；令牌每次重启变 |

**⭐ 电脑重启 / codespace 被空闲停掉之后，一条命令恢复（2026-09-23 已实测）**：

```bash
bash tools/codespace-zero.sh tunnel-bg humble-tribble-97pv59gw5rg62prg5
```

它做三件事：① 远端 `dsh web` 不在就起（并自动唤醒 codespace）；② `setsid` 挂**自带重连**的隧道（日志 `~/.dsh-cloud-tunnel.log`，`ServerAliveInterval=30`）；③ 打印**带新令牌**的回环 URL。
⚠️ 容器文件系统在 stop/start 之间**会保留**（实测：`.credentials.yaml`、profile、bundles、仓库全在），但**进程不会** ⇒ 每次唤醒都要重起服务（`tunnel-bg` 已代做）。
| `https://<名>-3081.app.github.dev/?token=…` | 只能对话；**设置页永久不可用**（§9-24） | 无需本机任何东西 |

**零期判据**：1–4 ✅（`verify` 全绿；`compileJava OK`、`check-all` 见日志）· 5 ✅（用户实测能发起对话）· 6/7 ✅（云端编译 + 离线门禁通过）· 8 ⏳ 未做（两前端并发）· 入口的"设置页可用"✅（**真浏览器** headless Chrome 实测：回环入口下模型选择器/余额/插件设置面板正常渲染，无 §9-24 那句报错）。

**用户裁定（2026-09-23）**：判据 8（两前端并发发一句）**跳过** —— 「并发情况几乎没有」（临时裁定；**复核触发** = 将来出现多前端并发或会话错乱/丢事件的现象，再补这一条）；工作区指向 `/workspaces/Alice-mcbot` **先不做**（项目还没迁移过去）。

**已办**：旧的两个 codespace（`fictional-bassoon-*`、空白的 `symmetrical-spork-*`）**已删除**（`gh codespace delete --force`，不可逆）⇒ 账号里只剩 `humble-tribble-97pv59gw5rg62prg5`。

## §12 新设备：只用 PowerShell（**不装 WSL**）

**新设备需要两样东西**：

| 项 | 怎么做 |
|---|---|
| `gh` CLI | `winget install --id GitHub.cli -e`（或 `scoop install gh`）；装完**新开终端** |
| 认证 | 把带 `repo` + `codespace` scope 的 PAT 存成 `$HOME\.gh-token`（`Set-Content -NoNewline -Path $HOME\.gh-token -Value '<PAT>'`）。⚠️ 本机网络**不通 github.com 的 HTTPS**（实测），所以 PAT 要在能上 github.com 的设备上建好再带过来；`api.github.com` 是通的，脚本全程只走 api + SSH |
| （可选）ssh 客户端 | Windows 自带 OpenSSH 客户端即可；**没有也能工作**（脚本退到 gh 原生转发） |

**一条命令**（注意路径：`-File` 用的是**当前目录**的相对路径，不是 PATH ⇒ 要么 `cd` 到脚本目录，要么用**绝对路径**）：

```powershell
# 方式 1：包装脚本（推荐，内部用 %~dp0 解析自身目录 ⇒ **任何当前目录都能调**）
D:\JAVA_projects\alice\tools\codespace-tunnel.cmd -Open     # 或 -Stop / -LocalPort 3183

# 方式 2：直接调 ps1，用绝对路径（若你**已经在 pwsh 里**，不要再套一层 pwsh，用 & 调用）
pwsh -File "D:\JAVA_projects\alice\tools\codespace-tunnel.ps1" -Open
& "D:\JAVA_projects\alice\tools\codespace-tunnel.ps1" -Stop
```

> ⚠️ 实测踩到的报错（用户 2026-09-24）：`pwsh -File tools\codespace-tunnel.ps1 -Stop`
> ⇒ `The argument 'tools\codespace-tunnel.ps1' is not recognized as the name of a script file`。
> 原因是**当时的工作目录不是仓库目录**（`-File` 不做 PATH 搜索）⇒ 用上面两种方式之一即可。

**⭐ 两个"只有真在 Windows 上跑才会现形"的坑（都是本次实测抓到并已修）**：

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| 1 | `.ps1` 存成**无 BOM 的 UTF-8** | PS 5.1 对无 BOM 的 .ps1 按 **ANSI/GBK** 读 ⇒ 中文把 here-string 解析坏，3 处语法错、**脚本跑不起来** | 存成 **UTF-8 with BOM**（`tools/codespace-tunnel.ps1` 已修） |
| 2 | `.cmd` 用 **LF 行尾 + 中文注释** | `cmd.exe` 按 GBK 误读、LF 断错行 ⇒ 命令行被切碎（报错里出现 `espace-tunnel.cmd` = 头部被吃掉的证据） | **CRLF + 纯 ASCII**（`tools/codespace-tunnel.cmd` 已修，`file` 实测 = `DOS batch file, ASCII text, CRLF`） |

**包装脚本实测（2026-09-24，从 `C:\Users\<你>` 这种**非仓库目录**调用）**：
`...\tools\codespace-tunnel.cmd -LocalPort 3184` ⇒ 打印 `http://127.0.0.1:3184/?token=…` ✓ ·
监听 = **`127.0.0.1:3184` + `[::1]:3184`**（回环 ✓）· 无令牌 **401** / 带令牌 **303** ✓ ·
`...codespace-tunnel.cmd -Stop` ⇒ 监听残留 **0** ✓。
（包装脚本在找不到 `pwsh` 时会自动退回 Windows PowerShell 5.1 ✓）

它依次做：① 检查 gh/认证 → ② **确保云端 `dsh web` 在跑**（顺带唤醒 codespace）→ ③ 挂端口转发到本机回环 → ④ 读回令牌 → ⑤ 打印（并可选打开）`http://127.0.0.1:3181/?token=…`。
停掉转发：`pwsh -File tools\codespace-tunnel.ps1 -Stop`。

**两种转发机制（都实测过）**：

| 机制 | 绑定 | 结论 |
|---|---|---|
| `gh codespace ssh -c <名> -- -N -L 3181:127.0.0.1:3081` | **`127.0.0.1`** ✓ | 脚本**优先**用这条 |
| `gh codespace ports forward 3081:3181 -c <名>`（参数顺序 = **远端:本地**） | **`*`（所有网卡）** ⚠️ | 只在没有 ssh.exe 时兜底；局域网内可访问该端口（DSH 的围栏仍会挡掉非回环 Host，但不如前者干净） |

**为什么非得是回环**：见 §9-24（`persistence = isLoopback ? "host" : "memory"`）—— 用 `https://<名>-3081.app.github.dev` 打开时只能对话，**模型/插件配置永远打不开**。

**完全不用 gh CLI 的备用路线**：VS Code（桌面版）+ 官方 Codespaces 扩展 → 连上该 codespace → **PORTS 面板 → 3081 → Open in Browser** ⇒ 浏览器拿到的是 `http://localhost:3081`（**也是回环**）⇒ 设置页同样可用，而且不占终端。

**常见故障对照**：

| 现象 | 原因 / 处置 |
|---|---|
| 页面显示 `authentication required` | 没带 `?token=`（Ports 面板的裸链接必然如此）⇒ 用脚本打印的链接 |
| 页面能开但"设置不可用 / 加载提供方目录失败" | **入口不是回环**（用了转发域名）⇒ 改用回环链接，见 §9-24 |
| `127.0.0.1:<端口>` 连不上 | 端口被占（换 `-LocalPort`）；或 codespace 被空闲停掉 ⇒ 重跑脚本 |
| `gh: not found` / 认证报错 | 见上表前两行；`gh api /user --jq .login` 应打印账号 |

### ✅ 已在 Windows 上实测（2026-09-24，Windows PowerShell **5.1**.19041）

| 项 | 结果 |
|---|---|
| Windows 侧前置 | `ssh.exe` / `curl.exe` / `git` 本来就有；**`gh` 没有** ⇒ `winget install --id GitHub.cli -e --accept-source-agreements --accept-package-agreements` **装成功**（v2.101.0，落在 `C:\Program Files\GitHub CLI\gh.exe`）。⚠️ 装完**必须新开终端**（PATH 才更新） |
| ⭐ 真实 bug（验证抓到并已修） | 脚本原来是**无 BOM 的 UTF-8**，而 PS 5.1 对无 BOM 的 .ps1 按 **ANSI/GBK** 读 ⇒ 中文把 here-string 解析搞坏，报 3 处语法错、**根本跑不起来**。修法 = 存成 **UTF-8 with BOM**（现已修，PS 5.1 解析 0 error） |
| 端到端跑通 | 认证 = PAT（读 `C:\Users\<你>\.gh-token`）✓ · 账号 `dddgn486` ✓ · 唤醒 codespace + 确保远端服务 ✓ · 挂转发 ✓ · 本地就绪 `HTTP 401` ✓ · 打印带令牌链接 ✓ |
| 绑定地址 | `netstat` 实测 **`127.0.0.1:3183` LISTENING**（走 ssh 路线 ⇒ **只绑回环** ✓，设置页可用） |
| 经隧道取页面 | 无令牌 = **401**，带令牌 = **303** ✓ |
| ssh 密钥 | `gh codespace ssh` 在 Windows 上**没有额外操作**就通了（无需手动登记密钥）⇒ 新设备少一个坑 |
| `-Stop` | ✓ 干净：`gh`/`ssh` 进程消失、监听消失、PID 文件清掉。（⚠️ 注意 `netstat | findstr` 在停止后 ~2 分钟内还能看到 **TIME_WAIT** 残留，那不是没停掉） |

**给别人/未来自己复现时的一个坑**：若你也想从 WSL 里起 `powershell.exe` 来做这种验证，那个进程继承的是 **WSL 的 PATH** ⇒ 新装的 `gh` 会"找不到"。
先在同一会话里刷新：
`$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')`

**仍未被人类确认的部分**：Windows 浏览器里页面**渲染**（脚本只证到 HTTP 303/401 与打印链接）⇒ 你点开链接看一眼即可。

## §12b 「丢到新设备就能用」的包（实测：模拟新设备跑通）

**生成**（一条命令，产物落在**桌面**；源文件都在仓库里，随时可重建）：

```bash
tools/make-cloud-tunnel-bundle.sh
```

产物：

| 位置 | 内容 |
|---|---|
| `C:\Users\<你>\Desktop\alice-cloud-tunnel\`（文件夹，可直接双击） | `codespace-tunnel.cmd` · `codespace-tunnel.ps1` · `怎么用.txt`（CRLF+UTF-8 BOM，记事本友好） · `本机WSL专用/cloud-rollback.sh` |
| `C:\Users\<你>\Desktop\alice-cloud-tunnel-<日期>.zip` | 同一份，**拷到新设备用**（12 K） |
| `D:\JAVA_projects\alice-backups\` | 同一 zip 的备份 |

**脚本替新设备挡掉的两个摩擦点**（都在包里实现）：
① 没有 `gh` ⇒ 问一句 `现在装吗? [y/N]`，同意就用 winget 装并把安装目录接进本次会话的 PATH；
② 没有 PAT ⇒ 提示粘贴一次，存到 `%USERPROFILE%\.gh-token` 并 `icacls` 收紧为仅本人可读写。

**实测（2026-09-24，把 zip 解压到全新临时目录当"新设备"）**：

| 检查 | 结果 |
|---|---|
| zip 解压内容 | `.cmd`(692 B) / `.ps1`(8018 B) / `怎么用.txt`(3073 B) / `本机WSL专用\cloud-rollback.sh` ✓ |
| `.cmd` 经 zip 往返后仍是 **CRLF** | ✓ |
| `怎么用.txt` 是 **UTF-8 BOM + CRLF** | ✓（239,187,191 开头） |
| 解压出来的 `.ps1` 用 **PS 5.1 解析** | **0 error** ✓ |
| 有 PAT 文件时跑（端口 3186） | 认证 ✓ · 打印回环链接 ✓ · `HTTP 401` ✓ · `-Stop` 后监听残留 **0** ✓ |
| **模拟新设备**（把 `.gh-token` 移走，PAT 走 `DSH_PAT`） | 同样跑通并打印链接 ✓（测完已把文件恢复） |

## §13 回迁（⭐ **只针对本设备**：WSL `/home/fb486/projects/alice`）—— **增量版，2026-09-24 重写**

> 用户 2026-09-23：不一定一直留在云端 ⇒ 要先准备回迁；**回迁只针对目前这个设备**。
> 用户 2026-09-24 转达 `survey/33` 并指派「**回迁靠你来**」⇒ 当场实做一遍并**重写工具**（详见
> `docs/reviews/2026-09-24-回迁准备与云端分叉.md`：实测数字、分叉事实、四个坑）。

**一条命令**（✅ 已实测跑通两次，2026-09-24 10:36 旧版 / **23:01 增量版**）：

```bash
bash tools/cloud-rollback.sh            # 可选参数：<codespace 名>
```

它做八件事，**都不删远端任何东西**：

1. **云端仓库自检**：未提交改动 / 未推送 commit（⚠️ "未推送 0"可能是引用过期，见坑 **43**）；只报告，不替云端提交；
2. **本地同步**：`git pull --ff-only github master` + 刷新 Windows 镜像；
3. **云端清单**：会话（含**哈希阶梯**：前 1/2/3…MiB 的 sha256）+ 附件 + 小文件 ⇒ 取回本机；
4. **本机算增量计划**：只搬"本机没有的那一段"（跳过两端逐字节相同的项）；
5. **云端按计划切字节**（切在 **zstd 帧起点**上，见坑 **46**）并打包；
6. **取回 + 重建 + sha256 两端对账** ⇒ 落 **Windows 可见**的 `D:\JAVA_projects\alice-backups\cloud-rollback-<时间戳>\`；
7. ⭐ **文本出口（层次 b）**：把搬回来的增量解码成 `.jsonl` + **可读 `.md`**（给下一个主工作流读的原文）；
8. 打印"能不能被本机 DSH 采纳"的诚实结论 + 收尾清单。

**为什么重写（旧版的硬缺陷）**：旧版把**整个** `~/.dsh/sessions` 打包回来 —— 当时云端只有 2 个会话（21 KB，能用）；
迁移 177 个会话后同一份会变成 **292 MB**（经 base64 过 ssh ≈ 400 MB）⇒ 在"额度快耗尽 + 链路不稳"时**不可用**。
✅ 增量版本次实测：**要搬 29 项 / 跳过 251 项 ⇒ 传输 9.99 MB，重建 29/29 全部 sha256 通过**。

**回迁的核心事实（工具成立的前提）**：会话日志**只追加**，云端那份是从本机复制出去的
⇒ 本机文件是云端文件的**前缀**，只需传 `云端[F:]`；分叉点 `F` 由**哈希阶梯**反查（不靠猜）。
✅ 实测：主会话共同前缀 **90,206,208 B（86.02 MiB）**，拼回后 **98,839,800 B / `sha256 853f5a8be1b56f9b…` = 云端整文件**。

**诚实边界（回迁时别踩）**：

| 类别 | 结论 |
|---|---|
| 代码 | **零成本**：云端已 commit 的东西都在 git（= GitHub = 本机）⇒ 回迁靠 `git pull` 就够 |
| `settings.yaml` | **本机那份是权威**（云端那份本来就是从本机搬上去的副本，而且 DSH 之后在云端**自己重建过一个**）⇒ 归档只为留证，**不要覆盖本机** |
| `sessions/`（会话历史） | ⭐ **归档可读，但不要把归档会话塞回本机 `~/.dsh/sessions/`**：**同一个会话 id 在两端各自长过**（实测主会话两端各 +2.3 MB / +8.6 MB）⇒ 同 id 会与活着的本机分支**互踩**。另有版本代际（云端 rc.3 / 本机 rc.1）⇒ 别指望 DSH 本体静默兼容。**正确读法**：`node tools/dsh-session-log.mjs --file <归档里的 .zstd>`，或直接看回迁产出的 `.md` 文本出口 |
| ⚠️ `survey/33 §3` 的 slug 理由 | **已过期**：云端工作目录就是 `/home/fb486/projects` ⇒ 两端 slug **相同**（都是 `--home-fb486-projects--`）。层次 (a) 的真实障碍只剩"版本代际 + 同 id 双活" |
| 插件 | 无需回迁（云端装的是 npm 上的 `dsh-ears` / `dsh-whale-widget`；本机的 `dsh-whale-widget` 反而是 `link:` 开发副本） |
| 云端机器 | 回迁完成后 **stop** 省额度（⚠️ 实测**空闲超时不会自己停**，见 `survey/32 §4.2`）；确认不要了再 `gh codespace delete -c <名> --force`（不可逆） |

**脚本分工**：`tools/codespace-zero.sh`（WSL 侧遥控：doctor/create/state/verify/start/**tunnel**/**tunnel-bg**/url/down/destroy）·
`tools/codespace-tunnel.ps1`（Windows 侧，新设备只用 pwsh · 只做"挂隧道 + 打印链接"）·
`tools/cloud-rollback.sh`（回迁驱动）· `tools/dsh-session-rollback.mjs`（增量清单/计划/切字节/重建/文本出口，自检进 `check-all`）。

## §14 ✅ 云端「开发 + 编译 + 无头测试」闭环已跑通（2026-09-24 实测）

**怎么跑**（codespace 已装好生产服务端；母本与模组已就位）：

```bash
# 云端（codespace 内）
ALICE_HEADLESS=1 \
ALICE_CLIENT_SAVE=/home/vscode/mc-client/save \
ALICE_CLIENT_MODS=/home/vscode/mc-client/mods \
ALICE_SERVER_DIR=/home/vscode/alice-server \
bash tools/headless-battery.sh core
```

**结果**：`PROFILE=CORE baseline=15 main=26 extra_skipped=52 (passed=41/41 skipped=0)` **PASS** ·
一轮 ≈ **248 s**（本地 251 s ⇒ **4 核不比 2 核快**，服务端基本单线程）· 云端指纹 **`55045bbb2b15`**（与本地 `0f0f4b99f345…` 不同 ⇒ `D-352` 缓存**不跨机器**，云上第一轮必然真跑）。

**搬运清单（实测尺寸）**：客户端存档 **93 M**（电池据此建母本）+ 客户端模组 **65 M** = **净 158 M**，用
`gh codespace cp -e -r <本地> remote:/home/vscode/mc-client/<名>`（⚠️ **远端必须写绝对路径**；同名目录已存在时
`cp -r` 会**嵌套进去** ⇒ 想重搬先 `rm -rf` 远端那份）。服务端目录（332 M）**不用搬**：`bash tools/headless-battery.sh --install`
在云上自己装（实测 <1 min，下载飞快）。

**⭐ 跨机器跑当天就抓到两个"本地永远看不见"的真缺陷**（都已修，各带云端实测证据）：

| # | 缺陷 | 为什么本地看不见 | 修 |
|---|---|---|---|
| 1 | 电池**假设 `run/` 已存在** | `run/` 整个被 `.gitignore` ⇒ 全新检出里**根本不存在**；本地这台机器的 `run/` 常年存在 | `9669d9b`：建母本前 `mkdir -p "$(dirname "$PRISTINE")"` |
| 2 | ⭐ 电池**没有真正钉住 `difficulty`**：旧代码带 `[ -f server.properties ]` 守卫，而该文件是**服务端首次启动时**才生成的 | 本地文件早已存在、且早被这段代码钉过 ⇒ 守卫从没挡住过 | `3804802`：**先建文件再钉**；并在结果段打印 `前提(effective)`（不钉的项也打印，方便将来一眼看出两端分歧） |

缺陷 2 的云端表现 = CORE **40/41**，唯一红项 `damage_event_visible`（`hits=4 total=4.0`，`easy` 下多出的伤害源）⇒ 是**假红**，不是内核问题。
⇒ **结论**：跨机器跑不是为了"更快"，它的价值是**暴露隐含前提**（"本地常年如此"的目录/文件/默认值）。

**注意（与手册 §9-30 的额度口径配套）**：4 核 ⇒ 免费 **120 core-hours/月 ≈ 30 小时**；一轮 CORE ≈ 4 min ⇒ 一个月能跑几百轮，
但**别让它 24 小时开着**（空闲 30 分钟自动停已开）。

## §15 ✅ §5 世界母本：裁定与执行（2026-09-24，AI 定，用户授权）

**裁定：权威 = 「存档派生版」母本**（即由客户端存档 `ALICE_CLIENT_SAVE` 生成的那一份）。三条理由：
1. **可重建**：来源唯一且可哈希钉住（客户端存档）⇒ 任何机器都能复现同一份夹具；
2. **有已记录的绿色判决**：云端用它跑出 CORE `passed=41/41`（指纹 `55045bbb2b15`）；
3. **旧那份无法重建**：本机原来的 `run/world-pristine`（**369 文件**、`c9d7004d86ac25ea`）是从某个**已不存在的存档状态**派生的
   ⇒ provenance 丢失 ⇒ 不能当权威（否则"权威"等于一个谁也复现不出的工件）。

**实测到的分歧（同一口径 `LC_ALL=C`、排除 `session.lock`）**：

| 工件 | 哈希 | 文件数 |
|---|---|---|
| 客户端存档 `saves/新的世界` | `9220aca0640a3eee` | 481 |
| 云端 `run/world-pristine`（= 权威，存档派生） | `9220aca0640a3eee` | 481 |
| 本机旧 `run/world-pristine` | `c9d7004d86ac25ea` | **369** |

⇒ **两台机器此前在测不同的世界**（这就是"母本以哪份为权威"的物理后果）。

**已执行**：云端母本下载到本机（94 M / 2m52s）→ 校验哈希与文件数一致 → 换装
（旧那份留作对照 `run/world-pristine.old-369`）→ 本机重跑 CORE（换装使 `D-352` 指纹失效 ⇒ 这一轮是**真跑**）。

⚠️ 口径纠正：早前我按"含 `session.lock`"与"不含"两种口径比过哈希，据此说过「客户端存档在 09-24 那轮后变了」——
**那是错的**（口径不一致导致的误判）。用统一口径复算：存档哈希 `9a7e472d7733f6ed`(482 文件，含 lock) **与今早传输时完全一致** ⇒ **存档没变**。

## §16 🔬 会话迁移实验（2026-09-24，进行中）

**已确认的事实**（都实测）：

| 项 | 结果 |
|---|---|
| 会话文件世代 | 本机与云端**同为 `session.v3.jsonl.zstd`** ✓（本机另有 128 个更老的 `session.jsonl.zstd`，**不要动**） |
| slug 约定 | **`--<绝对路径去掉首斜杠、其余斜杠换短横>--`**，例：`/home/fb486/projects` ⇒ `--home-fb486-projects--`（⚠️ 我们的启动脚本原来算错成 `-home-fb486-projects`，已修 `5916f0f`） |
| 云端路径 | `sudo mkdir -p /home/fb486/projects` 需要 root（`vscode` 有免密 sudo ✓）；已建 `alice → /workspaces/Alice-mcbot` 软链 |
| 工作区登记 | 界面列表来自 `~/.dsh/storages/workspace.json`（`unit.name=workspace, version=2` + `tables.workspaces{<uuid>:{path,title,sessionIds,…}}` + `global.workspaceIds[]`）⇒ **手工登记在后端重启后保住了** ✓ |
| 试点会话 | 本机 `session-99966497-…`（18 KB，09-23 生成）复制到云端 `~/.dsh/sessions/--home-fb486-projects--/` ⇒ **两端 sha256 一致**（`e6b60750d04e27922b8a88b0`） |
| 服务以新 cwd 重启 | `WORKDIR=/home/fb486/projects` ✓；`401`/`303` 正常 ✓；**新 token** `4Q2RPSP11kt685_6Qwku6pJ_kFw8uCA-q6m2gn9u42M` |

**待用户做的两步（判据）**：
1. 打开云端界面 ⇒ 工作区 `projects（与本地同一 slug）` 是否出现、试点会话是否在列、能否打开；
2. ⭐ 在**那个试点会话里发一条消息**（让云端 rc.3 写它）⇒ 我再测**本机 rc.1 能否继续读它**
   —— 这就是"**单向门**"到底存不存在的决定性实验（试点会话是可丢弃的，所以拿它试）。

**§9 新增已知坑**：
- **32**：`CODESPACE_NAME` / `GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN` **在 ssh 会话里不存在**（那是 VS Code 终端注入的）
  ⇒ 通过 ssh 启动 `dsh web` 必须显式给 `DSH_TRUSTED_HOST=<名字>-<端口>.<转发域名>`，否则脚本会拒绝启动（实测踩过）。
- **33**：云端容器的 `python3` **没有 `json` 模块**（离谱但实测）⇒ 解析 JSON 用 `cat`/`jq`/本地处理，别在云上跑 python3 json。
- **34**：slug 约定见 §16（启动脚本已修）。
- **35**：迁移会话时用 `tar` 打包**必须加 `--` 分隔**：会话目录名按 slug 约定以 `--` 开头（`--home-fb486-projects--`），
  GNU tar 会把它当**长选项**解析 ⇒ 报 `unrecognized option`，而远端 `tar xf -` 收到空流 ⇒ **静默什么都没传**（实测踩过）。
  正确写法：`tar cf - --exclude='…' -- --home-fb486-projects-- | ssh … 'tar xf - -C ~/.dsh/sessions'`。
- **36**：⭐ **迁移会话必须连 `~/.dsh/attachments/` 一起迁**。只搬 `sessions/` ⇒ 引用了图片对象的会话会在构造请求时坏掉，
  云端表现为 **API stream 失败（`code: TRANSPORT`）**，而**不含图片的小会话完全正常** ⇒ 极易误判为网络/额度问题（2026-09-24 实踩）。
  另：云端 settings 若缺 `llm-deepseek`（`contextWindow`/`imagePixelBudget`/`imageMaxBytes`）会与本地行为不一致，需**部分迁移**该段。
- **37**：⚠️ **云端重启 `dsh web` 时的"自匹配误杀"**（2026-09-24 实踩，后果 = 界面里所有会话都"打不开"）。
  `pkill -f "dsh web"` 会匹配到**执行这条命令的 shell 自身**（它的命令行里就含这串字）⇒ 连带杀掉正在跑的
  会话/服务，表现为"服务没了、界面打不开"。**正解**：用方括号写法 `pkill -f "[d]sh web"`，
  或直接 `kill <pid>`；重启一律走 `tools/codespace-start-dsh.sh`（它会打印新的 token URL）。
  排查口径：`pgrep -af "[d]sh web"` 为空 ⇒ 先怀疑服务没跑，而不是会话坏了。
- **38**：工作区分组（= 会话分组）登记的 `sessionIds` **只影响"已登记/排序"**，磁盘上同 `cwd` 的会话仍会出现；
  想**藏掉**某个会话要用 `global.archivedSessionIds`（API 原文：hidden from every grouping surface，
  且不毁掉它在工作区里的位置）。
- **36**：⭐ **两端的 git 远端名不同**：**本地 WSL 用 `github`**，**云端容器用 `origin`** ⇒ 在云端照抄本地的
  `git push github master` 会报 `fatal: 'github' does not appear to be a git repository`（实测踩过；提交本身是成功的，只是没推上去）。
  两端都可用同一句的自查：`git remote -v`。
- **37**：云端基础镜像的 `python3.12` **物理缺标准库**（`/usr/lib/python3.12/json` 不存在）⇒ 4 项门禁因 `import json/html/shutil/zipfile` 失败而红（与代码无关）。
  修：`sudo apt-get update && sudo apt-get install -y libpython3.12-stdlib`；已固化进 `.devcontainer/devcontainer.json` 的 `postCreateCommand`。
- **38**：`check-machine-map` 的 mods 目录写死在本地 Windows 路径 ⇒ 云端必 `INCOMPLETE`。修：`tools/check-machine-map.sh` 支持 `ALICE_MODS_DIR`
  （云端用法：`ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` ⇒ `pass=19 warning=2 failed=1`，唯一 FAIL = 既有红 `check-ref-integrity` 26 条过期引用）。

- **39**：⭐ **云端跑无头电池必须显式给客户端模组目录**（否则 craft 类步骤**假红**）：`tools/headless-battery.sh` 的
  `CLIENT_MODS` 默认是本地固定客户端路径 `/mnt/d/.../worldedit-test/.../mods`（云端不存在）⇒ 云端的 `~/alice-server/mods`
  只会有 alice jar，**没有任何上游模组**。
  实测（2026-09-24 云端 CORE，`run/headless-logs/20260924-090839-core.log`）：`38/41`，唯一 FAIL = `craft_check`，
  失败子项正是"要靠模组才有机器配方"的那两条 —— `machine_only_vanilla=FAIL NO_RECIPE target=minecraft:cobblestone x1`
  与 `machine_only=SKIP`（`mekanism:dust_iron` 不存在）。**与代码无关**（对照：同一提交 `single:restore_underfoot_safety` PASS）。
  云端正确跑法：`ALICE_CLIENT_MODS=$HOME/mc-client/mods ALICE_HEADLESS=1 tools/headless-battery.sh --no-build core`
  （`~/mc-client/mods` 有 22 个 jar，含 Mekanism/Create 系）。
  ⚠️ **别再踩**：`ALICE_HEADLESS=1 tools/check-all.sh` 里那次电池调用**不带**这个环境变量 ⇒ 云端会拿到上面那批**假红**；
  门禁自查用**不带** `ALICE_HEADLESS` 的跑法（电池记 WARN），电池单独带变量跑。
- **40**：⭐ **`check-ref-integrity` 的"26 条过期引用"在云端是假阳性**（2026-09-24 查清并修掉）：
  `tools/ref-integrity.py` 把 Baritone 参照仓路径**写死**成 `/home/fb486/projects/reference/baritone`（本地 WSL），
  而云端参照仓在 `$HOME/reference/baritone-1.20.1` ⇒ 索引不到 ⇒ 两个仓都有的 `MovementHelper.java` 只剩**我们那份（474 行）**
  ⇒ 26 条**指向 Baritone 行号**的引用（`565…843`；Baritone 实为 **863 行**，逐行核对都命中）被判"超界"。
  修法：① 参照仓路径 = `ALICE_BARITONE_DIR` > 本地固定路径 > `$HOME/reference/baritone*`；
  ② 裸基名引用改为**候选全取、候选都装不下才红**（不再依赖"基名唯一"，也不依赖参照仓在不在）。
  效果：校验量 836 → **1082** 处、提示 232 → 156，并**真的抓出 2 条真过期**（`ToolSet.java` 当时写的 `207-239` **越界**（真值 236 行）⇒ 已改 `207-236`）；
  `ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = **`pass=20 warning=2 failed=0`（首次无 FAIL）**。
  ⚠️ 两个 warning 仍是"未执行"（无头电池未跑 / `check-machine-map` 缺上游 jar），**不是通过**。
- **41**：⭐ **日志里的中文变成 `?` = 启动器的 locale，不是电池的问题**（2026-09-24 用探针读数时发现，
  影响**所有证据留存**）：同一份代码、同一条命令，日志有的轮次中文完整、有的轮次全 `?`
  （实测 `…/20260924-112326-core.log` 里 `[Regression] ? PASS ??0 ????` 就是 `非 PASS 步（0 条）…`）。
  机制：本 harness 直接调用时环境里 `LC_ALL=zh_CN.UTF-8`（该 locale **不可用**，每条命令都打
  `setlocale: LC_ALL: cannot change locale (zh_CN.UTF-8)`）⇒ JVM/控制台流回落到 ASCII ⇒ 中文写成 `?`；
  经 Python 子进程或显式 `LC_ALL=C.UTF-8` 启动的那几轮中文完整（对照：`…/20260924-112518-*` 里
  `非 PASS 步（0 条）：无 —— 本步清单内**每一条**都是 PASS` 逐字可读）。
  ⇒ **纪律：跑电池一律前置 `LC_ALL=C.UTF-8`**（`LC_ALL=C.UTF-8 ALICE_CLIENT_MODS=$HOME/mc-client/mods
  ALICE_HEADLESS=1 tools/headless-battery.sh …`）。⚠️ 这条**不影响判决**（`PASS/FAIL/step 名/计数`都是 ASCII），
  但它决定"人能不能读日志"—— `P7` 那行报告的存在意义就是给人读。
- **42**：⚠️ **WSL 不继承 Windows 代理 ⇒ "连不上云端"的最常见原因**（2026-09-24 实踩）。
  Windows 上开着本地代理时（实测注册表 `ProxyEnable=1`、`ProxyServer=127.0.0.1:7897`），
  **浏览器能上 GitHub，WSL 不能**：`curl https://api.github.com/` 超时（而 `www.baidu.com` 200 正常）
  ⇒ `gh` / 隧道全断，现象是 `127.0.0.1:3181` 打不开、`gh codespace ssh` 报 `dial tcp … i/o timeout`、
  隧道日志 `Timeout, server localhost not responding`。
  **⚠️ 此时云端服务其实一直在跑**（本次实测 pid 与 token 都没变）⇒ 别误判成"云端挂了"。
  两个反直觉点：
  ① **`gh`（Go）不读 Windows 系统代理**，只认 `HTTPS_PROXY`/`HTTP_PROXY` 环境变量；
  ② WSL 里**不能写 `127.0.0.1:7897`**（NAT 模式下那不指向 Windows），要用**默认网关地址**
     （`ip route show default | awk '{print $3}'`，本次实测 `172.23.112.1:7897` → HTTP 200 ✓）。
  ⇒ **已修**：`tools/codespace-zero.sh` 新增 `ensure_proxy()`（按宿主 IP 探测 7897/7890 并导出代理），
  隧道循环**每轮重算宿主 IP**（WSL 重启后网关会变）；实测 `tunnel-bg` 打印「已启用宿主代理 …」+ 自检 401 ✓。
  更彻底的选项（需 `wsl --shutdown`，会重启本会话与 WSL 里的服务）：`%USERPROFILE%\.wslconfig` 加 `networkingMode=mirrored`。
  ⚠️ **Windows 侧的管家 / bus-watch 同理**：它们的 `gh` 也需要 `HTTPS_PROXY`（系统代理不够）
  ⇒ 建议设用户级环境变量 `HTTP_PROXY`/`HTTPS_PROXY=http://127.0.0.1:7897`。
- **43**：⚠️ **云端仓库的"未推送 0"可能是假的**（2026-09-24 回迁时实测）：云端克隆的 `origin/master` 引用**可能从没 fetch 过**
  ⇒ `git log @{u}..HEAD` 自然为 0，而 GitHub 上其实已经有**别的环境**（勘测员）推的新提交。
  本次实测：云端 HEAD 停在 `3093e31`，而 GitHub 上已有 `a2e5b05` / `f3f26af`（两份勘测报告），
  两端的 `git status` 都显示"干净且未推送 0"。**判据**：回迁脚本的 ② 步（本机 `git fetch` + `pull`）才是权威口径，
  不要把云端那句"未推送 0"当结论。
- **44**：⭐ **同一个会话 id 可能在两端各自长过（分叉）⇒ 不能按 id 合并**（2026-09-24 实测）：
  主会话 `session-c83b9b33-…` 在本机与云端**都活着**，共同前缀 **90,206,208 B**，之后本机 +2.3 MB、云端 +8.6 MB
  ⇒ 把云端那份塞进本机 `~/.dsh/sessions/` 会**与活着的本机分支同 id 互踩**（不是"锦上添花"）。
  **正解**：当归档读 —— `node tools/dsh-session-log.mjs --file <归档> --out x.jsonl`，或看
  `tools/cloud-rollback.sh` ⑦ 步产出的 `.md` 文本出口。
  ⚠️ 另：`survey/33 §3` 说的"slug 不同"**已过期** —— 云端工作目录就是 `/home/fb486/projects`，
  两端 slug **相同**（都是 `--home-fb486-projects--`）。
- **45**：⚠️ **"base64 套 base64"传文件有硬上限（Linux 单参数 128 KB）**：`rput` 把文件 base64 后塞进远端脚本，
  远端脚本又被 base64 一次 ⇒ 体积 ×2.33。实测 84 KB 的增量计划 → 外层参数 ~150 KB ⇒
  **`gh: Argument list too long`**（现象像"连不上云端"，实则没发出去）。**修**：结构化文件（清单/计划）一律走
  `gh codespace cp`；小脚本（<20 KB）仍可 base64。
- **46**：⭐ **增量切字节必须切在 zstd 帧起点上**：哈希阶梯给的偏移是 1 MiB 整数倍，**不是**帧边界
  ⇒ 从那儿直接切，解码会以"首个魔数不在偏移 0"失败。`tools/dsh-session-rollback.mjs pack` 已改成
  "从 `≥offset` 的第一个魔数起切"，并把 `start` 写进 manifest，`rebuild` 用 `本机[0,start)` 补前缀
  ⇒ 还原仍是**逐字节**的（sha256 两端对账已证）。
