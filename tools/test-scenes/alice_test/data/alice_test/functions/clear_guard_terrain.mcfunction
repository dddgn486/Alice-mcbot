# D-095 断言场景：目标在墙后，唯一通道被**箱子**堵住 —— bot 不得为取目标而拆箱子
# 孤立区域：x 40..62, y 58..78, z 150..170（边界外一圈为空气）
#
# 2026-09-11 修正（首测暴露的场景缺陷）：原来箱子放在缺口**最下面**（与脚位同高），
# 于是 bot 把箱子当成**可踩的台阶**，踩着它试图翻墙 —— 墙比箱子高 2 格，必然失败，
# 空耗 161 tick 才放弃（用户观察："在箱子上跳了半天"）。
# 现在把缺口抬到**身子/头的高度（2 格高）**，下面补石头：箱子既不能踩、也不能当台阶，
# bot 只能"绕路（挖旁边的墙——合法）或如实失败"，不会空耗。
fill 40 58 150 62 78 170 minecraft:air
fill 40 63 150 62 63 170 minecraft:stone
# 一道横墙（x=50，y64..67，够高防跳越）
fill 50 64 150 50 67 157 minecraft:stone
fill 50 64 159 50 67 170 minecraft:stone
# z=158 的缺口只开在 y=65..66（身子/头），上下都是石头 → 踩不到
fill 50 64 158 50 64 158 minecraft:stone
fill 50 67 158 50 67 158 minecraft:stone
setblock 50 65 158 minecraft:chest
setblock 50 66 158 minecraft:chest
# 墙另一侧的目标（石头）
setblock 56 64 158 minecraft:stone
setblock 56 64 157 minecraft:stone
setblock 56 64 159 minecraft:stone
