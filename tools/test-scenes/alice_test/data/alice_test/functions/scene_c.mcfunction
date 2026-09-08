function alice_test:reset
fill -1 64 -2 11 65 -2 minecraft:stone
fill -1 64 2 11 65 2 minecraft:stone
fill -1 64 -2 -1 65 2 minecraft:stone
fill 11 64 -2 11 65 2 minecraft:stone
setblock 10 64 0 minecraft:deepslate
give @s alice:mining_scene_c_tester
tp @s 0.5 64.0 0.5
tellraw @s [{"text":"[Alice场景C] ","color":"gold"},{"text":"长直线路径动态障碍场景已生成并发放 C 启动器。普通右键启动器会自动标记起点、目标和中央障碍 (5,64,0)，再自动生成/复用 Bot 启动测试；Shift+右键清除。","color":"white"}]
