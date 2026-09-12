# 传输模块自检场景（L2 菜单路线）：**孤立长方体平台 + 两个箱子**
# 区域：x 40..60, y 60..70, z 392..420
#   · 与其它场景不重叠：伐木 z204..222 / 垂直上升 z38..54 / 脚位格 z300 / 石台 z100
#   · 区域整体先填空气（含上下各留空气层）⇒ 平台悬空、与周围地形不相连
fill 40 60 392 60 70 420 minecraft:air
# 平台地板：y=63（脚位 y=64）
fill 40 63 392 60 63 420 minecraft:stone
# 观察台（玩家侧，位于平台南端，不参与任务；与任务区之间留出空间）
fill 52 63 416 58 63 420 minecraft:stone
# 源箱（TransferCourseAnchor.SOURCE = 46,64,404）与目标箱（DESTINATION = 46,64,407）
# 两个箱子都由夹具填内容；箱子放在平台上，四周留空 ⇒ 站位候选充足
setblock 46 64 404 minecraft:chest
setblock 46 64 407 minecraft:chest
