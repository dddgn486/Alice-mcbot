# 佐证任务：BotPlayer.tick() NPE 证据采集（F1-F6 定案佐证，B3 定案或排除）

- 派发：Alice 架构监督员
- 执行：Alice 主开发员（session-30b693e7）
- 日期：2026-08-25
- Alice 基线：`709bc5a`（HEAD，F1-F6 fixture 已实施验收）
- 依据：active plan `20260825-f1f6-bot-physics-assertions-v1`（区分表 §2.3："B3 定案需 NPE 专项日志佐证"）
- 性质：**只读证据采集**——不改任何业务实现；定案由监督员按区分表执行

## 背景（监督员已核实的代码事实，避免空挖）

`BotPlayer.tick()`（`src/main/java/com/dddgn/alice/bot/BotPlayer.java:28-36`）：
```java
@Override
public void tick() {
    try {
        super.tick();
    } catch (NullPointerException exception) {
        // 打印堆栈定位 NPE 来源(玩家化后不应触发；吞掉会静默中断物理)
        exception.printStackTrace();
    }
}
```

**关键事实**：catch 用 `exception.printStackTrace()`——输出目标是 **System.err（stderr/console）**，**不是 latest.log**（Minecraft 日志走 log4j 文件输出）。因此：
- **先查 stderr/console 重定向**（selftest 运行的 stdout/stderr 捕获：run 目录、nohup 文件、服务器启动脚本的重定向目标）
- **不要把"latest.log 无 NPE 栈"当作"无 NPE"的否定证据**——这是错误否定

## 任务内容（只读证据采集，三查一录）

### 1. 运行期栈查证（stderr 路径优先）
- 定位 F1-F6 fixture 运行（`-Dalice.selftest.auto=true`）的 **stderr / console 输出捕获**（run/logs/ 下的非 latest.log 文件、nohup/log 重定向文件、或 IDE 控制台缓冲）
- 搜索关键字：`NullPointerException`、`at com.dddgn.alice.bot.BotPlayer`、`at net.minecraft.server.level.ServerPlayer.tick`、`aiStep`、`pushEntities`
- 记录：栈是否出现、完整栈内容（含行号）、出现频率、与 F1-F3 FAIL 的时间窗口是否对齐
- **若栈找到** → 记录为 B3 定案佐证（区分表 §2.3）

### 2. 代码级 NPE 候选点核查（当栈未找到时，或补充）
`super.tick()` 链上可能 NPE 的字段/调用点（只列候选，不预定结论）：
- `ServerPlayer.tick()` → `aiStep()` 链的字段访问（`horizontalCollision` 相关、`pushEntities`、`level()`、`connection`）
- BotPlayer 注册路径（`PlayerList.placeNewPlayer(伪造Connection)`）后，`connection` 字段是否在 tick 时可达
- 列出"哪一行在什么条件下可能 NPE"的**代码级候选清单**（文件+行号+条件），供区分表对照
- **不写"根因就是它"**（监督员定案）

### 3. bot.tick() 实际驱动链核查
- BotPlayer.tick() 由谁驱动：ServerLevel 实体 tick 循环？还是 BotManager 手动调用？
- 核查：`ServerLevel`/`ServerPlayer` 的 tick 触发路径（代码证据：谁调用 bot.tick()）
- 目的：区分"tick 链被驱动但 NPE 中断 aiStep" vs "tick 链根本没被驱动"（后者会推翻"NPE 吞错"机制，指向其他候选——只记录事实，交给区分表）

### 4. 证据落盘
- 报告写到 `.alice-supervision/research/npe-evidence-result-20260825.md`：
  - stderr 查证结果（栈全文/无栈+查证路径）
  - 代码级候选点清单（文件+行号+条件）
  - tick 驱动链核查结论
  - 证据分级（已确认事实/推断/待调查）
  - **不写根因定案**（监督员按区分表执行）
- 完成后 report_task 附报告路径

## 禁止事项（硬性）

- **不改业务实现**：`BotPlayer`/`FakeConnection`/`SoftMovementPrimitive`/BotManager 的 tick 驱动 零改动
- **不改 Fixture**：`BotPhysicsAssertionFixture` 零改动（如确需补充采集手段，先 request_input 报监督员批准，不得自行改）
- **不判定根因**：不写"根因是 NPE/是驱动缺失"——只采证据
- **不实现修复**：不修 tick、不修 catch、不修 hurt 链
- 不将"printStackTrace 走 stderr"之外的新日志手段加入业务代码（如需要，报监督员评估）

## 验收标准

- 三查完成（stderr 栈查证 / 代码级候选点 / tick 驱动链），各有代码或日志证据
- 报告落盘（含证据分级、不写根因定案）
- 未修改任何业务实现与 Fixture
- 未判定根因、未实现修复