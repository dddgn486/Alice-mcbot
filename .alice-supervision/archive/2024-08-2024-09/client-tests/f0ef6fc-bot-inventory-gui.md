# Alice Bot Inventory GUI 客户端测试说明

- 关联提交：`f0ef6fc`（feat: add bot inventory GUI with task read-only guard）
- 计划 ID：`20260823-bot-inventory-gui-v1`
- 深度调研：`.alice-supervision/research/bot-inventory-gui-20260823.md`
- 测试人：`user`
- 状态：`NOT_STARTED`
- 测试根目录：`.alice-supervision/client-tests/f0ef6fc-bot-inventory-gui/`
- 证据目录：`.alice-supervision/client-tests/f0ef6fc-bot-inventory-gui/evidence/`

## 修复说明

**功能**：玩家可用 `/alice bot-inventory <botName>` 打开 Bot 的 Inventory GUI（方案 A AbstractContainerMenu 完整交互），查看/拿取/放入 Bot 的 36 普通槽 + 4 装备槽 + 主手槽，并带**任务保护**（Bot 执行任务时只读可看、禁写）。

**关键设计**：
- 打开入口：`/alice bot-inventory tango`（需 op = 权限等级 2）
- 任务保护：Bot 正在执行 transfer/mine 时，GUI 可打开查看，但不能点击写入（bot 槽 clicked/quickMoveStack 被拦截）
- 主手槽：不单独重复展示（selected 普通槽是权威，与 equipment 渲染对齐）

## 使用方式

一次完整客户端会话连续测试 G1-G10；不为每个场景重启客户端或复制日志：

```text
.alice-supervision/client-tests/f0ef6fc-bot-inventory-gui/evidence/
  evidence-report.md        # 精简总表单（用户只填 3 部分）
  G1-open/evidence-report.md        # 场景表单
  G8-task-guard/evidence-report.md  # 场景表单
  G9-idle-interact/evidence-report.md
  latest.log                # 本轮唯一完整日志
  debug.log                 # 本轮唯一完整日志
```

## 核心测试场景

### G1 入口与授权
1. 确保自己是 `op`（权限等级 2）
2. `/alice bot-inventory tango` → **预期**：GUI 打开，显示 Bot inventory
3. 找一个非 op 玩家（或临时用非 op 账号）测试 → **预期**：被拒绝（`unauthorized` 或无权限提示）

### G2-G5 槽位显示（GUI 内观察）
- **G2**：GUI 显示 36 格普通槽（4x9 网格），内容与实际一致
- **G3**：左侧 4 个装备槽（头盔/胸甲/裤子/鞋子）正确显示
- **G4**：主手槽（selected）在实际显示中能反映 Bot 当前手持物品（与之前 equipment 渲染一致）
- **G5**：空槽显示空白、多格堆叠显示数量

### G6 GUI 隔离
- 打开 bot-inventory GUI 后，确认不影响普通箱子 GUI、玩家自身背包
- 关闭 bot-inventory GUI，确认原版 GUI 正常

### G7 bot 不在场
- 用不存在的 bot 名：`/alice bot-inventory nonexistent` → **预期**：`bot_unavailable`，无 GUI

### G8 任务保护（核心！）
1. 让 Bot 执行一个任务（如 `/alice mine 10 stone` 或 transfer）
2. 任务进行中，`/alice bot-inventory tango` 打开 GUI
3. **预期**：GUI 可以打开查看（能看物品）
4. **预期**：点击 bot 的槽位**不生效**（物品不移动，inventory 不变）
5. 关闭 GUI，确认任务正常完成（未被破坏）

### G9 空闲交互
1. Bot 空闲（无任务）
2. `/alice bot-inventory tango` 打开 GUI
3. **预期**：可以拿取 bot 的物品到玩家背包、放入物品到 bot、Shift+点击快速移动
4. 确认操作后 bot inventory 正确变更

### G10 客户端无服务端崩溃
- 整个测试过程，观察服务端/客户端日志，无 `NoClassDefFound` / `ClassNotFoundException` / 崩溃栈

## 预期日志关键字

```
bot_inventory: query player= bot= slots=36+4 code=accepted
bot_inventory: bot_unavailable          # G7 bot 不在场
bot_inventory_menu: taskActive=true     # G8 任务保护（可读禁写）
```

## 共用停止条件

出现 GUI 崩溃、类加载错误、inventory 异常变更、任务被破坏（transfer/mine 失败）时立即停止，保留该场景目录、完整日志和世界副本，不重试已发生变异的请求。

## 填写位置

所有需要填写的字段（用户决策、场景汇总、异常）只填写在 `evidence/evidence-report.md`（精简总表单），场景观察填在对应 `G*-*/evidence-report.md`。监督员从日志提取基本信息（日期/版本/Bot/SHA）。
