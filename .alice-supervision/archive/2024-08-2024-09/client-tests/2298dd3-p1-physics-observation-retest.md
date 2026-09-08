# P1 物理观测与扰动恢复客户端复测说明

- 关联提交：`2298dd3`（feat(soft): P1 physics observation and disturbance recovery (revalidate/replan)）
- 计划 ID：`20260824-soft-surface-physics-observation-p1-v1`
- 测试人：`user`
- 状态：`NOT_STARTED`

## 本次实施（P1 - 软路径方向第一步）

**核心**：扩展 SOFT_SURFACE 观测与扰动恢复，**只改隔离的 SoftPathProbeTask/FollowTask**，不触碰挖矿/拾取/道路/隧道/HARD_PATH。

**观测扩展**：
- 速度（deltaMovement）、onGround、fallDistance、support topY、碰撞方向（horizontalCollision/verticalCollisionBelow）
- 水平/垂直偏差、实体接触、击退/速度突变

**扰动恢复（settle → revalidate → replan）**：
- 偏离检测：hDev >2.0、vDev >1.5、noProgressTicks >20、velocityDisruption（速度突变 水平 >0.3、垂直 >0.5）
- REVALIDATE：重验段几何（canTraverse/Ascend/Descend）、支撑、碰撞
- REPLAN：从当前位置重新搜索路径（MAX_REPLAN=3 保护），稳定失败码
- **永不 setPos 校正、永不自动 HARD_PATH fallback**

**日志关键字**：`soft_phys_obs`、`soft_phys_disruption`、`soft_phys_revalidate`、`soft_phys_replan`

## 复测重点（13 场景）

**先重启客户端**（IDEA 运行 `runClient` 加载 `2298dd3`）

### P1-G1 基础物理
- **操作**：平地 10 格 `/alice soft-path-probe <target>`
- **预期**：bot 平地前进，latest.log 含 `soft_phys_obs` 每 10 tick 输出（deltaMove/hDev/vDev/support/onGround/collision/noProgress），无 setPos 校正，bot 真实走到目标
- **PASS 标准**：onGround=true、实际脚位对齐、无传送

### P1-G2 slab/stair
- **操作**：从整格到下半砖/台阶 `/alice soft-path-probe <slab脚位>`
- **预期**：supportTopY 变化（1.0 → 0.5），settle 后 onGround=true，bot 真实站在 slab
- **PASS 标准**：支撑顶面对齐、无伪造 onGround

### P1-G3 一格上升
- **操作**：一格台阶 ASCEND `/alice soft-path-probe <上方脚位>`
- **预期**：action=ASCEND、setJumping=true、跳跃轨迹可见、落地后 onGround=true
- **PASS 标准**：真实跳跃动画、无传送到顶

### P1-G4 一格下降
- **操作**：一格下台阶 DESCEND `/alice soft-path-probe <下方脚位>`
- **预期**：action=DESCEND、settle 进入、fallDistance >0、重力生效、onGround=true
- **PASS 标准**：真实下落、无瞬移

### P1-G5 墙碰撞
- **操作**：平地前进途中玩家放方块封路
- **预期**：`horizontalCollision=true`、noProgressTicks++、revalidate 触发、日志含 `soft_phys_revalidate_blocked`、最终 FAILED 或 REPLAN（若侧向可绕）
- **PASS 标准**：不穿墙、不传送、稳定失败/重规划

### P1-G6 低天花板
- **操作**：ASCEND 上方有方块（跳跃途中头撞顶）
- **预期**：跳跃失败、revalidate blocked、FAILED with `soft_phys_revalidate_blocked`
- **PASS 标准**：不穿顶、稳定失败

### P1-G7 实体推挤
- **操作**：bot 前进时玩家站在路径上堵路
- **预期**：`horizontalCollision=true` 或 entities >0、noProgressTicks++、revalidate 等待（最多 20 tick）或 REPLAN
- **PASS 标准**：不穿玩家、有限等待后重规划

### P1-G8 击退
- **操作**：bot 前进时玩家攻击（用剑击退 bot）
- **预期**：`soft_phys_disruption` 日志、`velocityChange` 非零、revalidate 进入、速度归零后继续 settle 或 REPLAN
- **PASS 标准**：真实击退动画、无传送回路径

### P1-G9 support 动态变化
- **操作**：settle 阶段玩家破坏脚下支撑（执行中挖掉段目标下方方块）
- **预期**：supportTopY 变化/NaN、revalidate blocked、FAILED
- **PASS 标准**：不悬空伪造 onGround

### P1-G10 follow 碰撞重规划
- **操作**：`/alice follow on`，玩家绕障碍走
- **预期**：每 10 tick replan（已有）+ 碰撞立即 replan（新增）、路径可见改变
- **PASS 标准**：follow 不卡死、不穿墙

### P1-G11 危险区域
- **操作**：路径途经岩浆/火/深坑
- **预期**：已有 SurvivalSystem hazard FAILED（不改）；若 P1 未扩展危险判定，只需确认不因 replan 绕过危险检查
- **PASS 标准**：不跳岩浆、稳定失败

### P1-G12 MAX_REPLAN 保护
- **操作**：人工构造循环重规划场景（每次 replan 后再封新路，连续封路 4 次）
- **预期**：第 3 次 replan 后 FAILED with `soft_phys_max_replan`
- **PASS 标准**：不无限重规划、稳定失败码

### P1-G13 隔离验证
- **操作**：执行 `/alice mine <矿>`、`/alice road-build`、transfer
- **预期**：确认无 soft 偷接入；MineTask/DropCollectionTask/RoadBuildTask 不含 P1 观测/replan 日志
- **PASS 标准**：HARD_PATH 矿链不受影响

## 观察点与证据采集

每个场景需：
- **latest.log / debug.log**：含 `soft_phys_obs`、`soft_phys_disruption`、`soft_phys_revalidate`、`soft_phys_replan` 关键字及其详细参数
- **截图/视频**：bot 真实移动轨迹、碰撞/击退动画、最终脚位/支撑
- **失败场景**：稳定失败码（`soft_phys_*`）、无崩溃/栈溢出、bot 停在安全位置（不悬空/不岩浆中）

## 填写位置

复测后覆盖 `evidence/latest.log` + `evidence/debug.log`，更新 `evidence/evidence-report.md`（每个场景记录 是/否 + 截图链接 + 关键日志片段；P1-G13 隔离必须明确列出矿链日志无 soft 观测）。完成后告诉我"P1 第 X 轮复测完毕"，并附上关键日志片段。
