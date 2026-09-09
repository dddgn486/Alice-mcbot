# 对照测试：计分板初始化（load 标签调用；已存在时的报错只进日志）
scoreboard objectives add alice_sw dummy {"text":"对照计时(tick)"}
scoreboard objectives add alice_tr_t dummy {"text":"轨迹 tick"}
scoreboard objectives add alice_tr_x dummy {"text":"轨迹 x(毫格)"}
scoreboard objectives add alice_tr_y dummy {"text":"轨迹 y(毫格)"}
scoreboard objectives add alice_tr_z dummy {"text":"轨迹 z(毫格)"}
scoreboard objectives add alice_tr_vx dummy {"text":"轨迹 vx(毫格/tick)"}
scoreboard objectives add alice_tr_vy dummy {"text":"轨迹 vy(毫格/tick)"}
scoreboard objectives add alice_tr_vz dummy {"text":"轨迹 vz(毫格/tick)"}
scoreboard objectives add alice_tr_og dummy {"text":"轨迹 落地"}
scoreboard objectives add alice_tr_yaw dummy {"text":"轨迹 偏航(百分度)"}
