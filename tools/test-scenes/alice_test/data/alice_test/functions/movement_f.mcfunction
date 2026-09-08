function alice_test:movement_f_reset
fill -1 63 58 11 63 62 minecraft:stone
fill -1 64 58 11 65 58 minecraft:stone
fill -1 64 62 11 65 62 minecraft:stone
fill -1 64 58 -1 65 62 minecraft:stone
fill 11 64 58 11 65 62 minecraft:stone
give @s alice:movement_precondition_tester
tp @s 0.5 64.0 60.5
tellraw @s [{"text":"[Alice Movement场景F] ","color":"gold"},{"text":"启动前置条件场景已生成。普通右键会在 Bot 头部放置测试方块并立即检查；Shift+右键清理。","color":"white"}]
