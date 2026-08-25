# F1-F6 服务端物理断言 — 实施证据报告（定案前置）

- 执行：Alice 主开发（session-30b693e7）
- 日期：2026-08-25
- 基线：`6c2b461`（HEAD）
- 计划：`20260825-f1f6-bot-physics-assertions-v1`（APPROVED_FOR_IMPLEMENTATION）
- 性质：**只收集服务端证据，不判定根因、不实现修复**；根因由监督员按 active plan §根因区分表定案

## 实施变更（git status 确认）

| 变更 | 类型 | 文件 |
|---|---|---|
| 新增 | fixture | `src/main/java/com/dddgn/alice/task/BotPhysicsAssertionFixture.java` |
| 修改 | 一行调用 + 一行日志 | `src/main/java/com/dddgn/alice/task/BotSelftest.java`（`setup()` 内 spawn 之后） |
| 新增 | 本报告 | `.alice-supervision/research/f1-f6-assertion-result-20260825.md` |

未改动：`BotPlayer` / `FakeConnection` / `SoftMovementPrimitive` / 5 个 task 语义 / `SoftPhysicsObservationTest`（既有 P0 门禁）/ skills-manifest / state-machine / 业务实现，全部零改动。未写档（fixture 不触碰 BotWorldData/saveToWorld）。

## Headless 运行条件

- `./gradlew runServer -Dalice.selftest.auto=true`（服务端自动执行，无需真实客户端）
- 运行时刻：2026-08-25 14:26:30，corr=`147947fa`
- 证据来源：`run/logs/latest.log`（行号 117–141）
- 既有 suites 保持 PASS：TRANSFER_FIXTURE_SUITE / TRANSFER_SELECTION_FIXTURE_SUITE / TRANSFER_SELECTOR_EVENT_FIXTURE_SUITE / TRANSFER_SELECTION_COMMAND_PARSE_FIXTURE_SUITE / BOT_INVENTORY_FIXTURE_SUITE / SOFT_PHYSICS_OBSERVATION_SUITE

## 汇总结果

`BOT_PHYSICS_ASSERTION_SUITE FAIL corr=147947fa tick=101 f1=false f3=false f4=true f5=true f2=true tickChain=false`（latest.log:134 / 汇总行 latest.log:141）

| F 项 | 结果 | 数值/证据 | latest.log 行号 |
|---|---|---|---|
| F1 hurt→delta | **FAIL** | `generic=false gHurtOk=false d1=(0.000,0.000,0.000) health=20.000->20.000`；`mob=false mHurtOk=false d2=(0.000,0.000,0.000) health=20.000->20.000`（generic 与 Zombie mob 攻击双源均 hurt 返回 false，health 不变、delta 恒 0） | 117 |
| F3 push→delta+位移 | **FAIL** | `pushWritten=true pushD=(0.500,0.000,0.000)`（push 写入路径正常）；`entityPushed=false`（collision 箱内 Zombie 推挤后 botTick delta=(0.000,0.000,0.000)、disp=(0.000,0.000,0.000)，实体推挤链未产生位移） | 118 |
| F4 travel 前后摩擦衰减 | **PASS** | `chainRan=true ratioH=0.728 dBefore=(0.300,0.000,0.000) dAfter=(0.218,-0.078,0.005)`（travel 执行、水平摩擦衰减约 0.73 并含重力 -0.078 落地；travel/aiStep 链运行） | 120 |
| tickChain（F4 伴随） | **FAIL** | `horizontalCollision=false->false verticalCollisionBelow=false->false`（墙体撞击后碰撞 flags 无变化） | 121 |
| F5 同 tick vs 延迟 1 tick | **PASS** | `dSame=0.980 dKeep=1.000 dDelayed=1.890 delayExtra=0.910`（延迟 1 tick travel 位移显著大于同 tick） | 128 |
| F2 击退后延迟 travel 位移保留 | **PASS** | `dBase=0.100 dKnock=0.890 keepExtra=0.790`（击退后延迟 travel 位移明显大于无击退基线，击退分量保留） | 133 |

## 佐证日志（同一 corr 上下文，latest.log 122–132 区间）

- `soft_phys_disruption: velocityChange=(-1.000,0.000,0.000) horizontal=1.000 vertical=0.000`（:125, :130）——击退注入在任务内被观测为速度突变
- `soft_phys_revalidate: segment=1/5 from=... to=... result=passable reason=traverse_ok`（:126, :131）——任务 travel 后 revalidate 链正常运行
- 软路径探针日志（:119, :122, :127, :132）——任务路径执行正常

## F 项断言语义说明（按任务书，不预定根因）

- F1 false 记录的是：`bot.hurt(...)` 返回 false 且 health/delta 无变化——该数值由监督员按区分表对「hurt/isPushable 链未触发」候选闭合与否做判定，本报告不判定。
- F3 的 `pushWritten=true` 与 `entityPushed=false` 同时存在：push 直接写入 delta 正常，但实体推挤（aiStep/pushEntities 链）未观察到位移——数值留监督员闭包用。
- F4 PASS 表示 travel 后摩擦衰减发生（travel 链在任务驱动下运行）；tickChain FAIL 表示墙体撞击场景下碰撞 flags 未置位——两者并存的解释由监督员按区分表执行。
- F5/F2 PASS 表示时序对照与击退分量保留在服务端存在（同 tick < 延迟 tick、击退后位移大于基线）。

## 自清理确认

- F1 后 `setHealth(20.0)` 恢复（日志 health=20.000->20.000 即当时已恢复基线）
- F3/F4/F5/F2 后 `teleportTo(anchor)` 归位 + `setDeltaMovement(Vec3.ZERO)` 清 delta（每项后均有）
- Zombie 实体均在断言后 `discard()`；墙体方块断言后恢复 AIR
- 不写档：全程未触碰 `BotWorldData` / `saveToWorld`；不 remove bot

## 限制与未验证项

- 本报告只是服务端证据；**服务端 PASS ≠ 客户端验收**（active plan 阶段 B 客户端矩阵 M2/M3 需 Windows 实测，本包未执行）。
- F1 的伤害源使用了 `damageSources().generic()` 与 Zombie `doHurtTarget` 双源对照（任务书 F1 允许构造测试实体）；未使用真实玩家攻击。
- tickChain 断言通过 `bot.tick()` 手动驱动一个实体 tick 观察碰撞 flags——该驱动是 fixture 内的服务端观察手段，不改变业务 tick 行为。

（根因结论与修复方案不在本报告范围内，由监督员按 active plan 区分表定案后另行计划。）