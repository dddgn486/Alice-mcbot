# 软路径测试工具客户端复测说明

- 关联提交：`65f4863`（feat(soft): add soft path test tool with Shift+click mining）
- 计划 ID：`20260824-soft-path-test-tool-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`

## 本次实施（方案 A：扩展现有工具）

**新增功能**：扩展 `soft_path_probe_selector`（指南针）
- **右键方块**：软寻路（已有，不变）
- **Shift+右键方块**：软寻路 + 挖掘（新增）

**冻结边界守护**：
- ✅ MineTask 保持 HARD_PATH 完全不动（不改一行）
- ✅ 独立任务 SoftPathMineTask（Phase.SOFT_NAVIGATE 复用 P1 + Phase.MINING 调 BotMiner）
- ✅ 专用入口（assignSoftPathMine）+ 无自动切换

## 复测步骤（约 2-3 小时）

**先重启客户端**（IDEA 运行 `runClient`，加载新代码）

### T1：右键软寻路（回归测试）
1. 平地放一块石头
2. 获取测试工具：`/give @s alice:soft_path_probe_selector`
3. 持测试工具**右键点击石头**
4. **观察**：
   - Chat 消息：`[alice] soft-path-test: support=... foot=... -> bot`
   - Bot 软寻路到石头旁（foot = 石头上方）
   - 日志含 P1 观测：`soft_phys_obs`（每 10 tick）
   - **石头完整**（不挖掘）
5. **PASS 标准**：bot 到达石头旁，石头完整，P1 观测日志正常

### T2：Shift+右键软寻路+挖掘（核心新功能）
1. 平地放一块石头
2. 持测试工具**Shift+右键点击石头**
3. **观察**：
   - Chat 消息：`[alice] soft-path-mine-test: support=... foot=... -> bot`
   - Bot 软寻路到石头旁（P1 观测日志 `soft_phys_obs`）
   - 日志：`软路径挖掘: 导航完成，开始挖掘`
   - Bot 开始挖掘石头（手臂动画）
   - 石头被破坏，掉落物生成
   - 日志：`软路径挖掘完成`
4. **PASS 标准**：bot 到达后挖掘石头，石头消失，掉落物生成

### T2a：Shift 软寻路失败（边界测试）
1. 搭建封闭房间，bot 在房间外
2. 房间内放石头，bot 无法到达
3. Shift+右键房间内石头
4. **观察**：
   - P1 revalidate/replan 日志（若有可绕路径）
   - 最终失败：`soft_mine_no_path` 或 `soft_mine_search_limit`
   - 石头完整（未挖掘）
5. **PASS 标准**：稳定失败码，石头完整

### T2b：Shift 挖掘失败（保护区测试，可选）
1. 设置保护区（如果有保护机制）
2. 保护区内放石头
3. Shift+右键石头
4. **观察**：
   - 软寻路成功（Phase.SOFT_NAVIGATE 完成）
   - Phase.MINING 进入
   - BotMiner 拒绝：`protected_*` 或类似日志
   - 任务失败：`soft_mine_dig_failed`
5. **PASS 标准**：软寻路成功，挖掘拒绝，稳定失败

### T3：隔离验证（最重要）
1. **普通挖矿命令**：`/alice mine <x> <y> <z>`（或用 `target_selector` 右键矿）
2. **观察日志**：
   - 只含：`MineTask` / `PathExecutor` / `setPos` / `HARD_PATH`
   - **不含**：`soft_phys_obs` / `soft_phys_revalidate` / `NATIVE_TRAVEL` / `SoftPathMineTask`
3. **PASS 标准**：MineTask 保持 HARD_PATH 不动，日志无 soft 关键字

### T4：P1 观测回归（可选）
1. Shift+右键石头，软寻路途中
2. 玩家堵路或攻击 bot（击退）
3. **观察**：
   - P1 观测日志：`soft_phys_obs` / `soft_phys_disruption` / `soft_phys_revalidate` / `soft_phys_replan`
   - 行为与 SoftPathProbeTask 一致（revalidate/replan）
4. **PASS 标准**：P1 观测/revalidate/replan 正常

## 观察点与证据采集

每场景需：
- **latest.log 片段**（含任务启动、Phase 切换、完成/失败码）
- **Chat 消息截图**（可选）
- **Bot 行为截图/视频**（移动轨迹、挖掘动画、方块破坏）
- **T3 隔离**：明确列出 `/alice mine` 日志**无** soft 关键字

## 填写位置

复测后覆盖 `evidence/latest.log`，更新 `evidence/evidence-report.md`（每个场景记录 是/否 + 关键观察 + 日志片段）。完成后告诉我"测试工具复测完毕" + 结果（PASS/FAIL）。

## 注意

- **T3 隔离验证最重要**（确认 MineTask 不受影响）
- 如果时间紧，优先测试：T2（Shift+右键挖掘）+ T3（隔离）
- T1/T4 是回归测试，可简化
