function alice_test:reset
setblock 4 64 4 minecraft:deepslate
give @s alice:mining_scene_tester
tp @s 0.5 64 0.5
tellraw @s [{"text":"[Alice场景A] ","color":"green"},{"text":"基线已生成并发放挖掘场景启动器。手持启动器普通右键任意方块即可自动生成/摆位 Bot、标记目标并启动；Shift+右键只清除会话和高亮。","color":"white"}]
