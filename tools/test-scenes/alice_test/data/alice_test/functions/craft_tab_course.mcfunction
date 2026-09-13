# 阶段 3-A / S1-3 场景：**孤立平台 + 一个精妙存储容器**（"合成升级页签"这一站的现场）
# 区域：x 40..52, y 60..72, z 300..312（与 A3/A3b 场景共用同一块地，各自先清空 ⇒ 互不干扰）
# 探针起点固定 (46,64,304)；容器放在起点西侧 2 格 (46,64,306)，在触及距离内 ⇒ 探针无需走路
fill 39 59 299 53 73 313 minecraft:air
fill 40 59 300 52 72 312 minecraft:air
fill 40 63 300 52 63 312 minecraft:stone
# ↓ 模组方块：模组没装时这一行会报错（前面的平台已经建好，便于分辨"是模组缺失"还是"场景坏了"）
setblock 46 64 306 sophisticatedstorage:chest
# ↓ 给玩家一颗合成升级，便于**手动**装进容器升级槽（自动装配 = 下一步的 L2 层）
give @p sophisticatedstorage:crafting_upgrade 1
# 掉落物实体一并清掉（孤立场景前提）
kill @e[type=minecraft:item,x=40,y=60,z=300,dx=13,dy=13,dz=13]
