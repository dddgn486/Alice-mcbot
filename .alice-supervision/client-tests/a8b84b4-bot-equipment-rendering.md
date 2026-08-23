# Alice Bot 手持物品渲染修复客户端测试记录

- 关联提交：`a8b84b4`（fix: sync bot main hand equipment for entity rendering）
- 计划 ID：`20260822-bot-equipment-rendering-fix-v1`
- 调研报告：`.alice-supervision/research/bot-held-item-rendering-20260822.md`
- 前置修复：`bd9f0c3`（fix: sync bot inventory to client after transfer insert）
- 测试人：`user`
- 状态：`NOT_STARTED`
- 测试根目录：`.alice-supervision/client-tests/a8b84b4-bot-equipment-rendering/`
- 场景证据目录：`.alice-supervision/client-tests/a8b84b4-bot-equipment-rendering/evidence/`

## 修复说明

**问题**：Bot 搬运物品途中，实体模型上看不到手持物品（客户端测试 2026-08-22 23:53-23:56 观察）。

**根因**：`bd9f0c3` 只发送 `ClientboundContainerSetSlotPacket`（inventory GUI 数据同步），未发送 `ClientboundSetEquipmentPacket`（equipment 实体渲染同步）。

**修复**：在 `syncBotInventorySlots()` 内部新增 `ClientboundSetEquipmentPacket` 同步主手 equipment，客户端实体渲染器能显示 Bot 手持物品模型。

**服务端验证**：compileJava PASS、既有 fixture 保持 PASS（00:15:17）。

## 使用方式

一次完整客户端会话连续测试 V1-V3（V4-V5 可选）；不为每个场景重启客户端或复制日志：

```text
.alice-supervision/client-tests/a8b84b4-bot-equipment-rendering/evidence/
  evidence-report.md        # 精简总表单（用户只填 3 部分）
  latest.log                # 本轮唯一完整日志
  debug.log                 # 本轮唯一完整日志
  V1-basic/
    evidence-report.md      # 场景表单
    V1-bot-model.png        # Bot 手持物品模型截图
  V2-different-items/
    evidence-report.md
    V2-bot-model.png
  V3-multi-slot/
    evidence-report.md
    V3-bot-model.png
```

## 精简验证矩阵（V1-V3，约 15-20 分钟）

| ID | 场景目录 | 最少动作 | 核心观察 | 最少证据 | 依据 |
|---|---|---|---|---|---|
| V1 | `evidence/V1-basic/` | Transfer 3 个铁锭 | Bot 搬运途中**手持铁锭模型可见** | 场景表单、Bot 模型截图 | 调研报告 V1 基本功能 |
| V2 | `evidence/V2-different-items/` | 分别 transfer diamond/stone/pickaxe | 所有物品类型**正确渲染** | 场景表单、Bot 模型截图 | 调研报告 V2 不同物品 |
| V3 | `evidence/V3-multi-slot/` | Transfer iron_ingot×64（分散多槽位） | 手持物品与**主手槽位一致** | 场景表单、Bot 模型截图 | 调研报告 V3 多槽位 |

**V4（可选）**：打开 bot inventory GUI → 验证 GUI 和手持模型都正确（bd9f0c3 回归测试）  
**V5（可选）**：F3 监控 packet 频率 → 记录性能影响

## 关键观察时机

**核心时刻**：Bot 从源箱拿到物品后、在移动到目标箱途中（约 3-5 秒窗口）

- ✅ **修复前**（bd9f0c3）：Bot 手上是空的，看不到物品模型
- ✅ **修复后（预期）**：Bot 手上显示物品模型（铁锭/钻石/镐子等）

**观察角度**：从侧面或正面观察 Bot，能清楚看到手持位置

## 共用停止条件

出现 Bot 手持物品仍不可见、物品模型显示错误、崩溃或卡死时立即停止。保留该场景目录、完整日志和世界副本，不重试已发生变异的请求。

## 填写位置

所有需要填写的字段（用户决策、场景汇总、异常）只填写在 `evidence/evidence-report.md`（精简总表单），不在此文件重复填写。监督员从日志提取基本信息（日期/版本/Bot/SHA）。

## 与 bd9f0c3 的关系

- `bd9f0c3` 解决：GUI 看不到物品（ClientboundContainerSetSlotPacket）
- `a8b84b4` 解决：实体模型上看不到手持物品（ClientboundSetEquipmentPacket）
- 两个修复互补：bd9f0c3 修复 GUI 可见性，a8b84b4 修复模型渲染
- 如果 V3 出现"手持物品与 GUI 不一致"，需实施方案 A1（优先主手槽位）
