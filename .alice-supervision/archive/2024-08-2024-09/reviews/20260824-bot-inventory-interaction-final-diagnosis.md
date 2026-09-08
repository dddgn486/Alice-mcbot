# Bot Inventory 交互功能：最后一次故障调查结论

- 调查人：Alice 架构监督员
- 调查日期：2026-08-24
- 基线：`f7dc5e9`（方向 B view index 修复，第 8 轮复测仍失败）
- 用户决策：退回 A 方向；冻结交互功能（不放弃）；整理调查信息；交互功能重启前需重新规划

## 结论（一句话）

方向 B（自定义 packet + Screen）与方向 A（AbstractContainerMenu）的**交互功能失败根因相同**：view-model 槽位 index 语义与用户/客户端视觉布局之间不可调和的错位——不是网络层、不是 FakeConnection、不是同步链路问题。

## 证据链

### 第 8 轮日志（`run/logs/latest.log` 14:15-14:17）

```
14:15:06 bot_inv: command player=Dev bot=tango slots=41 code=accepted   ← 打开成功
14:15:07 bot_inv: action act=PICKUP slot=12 code=slot_empty             ← 唯一 action：slot_empty
14:17:35 bot_inv: command ... code=accepted                              ← 重开，之后无 action（"动不了"）
```

### 服务端快照 index 语义（`BotInventoryService.buildSnapshot`）

```java
for (int i = 0; i < 36; i++) slots.add(inv.getItem(i));   // 0-35: hotbar(0-8) + main(9-35)
for (int i = 36; i < 40; i++) slots.add(inv.getItem(i));  // 36-39: armor
slots.add(inv.getItem(40));                                // 40: offhand
```

`Inventory.getItem(index)` 是 vanilla 玩家 Inventory 语义：**0-8 hotbar（在最底部）、9-35 main（上方）、36-39 armor、40 offhand**。

### 客户端布局（`BotInventoryScreen.slotX/slotY`，f7dc5e9 已对齐快照 index）

```java
slotY: 0-8 → 142（hotbar 行）; 9-35 → 84/102/120（main 三行）; 36-39 → 8/26/44/62（armor 列）; 40 → 62（offhand）
```

**布局正确对齐快照 index**（0-8 底部、9-35 上方、36-39 左列、40 右侧）。

### 根因：视觉认知 vs index 语义的错位

1. **用户看到 GUI 的"背包"**（main 行在上、hotbar 行在下），点"有物品的格子" → 客户端 `hitTest` 返回该视觉格子的 index
2. 若用户点 main 行（视觉第 1 行）→ index 9-17 → 服务端 `getItem(9..17)` = main ✓ 应正确
3. 但用户点**视觉底部行**（hotbar 行）→ index 0-8 → 服务端 `getItem(0..8)` = hotbar —— **用户以为那是"背包第 4 行"，实际是 hotbar** → 若该 hotbar 空 → `slot_empty`
4. 用户点**视觉 armor 列** → index 36-39 ✓ 正确
5. 用户点 **offhand** → index 40 ✓ 正确

**矛盾点**：用户对"格子含义"的心智模型（背包 = 连续存储）与 vanilla Inventory 语义（0-8 hotbar 在底部，与 9-35 main 是**同一数组但视觉分离**）冲突。用户看到"第 1 行有物品就点第 1 行"是直觉，但第 1 行是 main（9-17）——**若 bot 的物品实际在 hotbar（0-8，视觉底部行），用户点视觉底部行 → index 0-8 → 若该格是 hotbar 空 → slot_empty**。

**进一步**：`slot=12`（唯一 action）是 main 第 4 格。若 bot 的 index 12 空但视觉上 index 12 有物品 → **渲染与快照不一致**（渲染用 `packet.slots().get(i)`，服务端用 `inv.getItem(i)`——两者 index 语义应一致，但**若快照 buildSnapshot 与 pickupToPlayer 读取的 Inventory 不是同一时刻同一对象**，或 bot 库存被任务/其他路径修改过而快照陈旧 → 点击时已空）。

### 深层设计缺陷（TLM 报告印证）

TLM 报告（`.alice-supervision/research/tlm-maid-inventory-gui-research-20260824.md`）核心结论：
- 必须用**显式 view index 表**（viewIndex → 逻辑区域 → 底层槽位），不能把"快照 index 直通 vanilla Inventory index"当 GUI 契约
- TLM 用 `SlotItemHandler` 把 handler index 与菜单物理 slot index 分离，服务端菜单持有明确 view

## 8 轮失败共同根因归纳

| 轮次 | 方案 | 失败现象 | 根因 |
|---|---|---|---|
| 1-6 | A（AbstractContainerMenu） | 拿取消失/翻倍/Shift 错乱 | 混合 index 分区 + setChanged 链路 + 客户端/服务端槽位语义错位 |
| 7 | B（自定义 packet） | slot_empty | 客户端布局 index 与服务端快照 index 错位（armor/offhand/main/hotbar 布局错） |
| 8 | B + view index 修复 | 仍 slot_empty / 动不了 | 布局对齐了，但**用户视觉心智 vs vanilla index 语义错位** + 快照陈旧风险 |

**统一根因**：**view-model index 语义没有成为服务端与客户端之间的显式契约**。所有轮次都在"客户端猜 index → 服务端按 vanilla 语义读"。

## 冻结交互的边界

1. **方向 B 代码**（`9534a5d`~`f7dc5e9`）：保留（含 view index 修复），不删除、不回滚
2. **方向 A 代码**（`f0ef6fc`~`1621ad2`）：`BotInventoryMenu`/`ModMenuTypes`/`ClientMenuScreens` 保留但停用——可恢复
3. **交互功能**：冻结（暂停继续实现/复测），**不放弃**
4. **其他功能不受影响**：转移/装备渲染/死亡过滤/selector 等已 USER_ACCEPTED 功能保持

## 重启交互功能的必要条件（未来）

1. **定义显式 view slot 契约**：`viewIndex → (逻辑区域, 底层槽位, 可移动规则)` 表，服务端与客户端共用同一 schema
2. **快照 revision + expectedStackFingerprint**：防陈旧快照（用户点击时 bot 库存已被任务修改）
3. **ActionResult 回推**：OK/SLOT_EMPTY/STALE_REVISION/BUSY/INVALID_SLOT 等明确结果 + 新权威快照
4. **客户端布局与用户心智对齐**：用"背包网格 + armor 列 + offhand"的直观布局，但 index 必须经 view 表映射
5. 参考 TLM 模式（MenuProvider 或自定义 packet 均可，但 view 表必须显式）

## 附：已完成的调查信息清单

- `.alice-supervision/research/player-view-other-entity-inventory-20260823.md`（容器菜单可行性）
- `.alice-supervision/research/vanilla-inventory-menu-source-20260823.md`（原版 InventoryMenu 源码）
- `.alice-supervision/research/container-menu-clicked-pickup-source-20260823.md`（PICKUP 同步）
- `.alice-supervision/research/minecraft-container-gui-correct-implementation-20260823.md`（GUI 渲染）
- `.alice-supervision/research/tlm-maid-inventory-gui-research-20260824.md`（TLM 车万女仆专项）
- `.alice-supervision/research/bot-inventory-gui-custom-packet-planner-20260824.md`（方向 B 规划）
- `.alice-supervision/research/forge-custom-screen-c2s-packet-20260824.md`（方向 B 实现调研）
- 本报告（最终故障调查）
