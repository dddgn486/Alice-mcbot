# P1 客户端同步修复最终复测说明

- 关联提交：`ab510fd`（fix(p1): broadcast bot position/velocity packets to real players）
- 计划 ID：`20260824-p1-client-sync-fix-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`

## 本次修复（P1 客户端同步问题）

**问题根因**（已调查确认）：
- ✅ 服务端物理完全正常（bot 碰撞/击退正常，P1 观测日志正确）
- ❌ 客户端同步失效（FakeConnection 丢弃所有 S2C 包，客户端看到陈旧位置）

**修复内容**：
- `FakeConnection.send()`：识别位置/速度包（`ClientboundMoveEntityPacket` / `ClientboundSetEntityMotionPacket` / `ClientboundTeleportEntityPacket`）
- `broadcastToRealPlayers()`：广播给所有真实玩家（排除 BotPlayer）
- **效果**：客户端现在能正确观察 bot 物理状态（碰撞/击退/速度变化）

## 复测重点（T4 重测，约 30-60 分钟）

**先重启客户端**（IDEA 运行 `runClient`，加载修复后的代码）

### T4a：玩家堵路（核心验证）
1. 搭建一格宽通道
2. 玩家站在通道中间堵路
3. 持测试工具 Shift+右键通道尽头的石头
4. **观察**：
   - **修复前**：bot 穿过玩家（客户端看到 bot 无碰撞）
   - **修复后（预期）**：bot 被玩家碰撞箱阻挡，客户端可见 bot 停止或尝试绕路
   - 日志：`soft_phys_obs` 含 `horizontalCollision=true`
   - 日志：`soft_phys_revalidate` 触发（玩家持续堵路时）
5. **PASS 标准**：客户端可见 bot 被阻挡，不穿过玩家

### T4b：击退（核心验证）
1. 持测试工具 Shift+右键远处石头，bot 开始软寻路
2. 途中用剑攻击 bot（击退效果）
3. **观察**：
   - **修复前**：bot 无击退位移（客户端看到 bot 继续前进）
   - **修复后（预期）**：bot 被击退，客户端可见位移动画（向后滑动）
   - 日志：`soft_phys_disruption` 含 `velocityChange` 非零
   - 日志：`soft_phys_revalidate` 触发（击退后重新验证路径）
4. **PASS 标准**：客户端可见 bot 击退位移动画

### T4c：HARD_PATH 不受影响（回归测试）
1. 普通挖矿命令：`/alice mine <x> <y> <z>`
2. **观察**：
   - bot 移动正常（既有 HARD_PATH 行为不变）
   - 客户端可见 bot 位置同步正常
3. **PASS 标准**：HARD_PATH 行为不变，无回归

### T1-T3 回归（可选，快速验证）
- **T1 右键寻路**：右键石头 → bot 软寻路到石头旁，石头完整
- **T2 Shift+右键挖掘**：Shift+右键石头 → bot 软寻路 → 挖掘 → 石头消失
- **T3 隔离验证**：`/alice mine <矿>` → 日志无 soft 关键字

## 观察点与证据采集

每场景需：
- **latest.log 片段**（含 P1 观测日志：soft_phys_obs / soft_phys_disruption / soft_phys_revalidate）
- **关键观察**（3-5 行）：客户端可见 bot 行为（碰撞阻挡/击退位移/正常移动）
- **截图/视频**（推荐）：T4a 玩家堵路时 bot 停止/T4b 击退位移动画

## 填写位置

复测后覆盖 `evidence/latest.log`，更新 `evidence/evidence-report.md`（每个场景记录 是/否 + 关键观察 + 日志片段）。完成后告诉我"P1 最终复测完毕" + 结果（PASS/FAIL）。

## 注意

- **T4a/T4b 是核心验证**（修复前失败，修复后应 PASS）
- T4c 是回归测试（确认 HARD_PATH 不受影响）
- T1-T3 可快速验证（已在上轮通过，这轮确认无回归）
- 如果 T4a/T4b 仍失败，需回报详细现象（bot 是否仍穿过玩家？击退是否仍无效？）

## 成功标准

- ✅ T4a PASS：客户端可见 bot 被玩家碰撞箱阻挡
- ✅ T4b PASS：客户端可见 bot 击退位移动画
- ✅ T4c PASS：HARD_PATH 行为不变
- ✅ T1-T3 无回归（可选）

**成功后**：P1 + 测试工具全部完成，软路径"更真实玩家定位"方向技术可行！
