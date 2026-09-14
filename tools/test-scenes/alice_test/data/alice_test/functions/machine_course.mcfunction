# 阶段 3-B / S2+S3 场景：**孤立平台 + 两台模组机器**（Mekanism 富集仓 + 粉碎机；只读发现用，不需要供电）
# S3（D-209）加第二台的意义：证明"按表认机器"是数据驱动的——加机器 = 表已有行 + 这里多一个 setblock，**不改 Java**。
# 区域：x 60..72, y 60..72, z 300..312（与其它场景分开；边界外一圈空气）
# 机器：enrichment_chamber @(66,64,306)（起点东侧 2 格）、crusher @(66,64,307)；
# 两格间距 ⇒ 都在原版交互距离 4.5 内（实测眼到方块中心 2.29 / 3.20 格）；**先成空气再放**（避免方块实体状态跨场景存活）
fill 59 59 299 73 73 313 minecraft:air
fill 60 59 300 72 72 312 minecraft:air
fill 60 63 300 72 63 312 minecraft:stone
setblock 66 64 306 minecraft:air
setblock 66 64 306 mekanism:enrichment_chamber
setblock 66 64 307 minecraft:air
setblock 66 64 307 mekanism:crusher
kill @e[type=minecraft:item,x=60,y=60,z=300,dx=13,dy=13,dz=13]
