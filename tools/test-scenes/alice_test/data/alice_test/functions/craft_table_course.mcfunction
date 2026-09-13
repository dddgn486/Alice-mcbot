# 阶段 3-A / A3 场景：**孤立长方体平台 + 一个现成工作台**（零写入路径）
# 区域：x 40..52, y 60..72, z 300..312（与转移场景 z392..420、伐木 z204..222 等均不重叠）
fill 39 59 299 53 73 313 minecraft:air
fill 40 59 300 52 72 312 minecraft:air
fill 40 63 300 52 63 312 minecraft:stone
# 现成工作台（bot 起点西侧 2 格，站过去即可用）
setblock 46 64 306 minecraft:crafting_table
# 掉落物实体一并清掉（孤立场景前提，理由见 mine_course_terrain）
kill @e[type=minecraft:item,x=40,y=60,z=300,dx=13,dy=13,dz=13]
