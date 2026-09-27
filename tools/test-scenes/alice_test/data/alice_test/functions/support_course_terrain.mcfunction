# 真悬空目标（**放支撑块 + 用完即拆**）验证场景：孤立长方体区域
# 区域：x 17..31, y 44..76, z 205..223（外扩一圈空气保证隔离）
#
# ⭐ 为什么单开一个场景（`D-464` 的 O1/O2 修复，2026-09-27）：
# `floating_course` 的竖井是 **1 格深**（`D-364` 收紧后的口径：掉落物落在坑底、捡得回来 ⇒ **不垫**），
# 而 `MiningPlanner.dropWouldBeLost`（`DROP_FALL_SEARCH = 8`）只在**下方 8 格内没有可落面**（或岩浆）时
# 才判"掉落物会丢" ⇒ 必须"垫"。⇒ 两个场景各锁一种语义，**谁也不许改对方**：
#   `floating_course` = 1 格深 ⇒ 断言"**不垫**"；`support_course` = 深坑 ⇒ 断言"**垫 + 用完即拆**"。
# ⚠️ 顺带修掉一个空判据：`MineRegressionTask` 的 `supportOk`/`restoredOk` 原先写成
# `!expectSupport() || …`，而**13 条用例的 expectSupport 全是 false** ⇒ 两条断言恒真（假绿）。
# 本场景第一次让 `expectSupport = true` 有了正例。
fill 17 44 205 31 76 223 minecraft:air
fill 18 45 206 30 75 222 minecraft:air
fill 18 51 206 30 51 222 minecraft:stone
# 场景内掉落物实体一并清掉（孤立场景前提）
kill @e[type=minecraft:item,x=18,y=45,z=206,dx=13,dy=31,dz=17]

# 地面平台：y=63（脚位 64），x 20..26，z 208..218
fill 20 63 208 26 63 218 minecraft:stone

# 目标正下方挖成 **深坑**：y=52..63 全空（≥8 格）⇒ `dropWouldBeLost` 判 true
# （y=51 是地板 ⇒ 掉落物若掉到底，bot 站在 y=63 的平台上够不着 ⇒ 必须垫）
setblock 23 63 212 minecraft:air

# 放置点 (23,64,212) 的水平支撑面：东侧实心块（`placeAt` 只扫水平+下的支撑面）
setblock 24 64 212 minecraft:stone

# 悬空目标 (23,65,212)：正下方 8+ 格空气 ⇒ 必须先放支撑块再挖（掉落物落在支撑块上，捡得回）
setblock 23 65 212 minecraft:stone

# 观察台（场景内、路径外）
fill 20 63 206 21 63 207 minecraft:stone
