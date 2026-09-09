# 连续行动轨迹记录：每 tick 采样一次（位置/速度单位=毫格，偏航=百分度）
execute as @a[tag=alice_tr_running] run scoreboard players add @s alice_tr_t 1
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_x run data get entity @s Pos[0] 1000
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_y run data get entity @s Pos[1] 1000
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_z run data get entity @s Pos[2] 1000
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_vx run data get entity @s Motion[0] 1000
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_vy run data get entity @s Motion[1] 1000
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_vz run data get entity @s Motion[2] 1000
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_og run data get entity @s OnGround
execute as @a[tag=alice_tr_running] run execute store result score @s alice_tr_yaw run data get entity @s Rotation[0] 100
execute as @a[tag=alice_tr_running] run tellraw @a [{"selector":"@s"},{"text":" t="},{"score":{"name":"@s","objective":"alice_tr_t"}},{"text":" x="},{"score":{"name":"@s","objective":"alice_tr_x"}},{"text":" y="},{"score":{"name":"@s","objective":"alice_tr_y"}},{"text":" z="},{"score":{"name":"@s","objective":"alice_tr_z"}},{"text":" vx="},{"score":{"name":"@s","objective":"alice_tr_vx"}},{"text":" vy="},{"score":{"name":"@s","objective":"alice_tr_vy"}},{"text":" vz="},{"score":{"name":"@s","objective":"alice_tr_vz"}},{"text":" og="},{"score":{"name":"@s","objective":"alice_tr_og"}},{"text":" yaw="},{"score":{"name":"@s","objective":"alice_tr_yaw"}}]
execute as @a[tag=alice_tr_running] at @s if entity @e[type=minecraft:marker,distance=..1.5] run function alice_test:trace_stop
