# Alice 会话接手规范

> 这是 Alice 项目的永久性协作规范，不因具体进度变化而修改。  
> 作为新会话的 preset，确保 AI 助手在任何阶段都遵循统一的工作方式。

## 项目定位

**Alice** 是 Minecraft Forge 1.20.1 / Forge 47.4.10 / Java 17 的服务端权威 AI 玩家模组。

**核心原则**：
- LLM 只做目标级决策；确定性代码负责动作、安全和完成条件
- 服务端是世界、bot、任务和库存的真相
- 普通挖矿和拾取保持 `HARD_PATH`
- `SOFT_SURFACE` 不能悄悄接入正式任务
- `SEARCH_LIMIT` ≠ `UNREACHABLE`，不能自动授权挖隧道
- 未知模组能力默认只读，不猜槽位、配方或写入语义

## 会话启动流程（必做）

1. **确认环境**：`pwd && git status --short && git branch --show-current`
2. **读取状态文档**（按优先级）：
   - `docs/AI_PROJECT_STATE.md` ← 当前目标和已验证状态
   - `docs/AI_DECISIONS.md` ← 不可悄悄改变的架构决策
   - `docs/AI_TEST_MATRIX.md` ← 测试入口和验收等级
   - `docs/AI_DEVELOPMENT_PLAYBOOK.md` ← 详细协作规则
3. **按需读取 Skills**：`.alice-supervision/skills/*.skill.md`
4. **向用户复述**：当前目标、成功条件、不会改变的边界
5. **先选最小闭环**，不要直接大范围重构

## 开发工作流

```text
讨论目标
  → 读取相关 skills
  → 选择最小可验证闭环
  → 实施
  → ./gradlew compileJava --no-daemon
  → ./gradlew build --no-daemon（如需完整工件）
  → ./tools/mirror-windows-workspace.sh（镜像源码到 Windows）
  → ./tools/sync-windows-artifact.sh [jar] [repo] [runtime-mods]（同步工件到客户端）
  → 用户在固定客户端用游戏内测试物品/命令实测
  → AI 主动读取 Windows 日志并分析
  → 讨论事实与根因
  → 确认修复方向后再修改
```

**不要求**：每个小改动创建 active plan、工作包、审核包、HANDOVER 或执行旧监督脚本。

## 环境路径

| 位置 | 路径 | 说明 |
|---|---|---|
| WSL 开发端 | `/home/fb486/projects/alice` | 唯一 Git 管理端 |
| Windows 源码镜像 | `/mnt/d/JAVA_projects/alice/` | 不保留 `.git` |
| 固定客户端 | `/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10` | 用户测试实例 |
| 运行时 mods | `[固定客户端]/mods/` | 工件部署位置 |
| 客户端日志 | `[固定客户端]/logs/latest.log`<br>`[固定客户端]/logs/debug.log` | AI 必读 |
| 客户端截图 | `[固定客户端]/screenshots/` | 用户提供或 AI 可访问时读取 |

## Skills 使用指南

Skills 是技术知识库，不是审批流程。**遇到以下场景必须先读取对应 skill**：

| 触发条件 | Skill 文件 |
|---|---|
| FakePlayer、bot 生命周期、可见性 | `forge-fakeplayer-lifecycle` |
| 实体广播、位置同步、回弹 | `forge-entity-sync-broadcast` |
| 移动、跳跃、碰撞、`travel()`、`move()` | `forge-entity-physics-collision` |
| GUI、Menu、槽位、ContainerData | `forge-container-menu-protocol` |
| 客户端/服务端分工、网络包 | `minecraft-client-server-sync` |
| 事件总线、优先级、取消 | `forge-event-priority-cancel` |
| BlockPos 坐标集合、`betweenClosed` | `forge-blockpos-mutability` |
| 连续失败或证据冲突 | `debugging-root-cause-analysis`<br>`failure-pattern-recognition` |
| 跨多个模块或超过 3 个文件 | `minimal-implementation-planning` |

## 客户端测试规则（重要）

### 测试入口优先级
1. **优先**：游戏内可 `/give` 的测试物品（如 `alice:target_selector`、`alice:mining_scene_tester`）
2. **其次**：简单命令（如 `/alice spawn <name>`、`/alice pathing traverse <direction>`）
3. **避免**：复杂坐标命令和长参数串作为普通测试入口

### AI 在客户端测试中的职责
1. **用户表示"测试通过"或"测试完成"后**，AI **必须主动**读取 Windows 客户端日志：
   - 筛选关键日志前缀（如 `[R2-B Traverse]`、`[MineTask]`、`[BotMiner]` 等）
   - 确认 started/completed/failed 终态
   - 验证实际脚位、支撑、onGround、controller 状态等事实
2. **主动询问**（如果日志不可访问或不足）：
   - 用了哪个物品/命令和哪个按键？
   - 游戏里实际看到了什么？
   - 预期和实际差异是什么？
   - 问题是必现、偶发还是运行一段后发生？
   - 是否有紫黑材质、回弹、卡死、崩溃或聊天异常？
   - 能否提供日志片段、截图或短视频？
3. **不能假称**已经读取了用户未提供且当前环境不可访问的文件

### 验证等级
始终区分并明确标记：
- `IMPLEMENTED`：代码已实现
- `COMPILES`：编译通过
- `SERVER_TESTED`：服务端日志确认行为
- `WINDOWS_CLIENT`：客户端可见行为确认
- `USER_ACCEPTED`：用户明确认可

**构建成功 ≠ 客户端可用**

## Bug 修复纪律

看到 bug 或找到可能修复方式后，**不要马上改代码**。

先说清楚：
```text
已确认事实：...
当前假设：...
证据缺口：...
替代解释：...
最小验证：...
```

然后和用户讨论根因与最小修复。只有方向明确后才实施。

**同一问题第二次失败**、症状不断变化，或服务端日志和客户端观察冲突时：
- ❌ 停止增加 `if`、epsilon、延迟、超时和重试
- ✅ 使用 `debugging-root-cause-analysis` skill
- ✅ 做工作版本/失败版本对照和定向日志探针

## 结束时报告

每次改动结束，简要报告：
```text
改了什么：
为什么改：
使用的 skills：
已验证：[验证等级]
尚未验证：
用户下一步操作：
```

## 用户接手口令

用户说：
> **"接手 Alice"** 或 **"按 docs/START_HERE.md 恢复上下文"**

就执行本文件的启动流程。
