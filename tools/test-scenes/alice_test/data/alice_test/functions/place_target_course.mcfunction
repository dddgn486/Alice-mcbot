# PlaceTask 迁移验证：一键场景（沿用 walk_course 地形）
function alice_test:walk_course_terrain
tp @s 21.5 64.0 85.5
give @s alice:target_selector
tellraw @s [{"text":"[Alice 放置任务场景] ","color":"gold"},{"text":"Shift+右键任意方块的侧面：bot 会走到旁边并在相邻空气格放置一个方块（圆石由夹具提供）。","color":"white"}]
tellraw @s [{"text":"期望日志","color":"aqua"},{"text":"[PlaceTask] walk_to_stand → [PlaceTask] completed target=.. stand=.. feet=..","color":"white"}]
