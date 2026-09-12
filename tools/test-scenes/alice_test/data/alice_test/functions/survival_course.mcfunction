# 维生出口自检场景（S-1 / P1-C）：一键生成，孤立长方体开阔场景
# 目的：bot 处在「可走出的危险」中时，维生行使否决权之后必须给出一次明确出口（SurvivalExitTask）
# 布局：5×5 石头地板（支撑 y=63 ⇒ 脚位 64）
#       中央那一格的**头顶 y=65 压一块石头** ⇒ Entity.isInWall() = SUFFOCATING（每秒掉血）
#       除此以外四周全空 ⇒ 最近安全落点就是相邻格（同层、可走、头顶无遮挡），逃生只需 1 步
# 固定危险格：(66,64,104)；相邻可站格 e.g. (67,64,104)
function alice_test:survival_course_reset

# 地板：x 64..68，z 102..106，支撑 y=63
fill 64 63 102 68 63 106 minecraft:stone

# 压顶：只有中央那一格的头顶（y=65）是石头 —— 身体格 y=64 保持空气，所以 bot 站得进去
setblock 66 65 104 minecraft:stone
