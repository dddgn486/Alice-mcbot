# 阶段 3-B / S2 场景：**孤立平台 + 一台模组机器**（Mekanism 富集仓；只读发现用，不需要供电）
# 区域：x 60..72, y 60..72, z 300..312（与其它场景分开；边界外一圈空气）
# 机器放在起点 (66,64,304) 东侧 2 格 (66,64,306)；**先成空气再放**（避免方块实体状态跨场景存活）
fill 59 59 299 73 73 313 minecraft:air
fill 60 59 300 72 72 312 minecraft:air
fill 60 63 300 72 63 312 minecraft:stone
setblock 66 64 306 minecraft:air
setblock 66 64 306 mekanism:enrichment_chamber
kill @e[type=minecraft:item,x=60,y=60,z=300,dx=13,dy=13,dz=13]
