# Alice Bot Inventory GUI 复测说明

- 关联提交：`f4bf79a`（fix: align bot inventory GUI layout, add offhand, fix quick-move）
- 计划 ID：`20260823-bot-inventory-gui-fix-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`（复测）
- 测试根目录：`.alice-supervision/client-tests/f0ef6fc-bot-inventory-gui/`

## 修复说明

**本次修复**（提交 `f4bf79a`）三项：
1. **布局错位**：改用原版 `InventoryMenu` 权威坐标（armor 列 + offhand + 27 main + 9 hotbar），槽位互不重叠，与背景对齐
2. **拿取失败**：`quickMoveStack` bot→玩家方向修复（Shift 快速移动 + 普通点击拿取恢复正常）
3. **副手槽**：新增 offhand（index 40）显示，纳入任务保护
4. **背景**：改为自绘纯色面板 + 槽位格（不再用固定 vanilla 纹理，彻底消除错位）

**此前 FAIL 项**：G3（装备槽未显示）、G4（主手槽错位）、G9（空闲拿取失败）

---

## 复测步骤（一次会话）

### 先重启客户端（IDEA 运行 `runClient` 加载 `f4bf79a`）

**G3 装备槽**：`/alice bot-inventory tango` → 左侧应显示 4 个装备槽（头盔/胸甲/裤子/鞋子）

**G4 主手槽**：Bot 当前手持物品应在槽位中正确显示（与 equipment 渲染一致，不错位）

**G9 空闲交互**（重点）：Bot 空闲时打开 GUI →
- 从 bot 拿取物品到玩家背包 → **应成功**（上次失败）
- 放入物品到 bot → 应成功
- Shift+点击快速移动 → 应成功

**G11 副手槽**（新增）：GUI 应显示副手槽（offhand）

**回归确认**：G2（36 槽显示）、G5（堆叠）、G6（GUI 隔离）、G8（任务保护可读禁写）、G10（无崩溃）保持 PASS

---

## 核心观察

上次的三个 FAIL 点：
- ✅ G3 装备槽：现在应**可见**（4 个装备槽）
- ✅ G4 主手槽：现在应**对齐**（不错位）
- ✅ G9 拿取：现在应**能拿取**（从 bot 到玩家）

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`（修改场景结果为复测结果），并更新 G3/G4/G9/G11 场景表单。完成后告诉我"Bot Inventory GUI 复测完毕"。
