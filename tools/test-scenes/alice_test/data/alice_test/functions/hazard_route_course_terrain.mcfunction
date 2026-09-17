# D-292 判据场景：**只有一条短路且紧贴熔岩**，绕行代价远小于"贴危险"的加价
# 区域：x 60..76, y 60..72, z 238..268（与伐木 z204..222、工作台 z300+、转移 z392+ 不重叠）
fill 59 59 237 77 73 269 minecraft:air
# 直行道：**一格外宽**（x=64, z 242..262）+ 起点/终点平台
fill 64 63 242 64 63 262 minecraft:stone
fill 63 63 241 65 63 241 minecraft:stone
fill 63 63 263 65 63 263 minecraft:stone
# 两侧墙（x=63 / x=65）⇒ 直行道**无法侧移**避开
fill 63 64 242 63 67 262 minecraft:stone
fill 65 64 242 65 67 262 minecraft:stone
# 右侧墙开两个缺口（z=246 / z=258）通向绕行道
fill 65 64 246 65 67 246 minecraft:air
fill 65 64 258 65 67 258 minecraft:air
# **熔岩**：紧贴直行道右侧（x=65，z 250..252，与脚位同层 ⇒ 邻接判定命中）
fill 65 64 250 65 64 252 minecraft:lava
# 唯一绕行道：z=246 出去 → x=68 直走 → z=258 回来（多约 10 格 ≪ 3×20 的加价 ✓）
fill 65 63 246 68 63 246 minecraft:stone
fill 68 63 246 68 63 258 minecraft:stone
fill 65 63 258 68 63 258 minecraft:stone
fill 66 64 246 66 67 246 minecraft:air
fill 66 64 258 66 67 258 minecraft:air
# 外框（防止规划器绕到场景外）
fill 63 68 240 69 68 264 minecraft:stone
fill 69 64 244 69 67 260 minecraft:stone
fill 60 64 240 76 67 240 minecraft:stone
fill 60 64 264 76 67 264 minecraft:stone
kill @e[type=minecraft:item,x=59,y=59,z=237,dx=19,dy=15,dz=33]
