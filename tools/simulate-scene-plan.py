#!/usr/bin/env python3
"""场景设计辅助：在本地用简化模型推演"规划器会选哪条路线"。

⚠️ 这不是规划器真相，只是**场景设计工具**：它复刻 Alice 的 Movement 集合
（TRAVERSE / DIAGONAL / ASCEND / DESCEND + D-024 过冲检查）与成本模型，
用于在让用户跑客户端之前，先确认"这个验收场景能不能判别出路线差异"。

已知校验：用旧版 1x1 坑场景推演得到 `cost=2.83 first=DIAGONAL`，
与客户端 `latest.log` 的 `[DipRoute] status=REACHED first=DIAGONAL movements=2 cost=2.83` 完全一致。

用法：python3 tools/simulate-scene-plan.py
"""

import heapq
import math

# 实测段耗时（tick）：R3 电池证据 2026-09-08
MEASURED_TICKS = {'TRAVERSE': 6, 'DIAGONAL': 8, 'ASCEND': 10, 'DESCEND': 16}
# 成本模型：旧 = 当前 CostModel；新 = Q7 按实测标定
COST_OLD = (1.0, math.sqrt(2), 2.0, 1.0)          # traverse / diagonal / ascend / descend
COST_NEW = (1.0, 1.33, 1.67, 2.67)

CARD = [(1, 0), (-1, 0), (0, 1), (0, -1)]
DIAG = [(1, 1), (1, -1), (-1, 1), (-1, -1)]


def build_feet(floor_x=range(-3, 4), floor_z=range(60, 71), floor_y=63,
               pit=(), pit_depth=1):
    """返回可站脚位集合。pit 为 (x,z) 集合，坑内支撑下沉 pit_depth。"""
    feet = set()
    for x in floor_x:
        for z in floor_z:
            if (x, z) in pit:
                feet.add((x, floor_y - pit_depth + 1, z))
            else:
                feet.add((x, floor_y + 1, z))
    return feet


def walkable(feet, pos):
    if pos in feet:
        return True
    x, y, z = pos
    return not any(f[0] == x and f[2] == z and f[1] - 1 == y for f in feet)


def moves(feet, pos, costs):
    c_t, c_d, c_a, c_s = costs
    x, y, z = pos
    out = []
    for dx, dz in CARD:                                   # 同层四向
        to = (x + dx, y, z + dz)
        if to in feet and walkable(feet, to) and walkable(feet, (x + dx, y + 1, z + dz)):
            out.append(('TRAVERSE', to, c_t))
    for dx, dz in DIAG:                                   # 同层对角（两侧必须可通行）
        to = (x + dx, y, z + dz)
        if (to in feet and walkable(feet, (x + dx, y, z)) and walkable(feet, (x, y, z + dz))
                and walkable(feet, to) and walkable(feet, (x + dx, y + 1, z + dz))):
            out.append(('DIAGONAL', to, c_d))
    for dx, dz in CARD:                                   # 上升（基数为轴，dy=+1）
        to = (x + dx, y + 1, z + dz)
        if (to in feet and walkable(feet, to) and walkable(feet, (x + dx, y + 2, z + dz))
                and walkable(feet, (x + dx, y + 1, z)) and walkable(feet, (x, y + 2, z))):
            out.append(('ASCEND', to, c_a))
    for dx, dz in CARD:                                   # 下降（基数为轴，dy=-1）+ D-024 过冲检查
        to = (x + dx, y - 1, z + dz)
        if not (to in feet and walkable(feet, to) and walkable(feet, (x + dx, y, z + dz))):
            continue
        beyond = (to[0] + dx, to[1], to[2] + dz)
        if walkable(feet, beyond):
            if not (beyond in feet or (beyond[0], beyond[1] - 1, beyond[2]) in feet):
                continue
        out.append(('DESCEND', to, c_s))
    return out


def astar(feet, start, goal, costs):
    """与 Alice A* 同形：h = octile 水平 + 非对称竖向（扣除垂直移动覆盖的水平进度，见 D-040）。"""
    def h(p):
        dx, dz = abs(p[0] - goal[0]), abs(p[2] - goal[2])
        dy = goal[1] - p[1]
        straight = max(dx, dz) - min(dx, dz)
        val = straight * 1.0 + min(dx, dz) * 1.33
        if dy > 0:
            val += dy * (1.67 - 1.0)
        elif dy < 0:
            val += -dy * (2.67 - 1.0)
        return val

    openq = [(h(start), 0.0, start, [])]
    seen = set()
    while openq:
        _, g, pos, path = heapq.heappop(openq)
        if pos in seen:
            continue
        seen.add(pos)
        if pos == goal:
            return g, path
        for typ, to, c in moves(feet, pos, costs):
            if to in seen:
                continue
            heapq.heappush(openq, (g + c + h(to), g + c, to, path + [typ]))
    return None, None


def report(name, feet, start, goal):
    print(f"\n== {name} ==  start={start} goal={goal}")
    for label, costs in (("旧模型 (1.00/1.41/2.00/1.00)", COST_OLD),
                         ("标定后 (1.00/1.33/1.67/2.67)", COST_NEW)):
        g, path = astar(feet, start, goal, costs)
        if g is None:
            print(f"  {label}: UNREACHABLE")
            continue
        ticks = sum(MEASURED_TICKS[t] for t in path)
        print(f"  {label}: cost={g:5.2f} 实测≈{ticks:3d} tick  {'+'.join(path)}")


if __name__ == '__main__':
    # Q7 验收场景 dip_course：x=-1..2、z=65 的 1 格深坑
    pit = {(x, 65) for x in range(-1, 3)}
    report("dip_course", build_feet(pit=pit), (0, 64, 66), (-1, 64, 63))
    # 旧版 1x1 坑场景（已废弃，保留作为工具自校验）
    report("自校验：旧 1x1 坑", build_feet(pit={(0, 65)}), (0, 64, 66), (0, 64, 64))
