fill -16 63 -16 16 63 16 minecraft:stone
fill -16 64 -16 16 72 16 minecraft:air
kill @e[type=item,x=-16,y=63,z=-16,dx=32,dy=9,dz=32]
tellraw @a [{"text":"[Alice测试场景] ","color":"aqua"},{"text":"基础测试台已重置；支撑面 y=63，脚位 y=64。","color":"gray"}]
