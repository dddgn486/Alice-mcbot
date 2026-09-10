#!/usr/bin/env python3
"""离线预测伐木决策：用抓取到的方块数据复刻 Alice 的候选判定，先算"会选哪棵、走哪条策略"。

用途：换夹具时**不必先开客户端**就能知道预期结果，并核对断言是否合理。
镜像的生产口径（与 Java 侧一一对应，改动时两边都要改）：

- 触及 `reach = 4.5`，有效触及 `reach - reachMargin(0.4) = 4.1`
- 眼位 = 脚位中心 + 1.12（`BOT_EYE_HEIGHT 1.62 - 0.5`）
- 可见性样本 = 方块中心 + 6 个**面心**（各向内缩 `losSampleEpsilon 0.08`）
- 可站 = 下方有碰撞 + 脚位与头位无碰撞
- 站位候选 = y+1/y/y−1 全水平（半径 ceil(reach)+1）+ 正下方 k=2..6
- 视线用体素步进（只把"有碰撞"的方块当遮挡）

用法：
    tools/analyze-lumber-scene.py --world <存档> --from x y z --to x y z
"""

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "capture_scene", os.path.join(os.path.dirname(os.path.abspath(__file__)), "capture-scene.py"))
cap = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cap)

REACH = 4.5
REACH_MARGIN = 0.4
EFFECTIVE = REACH - REACH_MARGIN
EYE_HEIGHT = 1.62
EPSILON = 0.08

# 无碰撞（可穿过）的方块：与 ClipContext.Block.COLLIDER 的语义对齐（近似）
NON_SOLID = {
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
    "minecraft:water", "minecraft:lava",
    "minecraft:short_grass", "minecraft:grass", "minecraft:tall_grass",
    "minecraft:fern", "minecraft:large_fern", "minecraft:dandelion",
    "minecraft:poppy", "minecraft:torch", "minecraft:wall_torch",
    "minecraft:vine", "minecraft:glow_lichen", "minecraft:moss_carpet",
    "minecraft:snow", "minecraft:oak_sapling", "minecraft:spruce_sapling",
    "minecraft:birch_sapling", "minecraft:dark_oak_sapling",
    "minecraft:jungle_sapling", "minecraft:acacia_sapling",
}

LOG_SUFFIXES = ("_log", "_wood", "_stem", "_hyphae")


def base_name(block: str) -> str:
    return block.split("[")[0]


def is_log(block: str) -> bool:
    name = base_name(block)
    return name.startswith("minecraft:") and name.endswith(LOG_SUFFIXES)


def is_leaves(block: str) -> bool:
    return base_name(block).endswith("_leaves")


def solid(block: str) -> bool:
    return base_name(block) not in NON_SOLID


class Scene:
    def __init__(self, world: str, start, end):
        self.world = world
        self.start = start
        self.end = end

    def at(self, x, y, z) -> str:
        return self.world.block_at(x, y, z)

    def solid(self, x, y, z) -> bool:
        return solid(self.world.block_at(x, y, z))

    def standable(self, x, y, z) -> bool:
        return (self.solid(x, y - 1, z) and not self.solid(x, y, z)
                and not self.solid(x, y + 1, z))


def eye(x, y, z):
    return (x + 0.5, y + EYE_HEIGHT, z + 0.5)


def samples(x, y, z):
    return [
        (x + 0.5, y + 0.5, z + 0.5),
        (x + EPSILON, y + 0.5, z + 0.5),
        (x + 1 - EPSILON, y + 0.5, z + 0.5),
        (x + 0.5, y + EPSILON, z + 0.5),
        (x + 0.5, y + 1 - EPSILON, z + 0.5),
        (x + 0.5, y + 0.5, z + EPSILON),
        (x + 0.5, y + 0.5, z + 1 - EPSILON),
    ]


def first_solid(scene: Scene, origin, target):
    """体素步进（Amanatides & Woo）：返回射线上第一个有碰撞方块的坐标。"""
    ox, oy, oz = origin
    dx, dy, dz = target[0] - ox, target[1] - oy, target[2] - oz
    length = math.sqrt(dx * dx + dy * dy + dz * dz)
    if length < 1e-9:
        return None
    dx, dy, dz = dx / length, dy / length, dz / length
    x, y, z = math.floor(ox), math.floor(oy), math.floor(oz)
    step_x = 1 if dx > 0 else -1
    step_y = 1 if dy > 0 else -1
    step_z = 1 if dz > 0 else -1
    inf = float("inf")
    t_max_x = ((x + (1 if dx > 0 else 0)) - ox) / dx if dx else inf
    t_max_y = ((y + (1 if dy > 0 else 0)) - oy) / dy if dy else inf
    t_max_z = ((z + (1 if dz > 0 else 0)) - oz) / dz if dz else inf
    t_delta_x = abs(1 / dx) if dx else inf
    t_delta_y = abs(1 / dy) if dy else inf
    t_delta_z = abs(1 / dz) if dz else inf
    for _ in range(512):
        if scene.solid(x, y, z):
            return (x, y, z)
        if t_max_x <= t_max_y and t_max_x <= t_max_z:
            if t_max_x > length:
                return None
            x += step_x
            t_max_x += t_delta_x
        elif t_max_y <= t_max_z:
            if t_max_y > length:
                return None
            y += step_y
            t_max_y += t_delta_y
        else:
            if t_max_z > length:
                return None
            z += step_z
            t_max_z += t_delta_z
    return None


def stand_candidates(scene: Scene, tx, ty, tz):
    radius = math.ceil(REACH) + 1
    result = []
    for dy in (1, 0, -1):
        for dx in range(-radius, radius + 1):
            for dz in range(-radius, radius + 1):
                result.append((tx + dx, ty + dy, tz + dz))
    for k in range(2, int(math.floor(REACH + 1.54)) + 1):
        result.append((tx, ty - k, tz))
    return result


def can_see(scene: Scene, stand, target) -> bool:
    sx, sy, sz = stand
    if not scene.standable(sx, sy, sz):
        return False
    ex, ey, ez = eye(sx, sy, sz)
    for sample in samples(*target):
        hit = first_solid(scene, (ex, ey, ez), sample)
        if hit == target:
            return True
    return False


def visible_from_any_stand(scene: Scene, target) -> bool:
    for stand in stand_candidates(scene, *target):
        if can_see(scene, stand, target):
            return True
    return False


def first_blocker(scene: Scene, target):
    """从任一可站立观察位看过去，遇到的第一个遮挡方块。"""
    for stand in stand_candidates(scene, *target):
        if not scene.standable(*stand):
            continue
        ex, ey, ez = eye(*stand)
        for sample in samples(*target):
            hit = first_solid(scene, (ex, ey, ez), sample)
            if hit is not None and hit != target:
                return hit
    return None


def scan_trees(scene: Scene, start, end):
    """镜像 TreeScanner：上行 3×3 + 同层水平 4 向 + 正下方 1 格。"""
    visited = set()
    trees = []
    for y in range(start[1], end[1] + 1):
        for z in range(start[2], end[2] + 1):
            for x in range(start[0], end[0] + 1):
                if (x, y, z) in visited or not is_log(scene.at(x, y, z)):
                    continue
                base = (x, y, z)
                while is_log(scene.at(base[0], base[1] - 1, base[2])):
                    base = (base[0], base[1] - 1, base[2])
                if base in visited:
                    continue
                logs = []
                seen = {base}
                queue = [base]
                while queue:
                    cx, cy, cz = queue.pop()
                    if not is_log(scene.at(cx, cy, cz)):
                        continue
                    logs.append((cx, cy, cz))
                    neighbours = []
                    for dx in (-1, 0, 1):
                        for dz in (-1, 0, 1):
                            neighbours.append((cx + dx, cy + 1, cz + dz))
                    neighbours += [(cx + 1, cy, cz), (cx - 1, cy, cz),
                                   (cx, cy, cz + 1), (cx, cy, cz - 1),
                                   (cx, cy - 1, cz)]
                    for pos in neighbours:
                        if pos not in seen:
                            seen.add(pos)
                            queue.append(pos)
                visited |= set(logs)
                if len(logs) >= 3:
                    trees.append(sorted(logs, key=lambda p: (p[1], p[0], p[2])))
    return trees


def anchor_stands(scene: Scene, logs):
    logset = set(logs)
    result = []
    for cell in logs:
        below = (cell[0], cell[1] - 1, cell[2])
        if below in logset:
            continue
        if scene.solid(*below):
            result.append(cell)
    return result


def deferred_reachable(scene: Scene, logs, log):
    for stand in anchor_stands(scene, logs):
        if stand == log:
            continue
        # 头位 = 站位上方一格必须是空的 → 目标至少比站位高 2 格
        # （紧邻其上的那根原木只能从侧面挖；树冠挡侧面时就必须清障）
        if log[1] < stand[1] + 2:
            continue
        if abs(stand[0] - log[0]) > 1 or abs(stand[2] - log[2]) > 1:
            continue
        ex, ey, ez = eye(*stand)
        best = min(math.dist((ex, ey, ez), s) for s in samples(*log))
        if best <= EFFECTIVE:
            return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--world", required=True)
    parser.add_argument("--from", dest="start", nargs=3, type=int, required=True)
    parser.add_argument("--to", dest="end", nargs=3, type=int, required=True)
    args = parser.parse_args()

    scene = Scene(cap.World(args.world), args.start, args.end)
    trees = scan_trees(scene, args.start, args.end)
    print("发现 %d 棵树\n" % len(trees))
    header = ("% -22s %-12s %5s %5s %7s %8s %5s %5s   %s" %
              ("base", "species", "logs", "height", "visible", "deferred", "soft", "hard", "verdict"))
    print(header)
    print("-" * len(header))
    for logs in trees:
        base = logs[0]
        species = base_name(scene.at(*base)).replace("minecraft:", "").replace("_log", "").replace("_stem", "")
        visible = deferred = soft = hard = 0
        for log in logs:
            if visible_from_any_stand(scene, log):
                visible += 1
                continue
            if deferred_reachable(scene, logs, log):
                deferred += 1
                continue
            blocker = first_blocker(scene, log)
            if blocker is not None and is_leaves(scene.at(*blocker)):
                soft += 1
            else:
                hard += 1
        height = logs[-1][1] - logs[0][1] + 1
        verdict = "可行" if hard == 0 and soft <= 8 else "拒绝"
        print("%-22s %-12s %5d %5d %7d %8d %5d %5d   %s" %
              ("%d,%d,%d" % base, species, len(logs), height, visible, deferred, soft, hard, verdict))
    return 0


if __name__ == "__main__":
    sys.exit(main())
