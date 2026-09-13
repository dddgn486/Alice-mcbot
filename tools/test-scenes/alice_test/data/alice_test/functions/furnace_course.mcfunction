# 阶段 3-A / A4 场景：**孤立平台 + 一个原版熔炉**（"按时间工作"的机器）
# 区域：x 40..52, y 60..72, z 300..312（与合成/装配场景共用同一块地，各自先清空）
# 熔炉放在起点 (46,64,304) 西侧 2 格 (46,64,306)；**先成空气再放**（避免 setblock 同种方块短路留下的旧方块实体）
fill 39 59 299 53 73 313 minecraft:air
fill 40 59 300 52 72 312 minecraft:air
fill 40 63 300 52 63 312 minecraft:stone
setblock 46 64 306 minecraft:air
setblock 46 64 306 minecraft:furnace
kill @e[type=minecraft:item,x=40,y=60,z=300,dx=13,dy=13,dz=13]
