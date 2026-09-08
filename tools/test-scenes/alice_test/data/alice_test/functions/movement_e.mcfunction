function alice_test:movement_e_reset
fill -1 63 38 11 63 42 minecraft:stone
fill -1 64 38 11 65 38 minecraft:stone
fill -1 64 42 11 65 42 minecraft:stone
fill -1 64 38 -1 65 42 minecraft:stone
fill 11 64 38 11 65 42 minecraft:stone
setblock 10 63 40 minecraft:deepslate
setblock 5 64 40 minecraft:air
setblock 5 65 40 minecraft:air
give @s alice:movement_block_reason_tester
tp @s 0.5 64.0 40.5
tellraw @s [{"text":"[Alice Movement场景E] ","color":"gold"},{"text":"动态阻挡分类场景已生成。普通右键启动，执行中会在 x=5 放置封闭屏障；Shift+右键清理。","color":"white"}]
