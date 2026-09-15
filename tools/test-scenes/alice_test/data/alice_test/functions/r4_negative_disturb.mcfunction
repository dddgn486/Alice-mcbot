# r4_negative_disturb：**夹具注定放不下**的负例场景（`survey/08` §9 #4）
#
# 目的：把"夹具未生效 ⇒ `FIXTURE_NOT_FIRED` + 任务 FAILED"这条**负例分支**跑出来。
# 背景：两件 R4 物品的起终点是硬编码的同一场景（`place_course`），在那里夹具**必然放得下**
# ⇒ 负例分支从没被执行过（台账 §5.6 / `survey/08` §9#4 都登记为"未跑"）。
#
# 做法（**确定性**，不依赖时序运气）：
#   `PathSessionDiagnosticTask` 的扰动是**单格判定** —— `to = foot + (dx, 0, dz)`，没有候选搜索；
#   `dz = +1` ⇒ `to` 落在 **z=67** 那一列。把该列脚位层（y=64）填成石头，
#   `canWalkThrough(to)` 即为假 ⇒ 40 tick 宽限内**没有合法落点** ⇒ `disturb_not_applicable`
#   ⇒ 任务带 `/FIXTURE_NOT_FIRED=[disturb]` **如实 FAILED**。
#
# ⚠️ 不影响 bot 自己的通路：`place_course` 的走廊全程在 **z=66**（实测分段日志
#    `4,64,66 → 6,64,66 → 7,62,66 → 8,62,66` 全是 z=66）⇒ 堵 z=67 不会改变它要走的路。
#
# 预期（用户右键 `alice:pathing_disturber` 一次后）：
#   `[R4 Fixture] disturb_not_applicable … tick=70`（30 + 40 宽限）
#   `[R4 Session] result … FAILED … /FIXTURE_NOT_FIRED=[disturb]`
#   ⇒ **看到 FAILED 才是对的**：它正是"这趟没测到扰动"的如实回报（旧行为会静默当成功）。
# 先 reset 再建地形：本函数可**重复执行**，且跑完想恢复原场景只需 place_course_reset
function alice_test:place_course_reset
function alice_test:place_course_terrain
fill 0 64 67 9 64 67 minecraft:stone
tp @s -1.5 64.0 72.5
give @s alice:pathing_disturber
give @s alice:pathing_waller
give @s alice:pathing_placer
tellraw @s [{"text":"[Alice R4 负例场景] ","color":"gold"},{"text":"z=67 一侧已被封死 ⇒ 扰动夹具**无合法落点**。","color":"white"}]
tellraw @s [{"text":"右键「自愈扰动器」：","color":"aqua"},{"text":"预期日志出现 disturb_not_applicable + FIXTURE_NOT_FIRED + 任务 FAILED —— 那是**如实回报**，不是 bug。","color":"white"}]
tellraw @s [{"text":"右键「寻路放置器 / 封路器」：","color":"aqua"},{"text":"它们仍然正常（本场景只封了扰动的落点）。","color":"white"}]
tellraw @s [{"text":"恢复：","color":"aqua"},{"text":"跑 /function alice_test:place_course_reset 即可清掉这块石头。","color":"white"}]
