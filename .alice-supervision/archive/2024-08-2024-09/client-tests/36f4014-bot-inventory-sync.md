# Alice Bot Inventory 同步修复客户端测试记录

- 关联提交：`36f4014`（fix: sync bot inventory to client after transfer insert）
- 计划 ID：`20260822-bot-inventory-sync-fix-v1`
- 调研报告：`.alice-supervision/research/category-a-phenomena-root-cause-20260822.md`
- 测试人：`user`
- 状态：`NOT_STARTED`
- 测试根目录：`.alice-supervision/client-tests/36f4014-bot-inventory-sync/`
- 场景证据目录：`.alice-supervision/client-tests/36f4014-bot-inventory-sync/evidence/`

## 修复说明

**问题**：Bot 通过 transfer 拿到物品后，客户端看不到，重启后才能看到。

**根因**：`ChestBotTransferPrimitive.insertBot()` 写入 bot inventory 后未同步客户端。

**修复**：新增 `syncBotInventorySlots()` 方法，在 `insertBot()` 后遍历 36 槽位发送 `ClientboundContainerSetSlotPacket`。

**服务端验证**：compileJava PASS、既有 fixture 保持 PASS（23:42:32）。

## 使用方式

一次完整客户端会话连续测试 V1-V3（V4 可选）；不为每个场景重启客户端或复制日志：

```text
.alice-supervision/client-tests/36f4014-bot-inventory-sync/evidence/
  evidence-report.md        # 精简总表单（用户只填 3 部分）
  latest.log                # 本轮唯一完整日志
  debug.log                 # 本轮唯一完整日志
  V1-basic/
    evidence-report.md      # 场景表单
    V1-bot-inventory.png    # Bot 物品栏截图
  V2-multi-slot/
    evidence-report.md
    V2-bot-inventory.png
  V3-selector/
    evidence-report.md
    V3-bot-inventory.png
```

## 精简验证矩阵（V1-V3，约 20-30 分钟）

| ID | 场景目录 | 最少动作 | 核心观察 | 最少证据 | 依据 |
|---|---|---|---|---|---|
| V1 | `evidence/V1-basic/` | `/alice transfer-test` 转移物品 | Bot 拿到物品后**立即可见**（不需重启） | 场景表单、Bot 物品栏截图 | 调研报告 V1 基本功能 |
| V2 | `evidence/V2-multi-slot/` | transfer 多个物品（如 10 个） | 所有槽位**实时同步** | 场景表单、Bot 物品栏截图 | 调研报告 V2 多槽位 |
| V3 | `evidence/V3-selector/` | selector 路径提交 | 同步**一致**（与 transfer-test 行为相同） | 场景表单、Bot 物品栏截图 | 调研报告 V3 selector 路径 |

**V4（可选，不作为验收必需）**：观察 packet 频率与延迟（记录但不强制）。

## 共用停止条件

出现 bot 物品栏仍不可见、物品数量错误、崩溃或卡死时立即停止。保留该场景目录、完整日志和世界副本，不重试已发生变异的请求。

## 填写位置

所有需要填写的字段（用户决策、场景汇总、异常）只填写在 `evidence/evidence-report.md`（精简总表单），不在此文件重复填写。监督员从日志提取基本信息（日期/版本/Bot/SHA）。
