# Bot 主手残留修复复测说明

- 关联提交：`f33292c`（fix: sync bot inventory after destination transfer leg）
- 计划 ID：`20260824-bot-mainhand-residue-fix-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`

## 修复说明

**问题**（你第 3 轮 GUI 测试时记录的独立 bug）：转移任务后，主手物品实际已放入箱子，但**外部模型手上仍显示有该物品**，且 bot 背包中不可见。

**根因**：`botToDestinationChest`（第二段 leg：bot→目标箱）的 `removeBot` 从 bot 背包移除物品（含主手 selected 槽）后，**没有同步客户端**。只有第一段 leg（源箱→bot）有同步。→ 客户端外部模型/背包未刷新 → 主手残留。

**修复**：第二段 leg 移除物品后补上 `syncBotInventorySlots`（与第一段对称）。

## 复测步骤（约 10-15 分钟）

**先重启客户端**（IDEA 运行 `runClient` 加载 `f33292c`）

### 核心验证
1. 设置一个 transfer（如源箱 3 个铁锭 → bot → 目标箱）
2. 执行转移：`/alice transfer-test ...` 或 selector
3. **观察（重点）**：
   - Bot 搬运途中：手上应显示物品（第一段 leg 已同步）
   - **任务完成后**：Bot 外部模型手上应**不再显示物品**（修复前会残留）← 核心
4. 打开 bot 背包（如有方式）或观察 Bot 实体：背包物品数应与 ledger 一致

### 回归
- 转移正常完成（source→bot→dest）
- 既有转移场景不受影响

## 观察点

| 场景 | 修复前 | 修复后（预期） |
|---|---|---|
| transfer 完成后 Bot 外部模型主手 | 残留物品模型 | 手上干净 |
| transfer 完成后 bot 背包数据 | 客户端不刷新 | 正确刷新 |

## 填写位置

测试后覆盖 `evidence/latest.log` + `evidence/debug.log`（可复用之前测试包目录），把结果告诉我。完成后回复"主手残留复测完毕" + 结果（PASS/FAIL）。
