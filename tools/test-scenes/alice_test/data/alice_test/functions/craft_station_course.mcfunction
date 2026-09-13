# 阶段 3-A / A3b 场景：**孤立长方体平台，故意不放工作台**（"自己放工作站"这条路的前提）
# 区域：x 40..52, y 60..72, z 300..312（与 A3 的 craft_table_course 共用同一块地：
#   A3 之后紧接着跑 A3b 时，本函数先把台子清掉 ⇒ 前提"周围没有工作站"必然成立）
# 为什么单独一个场景而不是复用 A3 的：A3 场景自带工作台，而 A3b 要测的正是"**没有**时自己放"。
fill 39 59 299 53 73 313 minecraft:air
fill 40 59 300 52 72 312 minecraft:air
fill 40 63 300 52 63 312 minecraft:stone
# 掉落物实体一并清掉（孤立场景前提，理由见 mine_course_terrain）
kill @e[type=minecraft:item,x=40,y=60,z=300,dx=13,dy=13,dz=13]
