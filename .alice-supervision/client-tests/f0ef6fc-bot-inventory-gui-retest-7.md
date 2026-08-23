# Bot Inventory GUI 复测说明（第 7 轮 - 方向 B 全新实现）

- 关联提交：`9534a5d`（feat: rewrite bot inventory GUI as custom packet + screen (direction B)）
- 计划 ID：`20260824-bot-inventory-gui-custom-packet-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`（第 7 轮复测）

## 这是彻底的重新实现（方向 B）

前 6 轮是容器菜单（AbstractContainerMenu）方案，核心问题是"混合菜单 index 分区 + setChanged 同步链路"，导致拿取消失/物品翻倍/Shift 错乱。

**这次彻底绕开**容器菜单，改用自定义 packet + Screen，核心是**唯服务端权威**：
- 客户端**只发操作意图，不本地改数据**
- 收到服务端回推的快照才刷新显示
- 一切写入经服务端校验（任务保护/槽位合法/装备规则）

## 复测重点（第 7 轮）

**先重启客户端**（IDEA 运行 `runClient` 加载 `9534a5d`）

### 核心验证
1. **打开**：`/alice bot-inventory tango` → GUI 打开，显示 bot 36+4+1 槽位
2. **拿取（G5）**：点击 bot 槽拿物品到玩家背包 → **物品不再消失**
3. **放入（G6）**：从玩家背包放物品到 bot → **不再翻倍**
4. **Shift 快速移动（G7）**：Shift+点击 → **不重复、不残留**
5. **装备穿戴（G8）**：把可穿装备放入 armor/offhand → 正常穿上

### 回归
G1（op 入口）、G2（36 槽显示）、G3（装备槽）、G4（主手槽）、G9（任务保护）、G10（bot 不在场）、G11（无崩溃）

## 验证语义（关键）

**为什么这次应该好**：
- 前 6 轮失败是因为客户端和 bot 的槽位混编在一个 slots 列表，同步链路错乱
- 现在客户端只用服务端快照渲染，操作经服务端校验后回推快照——**客户端永不自行改变显示**，所以不可能"拿取消失/翻倍"

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`。如果仍出问题，记录 `bot_inv:` 日志（服务端有 `bot_inv: action ... code=accepted/task_active` 关键词）反馈给我。完成后告诉我"方向 B 第 7 轮复测完毕"。
