# 模组兼容（Ore Excavation）验证：一键场景
function alice_test:chain_course_terrain
tp @s 20.5 64.0 148.5
give @s alice:chain_test_runner
tellraw @s [{"text":"[Alice 模组兼容场景] ","color":"gold"},{"text":"平台上 3x3 铁矿石脉（种子=北面中心格）。","color":"white"}]
tellraw @s [{"text":"模组连锁兼容检查器","color":"aqua"},{"text":"右键：bot 反射调用 Ore Excavation 服务端入口触发连锁，验证掉落物捕获与收集。","color":"white"}]
