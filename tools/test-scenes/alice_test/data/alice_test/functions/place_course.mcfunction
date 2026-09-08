# R5-3 放置台阶通行场景（一键）：孤立长方体开阔场景
# 目的：验证 PlaceStepAndTraverse —— 缺支撑时放置方块（TEMPORARY_SUPPORT）再走上去
# 固定起点 (0,64,66)，目标 (8,62,66)
# 路径：平台A → 2 格宽缺口(x=2..3) → 平台B → 下 2 格到目标
function alice_test:place_course_reset

# 平台 A：支撑 y=63（脚位 64），x -3..1
fill -3 63 62 1 63 70 minecraft:stone

# 缺口：x=2..3 无支撑（保持空气，下方到 y=59 也是空气）

# 平台 B：支撑 y=63（脚位 64），x 4..6
fill 4 63 62 6 63 70 minecraft:stone

# 低位平台：支撑 y=61（脚位 62），x 7..9 → 从脚位 64 到 62 是 2 格落差
fill 7 61 62 9 61 70 minecraft:stone

# 玩家观察点（缺口外侧）
tp @s -1.5 64.0 72.5
give @s alice:pathing_placer
give @s alice:pathing_disturber
tellraw @s [{"text":"[Alice 放置台阶场景] ","color":"gold"},{"text":"固定起点 (0,64,66) → 目标 (8,62,66)。中间 x=2..3 是 2 格宽缺口，x=7 处地面低 2 格。","color":"white"}]
tellraw @s [{"text":"寻路放置器","color":"aqua"},{"text":"右键：正常放置通行；","color":"white"},{"text":"自愈扰动器","color":"aqua"},{"text":"右键：中途被平移 1 格，验证自动恢复。","color":"white"}]
