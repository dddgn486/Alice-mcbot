function alice_test:movement_d_reset
fill -1 63 18 11 63 22 minecraft:stone
fill -1 64 18 11 65 18 minecraft:stone
fill -1 64 22 11 65 22 minecraft:stone
fill -1 64 18 -1 65 22 minecraft:stone
fill 11 64 18 11 65 22 minecraft:stone
setblock 10 63 20 minecraft:deepslate
setblock 5 64 20 minecraft:air
setblock 5 65 20 minecraft:air
give @s alice:movement_dynamic_block_tester
tp @s 0.5 64.0 20.5
tellraw @s [{"text":"[Alice Movement场景D] ","color":"gold"},{"text":"封闭动态阻挡走廊已生成。普通右键启动 Movement，Bot 行走中途会在 x=5 放置横向石墙；Shift+右键清理。","color":"white"}]
