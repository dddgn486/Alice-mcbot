# place_course：一键场景（地形由 place_course_terrain 提供；串联回归直接调用它）
function alice_test:place_course_terrain
tp @s -1.5 64.0 72.5
give @s alice:pathing_placer
give @s alice:pathing_disturber
give @s alice:pathing_waller
tellraw @s [{"text":"[Alice 放置台阶场景] ","color":"gold"},{"text":"固定起点 (0,64,66) → 目标 (8,62,66)。中间 x=2..3 是 2 格宽缺口，x=7 处地面低 2 格。","color":"white"}]
tellraw @s [{"text":"寻路放置器","color":"aqua"},{"text":"右键：正常放置通行；","color":"white"},{"text":"自愈扰动器","color":"aqua"},{"text":"右键：中途被平移 1 格，验证自动恢复。","color":"white"}]
