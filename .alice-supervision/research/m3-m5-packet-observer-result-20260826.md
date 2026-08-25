# M3/M5 Packet 诊断观测结果——D0-D4（2026-08-26）

- Work package：`20260826-m3-m5-packet-observer-v1`
- 任务书：`.alice-supervision/research/m3-m5-packet-observer-task-20260826.txt`
- 规划：`.alice-supervision/research/m3-m5-packet-diagnostic-plan-20260826.md`
- 基线：`f4f6ca6` 后代；当前 F1 修复/C-1 已在工作树历史
- 性质：只采证；不构成 M3/M5 修复授权或客户端验收

## 实施范围

仅修改 `src/main/java/com/dddgn/alice/bot/FakeConnection.java`，新增默认关闭的 `-Dalice.packet.observer=true` 诊断旁路。默认值为 false，未启用时发送路径字节级行为保持原样。

观测器在既有 recipient 条件通过后、原有 `player.connection.send(packet)` 前记录，不改变：packet 类型筛选、recipient 集合、发送顺序、packet 对象、callback、tracking 或客户端语义。

记录字段（server send seam 可得）：
- `corr`、server `tick`
- source bot UUID/name/entityId
- concrete packet class、packet entityId（Move packet 通过当前 level 解析；不可解析记录 `-1`）
- Move：`hasPosition/hasRotation/xa/ya/za/yaw/pitch/onGround`
- Motion：`id/xa/ya/za`
- Teleport：`id/x/y/z/yaw/pitch/onGround`
- recipient UUID/name/entityId/dimension/distance/sameLevel
- `source=manual_fake_connection`
- duplicate key `(serverTick, packetEntityId, recipient UUID)` 与累计计数

## 验证证据

### 编译

`./gradlew compileJava --console=plain`：**BUILD SUCCESSFUL**。

首次编译曾因观测器自身 API 错误失败（缺少 `BotLog` import、Move packet 无公开 `getEntityId()`）；已最小修正为 `getEntity(level)` 解析，随后编译通过。该失败不是既有代码回归。

### 既有 headless 套件

命令：`timeout 180 ./gradlew runServer -Dalice.selftest.auto=true --console=plain`。

外部 180 秒预算到期，exit `124`，因此**不宣称完整套件 PASS**。超时前日志已观察到：

- `TRANSFER_FIXTURE_SUITE PASS`
- `BOT_INVENTORY_FIXTURE_SUITE PASS`
- `SOFT_PHYSICS_OBSERVATION_SUITE PASS`
- F1：`gHurtOk=true`, `mHurtOk=true`, `health=20.000->19.000->17.500`
- F2：`PASS dBase=0.100 dKnock=0.890`
- F4：`PASS ratioH=0.728`
- F5：`PASS dSame=0.980 dKeep=1.000 dDelayed=1.890`
- `BOT_PHYSICS_C1_CONSUME` 正常触发（样例 tick=246/361/383/440/500）
- F3b/tickChain 仍为已知独立 FAIL，不归因于本观测器

### 观测器 smoke

命令：`timeout 35 ./gradlew runServer -Dalice.selftest.auto=true -Dalice.packet.observer=true --console=plain`。

exit `124`，本次短 smoke 未捕获 `M3_M5_PACKET_OBSERVER` 样本；不能据此宣称 D1 packet 运行证据已闭合。报告记录的是观测器编译/旁路静态验证，不是 packet runtime PASS。

`git diff --check`：通过（无输出）。

## D0-D4 状态

- D0：当前 server Git/计划身份可记录；Windows good/bad/current build SHA、world/scene identity 未在本轮获得，标记 `INSUFFICIENT_EVIDENCE`。
- D1：观测器已实现字段，但本轮无 runtime 样本；tracking/manual 对照仍不可得。
- D2：客户端接收/渲染、client tick、客户端来源标识不在当前 server checkout/seam，标记 unavailable；服务端 send/callback 不等于客户端收到。
- D3：既有 F1 日志可证明 hurt 成功、health 下降但 d1/d2=0；本观测器未新增受控攻击时间线，不能把 M3 分类为同步问题。
- D4：BotManager 既有 spawn/clear 日志可用于服务端生命周期，但本轮未建立 Windows absent/present/cleared 对照，玩家 client position/velocity/onGround 不可得。

## 事实、推断与限制

- 事实：FakeConnection 仍只转发既有三类 packet；本改动只记录同一发送边界。
- 事实：同维度真实玩家 recipient 集合和发送顺序未改变。
- 未证实：tracking duplicate、客户端实际接收、错误 entityId、共享 packet 编码问题、M5 跳跃因果。
- 不可得：clientTick、client receive/render、vanilla tracking source、Windows build SHA/run identity、M3 受控攻击后 N tick position timeline、D4 玩家状态 timeline。
- 既有 F1 服务端证据仍是 `hurtOk=true + health↓ + delta=0`；按规划停止条件，不能把 M3 当作纯 packet 同步问题。

## 回滚/禁用

- 默认 `alice.packet.observer=false`，不启用任何诊断输出。
- 回滚只需移除本提交；未修改 FakeConnection 的业务分支。
- 若后续 Windows 诊断发现 entityId/recipient 错误、重复无法解释、观察器改变时序或客户端异常扩大，应停止并回滚本诊断提交，不扩大到修复。

## Git/交付状态

- 本报告与 HANDOVER 诊断段随诊断提交记录。
- Windows 端仅在确认同步到诊断 commit 后，按 D0-D4 矩阵采集；本报告不标记 M3/M5 `USER_ACCEPTED`。
- 下一安全步：监督员审核本报告；如需 runtime packet/client receive 证据，另行授权 Windows 诊断 hook或受控 server observer 运行。
