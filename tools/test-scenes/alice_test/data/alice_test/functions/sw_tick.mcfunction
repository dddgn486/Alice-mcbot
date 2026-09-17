# 对照计时器：每 tick 一次
# ① 待发中的玩家**一离开起点 marker**（>0.05 格）就正式开始 ⇒ 排除手动输入 #goto 的打字延迟
# ⚠️ 必须 `at @s`：`as` 只换执行者、**不换执行位置** ⇒ 少了它，`distance` 会相对命令源(0,0,0)算
# （2026-09-17 实测踩到：少了 `at @s` ⇒ 条件恒真 ⇒ 一调用就正式计时，等于没修）
execute as @a[tag=alice_sw_pending] at @s unless entity @e[type=minecraft:marker,tag=sw_origin,distance=..0.05] run function alice_test:sw_begin
# ② 计时中：累加，并只在**目标** marker 处停表（起点 marker 不再误触发）
execute as @a[tag=alice_sw_running] run scoreboard players add @s alice_sw 1
execute as @a[tag=alice_sw_running] at @s if entity @e[type=minecraft:marker,tag=sw_goal,distance=..0.8] run function alice_test:sw_stop
