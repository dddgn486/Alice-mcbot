function alice_test:reset
fill -4 64 -1 4 66 -1 minecraft:stone
fill -4 64 4 4 66 4 minecraft:stone
fill -4 64 -1 -4 66 4 minecraft:stone
fill 4 64 -1 4 66 4 minecraft:stone
fill -3 64 1 3 65 1 minecraft:stone
setblock 0 64 3 minecraft:deepslate
give @s alice:mining_scene_b_tester
tp @s -3.5 64.0 0.5
tellraw @s [{"text":"[Alice场景B] ","color":"gold"},{"text":"封闭有限清障场景已生成并发放 B 启动器。启动器会自动生成/摆位 Bot 到 (-3,64,0)，目标为 (0,64,3)。墙体两端和前后边界已封闭，Bot 不能绕墙；预期只清掉一格遮挡后跨过。普通右键启动，Shift+右键只清除会话和高亮。","color":"white"}]
