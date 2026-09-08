# 路径测试课程（一键）：孤立长方体开阔场景（边界外一圈全为空气，无围墙）
# 覆盖 traverse / diagonal / ascend / descend / 2 段链
# 固定起点（必须与 PathingBatteryItem.COURSE_START_FOOT 一致）：脚位 (0,64,46)
function alice_test:pathing_course_reset

# 测试平台（仅平台内部）：x -3..11, z 40..52，支撑层 y=63 → bot 脚位 y=64
fill -3 63 40 11 63 52 minecraft:stone

# 上升台阶（东侧）：目标脚位 (1,65,46)，支撑 (1,64,46)
setblock 1 64 46 minecraft:stone

# 下降坑（北侧）：目标脚位 (0,63,45)，支撑 (0,62,45)
setblock 0 63 45 minecraft:air
setblock 0 62 45 minecraft:stone

# 链式第二级：目标脚位 (0,62,44)，支撑 (0,61,44)
setblock 0 63 44 minecraft:air
setblock 0 62 44 minecraft:air
setblock 0 61 44 minecraft:stone

# 第二级过冲落点（D-024 细化要求）：脚位 (0,62,43)，支撑 (0,61,43)
setblock 0 61 43 minecraft:stone

# 观察台（平台外 2 格，避免与 bot 推挤）：脚位 (13,64,46)
setblock 13 63 46 minecraft:stone
tp @s 13.5 64.0 46.5

give @s alice:pathing_battery
give @s alice:pathing_session
tellraw @s [{"text":"[Alice 路径课程] ","color":"gold"},{"text":"孤立长方体开阔场景已生成，固定起点 (0,64,46)。你已站在观察台：","color":"white"},{"text":"寻路自检电池","color":"yellow"},{"text":"右键跑全部单步检查；","color":"white"},{"text":"路径会话测试器","color":"yellow"},{"text":"右键跑 规划→逐段执行。","color":"white"}]
