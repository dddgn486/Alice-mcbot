# legacy 上升兼容测试场景（一键）：孤立长方体开阔场景
# 目的：验证 maxUpStep=0.6 后 legacy 路径（/alice follow、PathExecutor）能爬 1 格台阶，且不会爬 2 格墙
# 几何：低位平台(脚位 64) → 1 格台阶(脚位 65) → 2 格高墙(不可爬)
function alice_test:legacy_ascend_reset

# 低位平台：支撑 y=63（脚位 64），x -3..3
fill -3 63 62 3 63 70 minecraft:stone

# 1 格台阶：支撑 y=64（脚位 65），x 4..10
fill 4 64 62 10 64 70 minecraft:stone

# 2 格高墙：x=11，必须无法攀爬（对照组）
fill 11 64 62 11 65 70 minecraft:stone

# 玩家就位在低位平台
tp @s 0.5 64.0 66.5
tellraw @s [{"text":"[Alice legacy 上升场景] ","color":"gold"},{"text":"低位平台(你脚下) → 东侧 1 格台阶 → 再东 2 格高墙。","color":"white"}]
tellraw @s [{"text":"测试步骤：输入 ","color":"yellow"},{"text":"/alice follow on","color":"aqua"},{"text":" 然后向东走上 1 格台阶，观察 bot 是否跟随并跳跃上台阶；再试走向 2 格高墙，bot 不应爬上去。","color":"white"}]
