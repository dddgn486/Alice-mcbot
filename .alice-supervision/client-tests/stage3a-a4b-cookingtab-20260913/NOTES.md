# 阶段 3-A / A4b（菜单型炉子 = 精妙"熔炼升级页签"）第二轮实测

- 日期：2026-09-13（19:39:55–19:40:20）
- 客户端：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`
- 被测 jar：`dcb45b76bf13994f0f00a32c1ea444d7c2993cf9c3ec2eca53aead299642e48f`（A4b 判据修复）
- 入口：`alice:craft_cooking_check`（零参数）；场景 `alice_test:craft_tab_course`（精妙容器 @46,64,306）
- 证据：`evidence/latest-log-excerpt.txt`、`evidence/smelt-tab-2026-09-13_18.57.42.png`（页签形态）

## 结论 1：A4b 能力 = 已验证（WINDOWS_CLIENT，世界事实）

```
[Furnace] 认出炉子 by=ownerDeclaration(getCookingSlots) assignBy=mayPlaceProbe
          input=#64 fuel=#65 output=#66 container=SimpleContainer data=CookingLogicContainer(selfReported) litTime=1600
[CraftFurnaceCheck] provision_verified=true …
[CraftFurnaceCheck] input_and_fuel_placed=true input=true fuel=true
[CraftFurnaceCheck] smelted=true product+1
[CraftFurnaceCheck] input_consumed=true input-1
[CraftFurnaceCheck] no_half_products=true inputLeft=0 outputLeft=0
```

⇒ 装升级 → 认 3 格（自述 `getCookingSlots()` + `mayPlace` 行为分格）→ 放沙子+煤 → 真烧（~10.5 s）→ 取走玻璃 →
炉内不留东西。**发现器与菜单点击（地址 64/65/66，未登记在 `menu.slots`）都被证明可用。**

## 结论 2：新缺陷（夹具控制流，非能力）= 已修

现象（用户报"一直开关箱子开关个不停"）：`[Menu] closed reason=furnace_cleanup type=-` 与
`[Menu] use_item_on … result=CONSUME` **每 ~50 ms 一对**，SUMMARY 从未打印。

根因：`cleanupFurnace()` 是每 tick 调用的相位处理函数；A4b 分支 `closeSession(...)` 后交给需要多 tick 的
`deprovisionAndFinish()`，但**相位仍停在 CLEANUP** ⇒ 下一 tick 又关掉刚开的菜单 ⇒ 活锁。

修（`CraftFurnaceCheckTask`，jar `502ec5f2…`）：独立 `DEPROVISION` 相位；CLEANUP 只走一次；cleanup 菜单守卫
（不再对玩家背包菜单跑发现器 / 拿旧地址点击）；`waitSmelt` 的发现器降到每 40 tick（原先 383 行噪声）。

## 未验证 / 下一轮

- 修复后的 jars：`alice:craft_cooking_check` 应打出 SUMMARY（期望 `verdict=PASS`，~11 s），`alice:regression_battery` CORE 应 24/24。
- 视觉结论（是否真的"只开关一次箱子"）需用户确认。

## 第三轮（jar `502ec5f2…`）= 全绿（2026-09-13 19:49 + 19:51）

- `alice:craft_cooking_check` **PASS**：`smelt_ticks=207`、`product_delta=1`、`cobblestone_delta=-1`、
  `input_left=0 fuel_left=0 output_left=0`、`leftovers_returned` 三项全 true、`deprovision_moved=true`、
  `upgrade_returned=true`、`no_block_writes=true`（19:49:00→19:49:11）。
- CORE 电池 **`(24/24) ticks=2809 → PASS`**（`craft_cooking=PASS`、`craft_furnace=PASS`）。
- 死循环消失：`reason=furnace_cleanup` **3 次**（两个 A4b + 一个 A4，各一次）、`ReportedException` **0 次**、
  `[Furnace] 认出炉子` 由 **383 → 23** 行。
- 证据文件：`evidence/round3-pass.txt`。
- **A4b 收口**；阶段 3-A 仅剩 A5 决策层接线。
