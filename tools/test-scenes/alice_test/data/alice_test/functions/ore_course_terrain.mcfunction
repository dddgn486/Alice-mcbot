# 挖掘 Job（切片 J5）验证场景：孤立长方体区域 + 裸露矿脉
# 区域：x 46..66, y 58..76, z 122..144（边界外一圈为空气，与其它场景不相连）
fill 46 58 122 66 76 144 minecraft:air
# 岩体：顶面 y=62（bot 脚位 63），既是站位也是"矿脉所在岩层"
fill 48 59 124 64 62 142 minecraft:stone
# 裸露铁矿 6 处（嵌在顶面层 y=62，彼此分散）——都能从上方直接看到，走模式 A
setblock 52 62 128 minecraft:iron_ore
setblock 56 62 128 minecraft:iron_ore
setblock 60 62 128 minecraft:iron_ore
setblock 52 62 136 minecraft:iron_ore
setblock 56 62 136 minecraft:iron_ore
setblock 60 62 136 minecraft:iron_ore
