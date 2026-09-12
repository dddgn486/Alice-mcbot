#!/usr/bin/env python3
"""场景"可规划性"离线自检（T6 / D-125）：**不需要客户端**，直接查夹具会不会自封航线。

为什么需要
----------
D-117 的教训：`lumber_course` 的树冠在 y=65 的 z=212..214 连成 x=17..31 的墙，可行走台地只有
x=18..30 ⇒ 只剩一条通道；第 4 棵橡树种进那段后**通道被封死**，bot 再也走不到南侧站位，
19/24 两棵树自那以后每轮全败 —— 而这一改动的验收等级只到 `COMPILES`，**没人能离线发现**。
本工具把"夹具是否自封航线"变成**机器可查**：从起点做保守可达性泛洪，再看每个目标是否
**至少有一个可达的合法站位**。

设计口径（有意保守，避免与内核判定分叉）
----------------------------------------
- **方块表**复用 `analyze-lumber-scene.py` 的 `FixtureWorld`（从数据包函数 setblock/fill 重建，
  "夹具即真相"），站位/视线判据复用它的 `stand_candidates` / `can_see`（与 Java 侧
  `StandingPointSelector` 同口径）——**不另写一套**。
- **可达性只做保守下界**：水平 1 格 / 上 1 格 / 下落 ≤3 格，对角要求两侧正交格都可通行；
  **不含**跳跃越沟、加高（PILLAR）、破坏通行。故判"不可达"是**强提示但非定论**：
  请用真规划器（游戏内电池）复核。判"可达"则基本可信（是内核能力的子集）。
- 输出 ASCII 切片与可达/搁浅站位标记，便于人/助手一眼看出"通道在哪、被谁堵了"。

用法
----
    tools/check-scene-connectivity.py --all                 # 扫描内置场景清单
    tools/check-scene-connectivity.py --scene lumber_course # 单个场景
    tools/check-scene-connectivity.py --fixture a.mcfunction b.mcfunction --start 0 64 66
    tools/check-scene-connectivity.py --selftest            # 自检（合成一条被封死的通道，必须报不可达）

退出码：有目标"无任何可达合法站位" ⇒ 1（可用 `--warn-only` 只看报告）。
"""

from __future__ import annotations

import argparse
import importlib.util
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FUNCTIONS_DIR = os.path.join(
    HERE, "test-scenes", "alice_test", "data", "alice_test", "functions")


def _load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# 复用既有离线分析器（方块表 + 站位/视线判据），避免第二套实现
ana = _load_module("analyze_lumber_scene", "analyze-lumber-scene.py")

# 下落上限：与内核 FALL 的常用限深一致（保守取 3）
MAX_FALL = 3
# 每个目标允许的"合法站位"上限（保证站点集合不会爆炸）
MAX_STANDS_PER_TARGET = 64


# ==================== 场景清单 ====================

# 场景清单（**权威来源**：`task/PathingRegressionTask` 的场景表 + 各 Job 场景的起点）
#   scenes           按顺序执行的场景函数
#   start            起点脚位
#   goals            该场景必须(或必须不)可达的目标脚位
#   expect_gap       True = 该场景的目标**应当不可达**（PLAN_REFUSED：fluid/fence）——
#                    这条同样会被校验：如果哪天场景被改得"能过去了"，测试的前提就失效了
#   needs_world_mod  True = 场景依赖破坏/放置/加高通行（break/place/pillar/vertical/fall…），
#                    **保守模型不判其目标**（模型只覆盖纯通行；判了会假报不可达）
SCENES = {
    # —— Job 场景：目标是原木/矿石，这里只需给起点（目标自动从方块表里扫）——
    "lumber_course":     dict(scenes=["lumber_course_terrain", "lumber_course_trees"], start=(23, 64, 207)),
    "ore_course":        dict(scenes=["ore_course_terrain"], start=(56, 63, 132)),
    "mine_course":       dict(scenes=["mine_course_terrain"], start=(21, 64, 140)),
    "chain_mine_course": dict(scenes=["chain_mine_course_terrain"], start=(23, 64, 170)),
    "floating_course":   dict(scenes=["floating_course_terrain"], start=(21, 64, 190)),
    "clear_guard_course": dict(scenes=["clear_guard_terrain"], start=(44, 64, 158)),
    "scaffold_course":   dict(scenes=["scaffold_course_terrain"], start=(38, 64, 46)),
    "break_course":      dict(scenes=["break_course_terrain"], start=(0, 64, 66),
                              goals=[(7, 64, 66)], needs_world_mod=True),
    "write_budget":      dict(scenes=["break_course_terrain"], start=(0, 64, 66),
                              goals=[(7, 64, 66)], needs_world_mod=True),
    # —— 寻路场景：目标格来自 PathingRegressionTask 的场景表 ——
    "pathing_course":    dict(scenes=["pathing_course_terrain"], start=(0, 64, 46), goals=[(0, 62, 44)]),
    "place_course":      dict(scenes=["place_course_terrain"], start=(0, 64, 66),
                              goals=[(8, 62, 66)], needs_world_mod=True),
    "vertical_course":   dict(scenes=["vertical_course_terrain"], start=(0, 64, 45),
                              goals=[(0, 63, 45)], needs_world_mod=True),
    "pillar_course":     dict(scenes=["pillar_course_terrain"], start=(24, 64, 44),
                              goals=[(25, 67, 44)], needs_world_mod=True),
    "fall_course":       dict(scenes=["fall_course_terrain"], start=(22, 64, 68),
                              goals=[(23, 61, 68)], needs_world_mod=True),
    "break_enter_course": dict(scenes=["break_enter_course_terrain"], start=(22, 64, 100),
                               goals=[(23, 64, 100)], needs_world_mod=True),
    "trace_course":      dict(scenes=["trace_course_terrain"], start=(0, 64, 40), goals=[(0, 64, 51)]),
    "chest_step_course": dict(scenes=["chest_step_course_terrain"], start=(1, 64, 126), goals=[(2, 65, 126)]),
    "slab_step_course":  dict(scenes=["slab_step_course_terrain"], start=(4, 64, 145), goals=[(8, 64, 145)]),
    "dip_course":        dict(scenes=["dip_course_terrain"], start=(0, 64, 66), goals=[(-1, 64, 63)]),
    # 下面两个的"过不去"是**内核策略**（不游泳 / 不跨栅栏），不是几何隔断 ⇒ 保守模型不判其目标
    "fluid_course":      dict(scenes=["fluid_course_terrain"], start=(0, 64, 66),
                              goals=[(4, 64, 66)], policy_only=True),
    "fence_course":      dict(scenes=["fence_course_terrain"], start=(0, 64, 48),
                              goals=[(0, 64, 44)], policy_only=True),
    "lava_course":       dict(scenes=["lava_course_terrain"], start=(0, 64, 66),
                              goals=[(4, 64, 66)], needs_world_mod=True),
}


def scene_files(names):
    return [os.path.join(FUNCTIONS_DIR, n + ".mcfunction") for n in names]


# ==================== 方块表 ====================

class MultiFixtureWorld:
    """按顺序叠加多个 mcfunction 的方块表（后面的覆盖前面的，含 `fill … air`）。"""

    def __init__(self, paths):
        self.blocks = {}
        for path in paths:
            world = ana.FixtureWorld(path)
            world._load()
            self.blocks.update(world.blocks)
        self._loaded = True

    def block_at(self, x, y, z) -> str:
        return self.blocks.get((x, y, z), "minecraft:air")


def scene_of(names, start):
    paths = [os.path.join(FUNCTIONS_DIR, name + ".mcfunction") for name in names]
    missing = [p for p in paths if not os.path.exists(p)]
    if missing:
        raise SystemExit("场景函数不存在: %s" % ", ".join(missing))
    world = MultiFixtureWorld(paths)
    return ana.Scene(world, start, start)


# ==================== 可达性（保守下界） ====================

def solid_cell(scene, x, y, z) -> bool:
    return scene.solid(x, y, z)


def standable(scene, x, y, z) -> bool:
    """能站在 (x,y,z)：下方实心 + 本格与头位可穿过（与 Java `MovementHelper` 同口径）。"""
    return (solid_cell(scene, x, y - 1, z)
            and not solid_cell(scene, x, y, z)
            and not solid_cell(scene, x, y + 1, z))


def clear_path(scene, x, y, z, nx, ny, nz) -> bool:
    """下落路径上不能有实心格挡。"""
    if nx == x and nz == z:
        step = -1 if ny < y else 1
        cur = y + step
        while cur != ny:
            if solid_cell(scene, x, cur, z):
                return False
            cur += step
        return True
    return True


def neighbours(scene, x, y, z):
    """保守邻接：水平同层 / 上 1 / 下落 ≤3；对角要求两侧正交格可通行且可站。"""
    out = []
    cardinals = ((1, 0), (-1, 0), (0, 1), (0, -1))
    diagonals = ((1, 1), (1, -1), (-1, 1), (-1, -1))

    def try_dest(nx, nz, ny_min=0, ny_max=0, via=None):
        for dy in range(ny_min, ny_max + 1):
            ny = y + dy
            if via is not None:
                vx, vz = via
                if not (standable(scene, x + vx, y, z + vz)
                        or standable(scene, x + vx, y + 1, z + vz)):
                    continue
            if dy > 0 and solid_cell(scene, x, y + 2, z):
                continue      # 起跳需要头位以上一格空
            if not standable(scene, nx, ny, nz):
                continue
            if not clear_path(scene, x, y, z, nx, ny, nz):
                continue
            out.append((nx, ny, nz))

    for dx, dz in cardinals:
        try_dest(x + dx, z + dz, ny_min=0, ny_max=1)
        for dy in range(1, MAX_FALL + 1):
            try_dest(x + dx, z + dz, ny_min=-dy, ny_max=-dy)
    for dx, dz in diagonals:
        try_dest(x + dx, z + dz, ny_min=0, ny_max=1, via=(dx, 0))
        try_dest(x + dx, z + dz, ny_min=0, ny_max=1, via=(0, dz))
    return out


def flood(scene, start):
    """从起点泛洪（保守下界）。返回 (可达集合, 全部可站集合)。"""
    if not standable(scene, *start):
        # 起点本身站不住：以起点为中心的±3 内找最近可站格（夹具常把观察点写在台地外）
        best = None
        for dy in range(0, 5):
            for dx in range(-6, 7):
                for dz in range(-6, 7):
                    cand = (start[0] + dx, start[1] - dy, start[2] + dz)
                    if standable(scene, *cand):
                        dist = abs(dx) + abs(dz) + dy
                        if best is None or dist < best[0]:
                            best = (dist, cand)
        if best is None:
            raise RuntimeError("起点 %s 附近 6 格内没有可站格（场景函数没造出台地？）" % (start,))
        start = best[1]

    reachable = {start}
    stack = [start]
    while stack:
        cur = stack.pop()
        for nxt in neighbours(scene, *cur):
            if nxt not in reachable:
                reachable.add(nxt)
                stack.append(nxt)

    # 全部可站格（用于"搁浅"标记；限定在可达集合的包围盒外扩 8 格内，避免扫全图）
    xs = [p[0] for p in reachable]
    ys = [p[1] for p in reachable]
    zs = [p[2] for p in reachable]
    all_stands = set()
    for x in range(min(xs) - 8, max(xs) + 9):
        for y in range(min(ys) - 6, max(ys) + 9):
            for z in range(min(zs) - 8, max(zs) + 9):
                if standable(scene, x, y, z):
                    all_stands.add((x, y, z))
    return start, reachable, all_stands


# ==================== 目标与站位 ====================

def scan_targets(scene, reachable):
    """目标 = 原木（树）与矿石；对每个目标求"合法站位"，再看有没有落在可达集合里。"""
    targets = []
    for (x, y, z), block in sorted(scene.world.blocks.items()):
        name = ana.base_name(block)
        if ana.is_log(block) or name.endswith("_ore"):
            targets.append((x, y, z))

    # 原木按竖直柱聚合（同一棵树的同一列只报一次，取最低那格为"目标代表"）
    columns = {}
    for (x, y, z) in targets:
        if ana.is_log(scene.at(x, y, z)):
            key = (x, z)
            columns[(x, z)] = min(y, columns.get((x, z), y))
    representative = [((x, y, z)) for (x, z), y in sorted(columns.items())]
    representative += [t for t in targets if not ana.is_log(scene.at(*t))]

    results = []
    for target in representative:
        stands = []
        for stand in ana.stand_candidates(scene, *target):
            if len(stands) >= MAX_STANDS_PER_TARGET:
                break
            if ana.can_see(scene, stand, target):
                stands.append(stand)
        ok = [s for s in stands if s in reachable]
        results.append((target, stands, ok))
    return results


def goal_reachable(reachable, goal) -> bool:
    """目标格本身或 ±1 邻域有一格在可达集合里即算可达（容忍"站位 vs 目标格"的表述差异）。"""
    x, y, z = goal
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if (x + dx, y + dy, z + dz) in reachable:
                    return True
    return False


def render_slice(scene, reachable, all_stands, y_lo, y_hi):
    """俯视切片：'#' 实心 / '.' 空 / 'o' 可达站位 / 'x' 搁浅站位。"""
    pts = list(reachable) + list(all_stands)
    if not pts:
        return []
    xs = [p[0] for p in pts]
    zs = [p[2] for p in pts]
    lines = []
    for z in range(min(zs) - 1, max(zs) + 2):
        row = []
        for x in range(min(xs) - 1, max(xs) + 2):
            solid = any(solid_cell(scene, x, y, z) for y in range(y_lo, y_hi + 1))
            if any(s in reachable for s in ((x, y, z) for y in range(y_lo, y_hi + 1))):
                row.append("o")
            elif any(s in all_stands for s in ((x, y, z) for y in range(y_lo, y_hi + 1))):
                row.append("x")
            elif solid:
                row.append("#")
            else:
                row.append(".")
        lines.append("z=%4d %s" % (z, "".join(row)))
    header = "       " + "".join(str(x % 10) for x in range(min(xs) - 1, max(xs) + 2))
    return [header] + lines


# ==================== 主流程 ====================

def check(name, spec, verbose=False, warn_only=False, quiet=False):
    names = spec["scenes"]
    start = tuple(spec["start"])
    goals = [tuple(g) for g in spec.get("goals", [])]
    expect_gap = bool(spec.get("expect_gap", False))
    needs_world_mod = bool(spec.get("needs_world_mod", False))
    policy_only = bool(spec.get("policy_only", False))

    scene = scene_of(names, start)
    entry, reachable, all_stands = flood(scene, start)
    results = scan_targets(scene, reachable)
    stranded = sorted(all_stands - reachable)

    # **硬伤**：目标有合法站位、但**全部不可达** ⇒ 通道被封（D-117 的签名，确定是场景缺陷）
    # **软提示**：目标**没有**合法站位 ⇒ 需要清障/协助才能作业（属 `analyze-lumber-scene.py`
    #           的判定范围：那里会算"需要清几格、是否根本做不到"）。两者混在一起会淹掉真信号。
    # **硬伤**：目标有合法站位、却**全部不可达** ⇒ 通道被封/区域被隔断（D-117 的签名；
    #         实测历史封死版本正是这样（高云杉 22/23,64,218/219 全部 0 可达））。
    # **软提示**：目标**没有**合法站位 ⇒ 需要清障/协助才能作业（伐木的正常流程）。
    #   ⚠ 已登记的盲区：像"从来就看不见目标、必须清障"的那类（19/24），本工具只给软提示 ——
    #   "清障是否可行"由 `analyze-lumber-scene.py` 的 soft/hard 判定，且它**不计可达性**。
    hard = [t for (t, stands, ok) in results if stands and not ok]
    soft = [t for (t, stands, ok) in results if not stands]
    bad_goals = []
    for goal in goals:
        if needs_world_mod or policy_only:
            continue                      # 保守模型不覆盖破坏/放置/加高通行，也不判"策略拒绝"场景
        got = goal_reachable(reachable, goal)
        if expect_gap and got:
            bad_goals.append((goal, "应当不可达，却可达（测试前提失效）"))
        elif not expect_gap and not got:
            bad_goals.append((goal, "不可达"))

    if not quiet:
        print("=" * 78)
        print("场景 %s  起点=%s(实际 %s)  函数=%s" % (name, start, entry, ", ".join(names)))
        print("可达站位 %d 格；搁浅站位 %d 格；目标 %d 个（封航线 %d 个 / 需清障或协助 %d 个）；目标格 %d 个%s"
              % (len(reachable), len(stranded), len(results), len(hard), len(soft), len(goals),
                 "（需世界修改通行，保守模型跳过）" if needs_world_mod
                 else ("（策略拒绝场景，保守模型跳过目标判定）" if policy_only else "")))
        for target, stands, ok in results:
            if ok and not verbose:
                continue
            tag = "✗ 封航线" if (stands and not ok) else "… 需清障/协助"
            print("  %s 目标 %-14s 合法站位 %2d，可达 %2d%s"
                  % (tag, target, len(stands), len(ok),
                     "" if stands else "（附近没有合法站位）"))
        for goal, why in bad_goals:
            print("  ✗ 目标格 %-14s %s" % (goal, why))
    failed = bool(hard) or bool(bad_goals)
    return 1 if (failed and not warn_only) else 0


def selftest() -> int:
    """自检：合成"2 格高墙封死唯一通道 vs 留一个 2 格高缺口"两个场景，判定必须相反。"""
    tmp = os.path.join("/tmp", "alice_scene_selftest")
    os.makedirs(tmp, exist_ok=True)

    def write(name, lines):
        path = os.path.join(tmp, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("".join(lines))
        return path

    floor = write("floor.mcfunction", [
        "fill 0 63 0 10 63 10 minecraft:stone\n",
        "fill 0 64 0 10 70 10 minecraft:air\n",
        "setblock 10 64 5 minecraft:iron_ore\n",       # 目标（墙的另一侧）
    ])
    sealed = write("sealed.mcfunction", ["fill 5 64 0 5 65 10 minecraft:stone\n"])
    open_lane = write("open_lane.mcfunction", [
        "fill 5 64 0 5 65 10 minecraft:stone\n",
        "setblock 5 64 5 minecraft:air\n",             # 留一个 2 格高缺口
        "setblock 5 65 5 minecraft:air\n",
    ])

    start = (1, 64, 5)
    world = MultiFixtureWorld([floor, sealed])
    scene = ana.Scene(world, start, start)
    _, reachable, _ = flood(scene, start)
    sealed_ok = any(stands and not ok for (_, stands, ok) in scan_targets(scene, reachable))

    world2 = MultiFixtureWorld([floor, open_lane])
    scene2 = ana.Scene(world2, start, start)
    _, reachable2, _ = flood(scene2, start)
    results2 = scan_targets(scene2, reachable2)
    open_ok = bool(results2) and all(ok for (_, _, ok) in results2)
    gap_ok = not goal_reachable(reachable2, (10, 64, 5)) is False   # 目标格此时可达

    print("[selftest] 封死通道 → 报不可达 = %s（期望 True）" % sealed_ok)
    print("[selftest] 留 2 格缺口 → 全部可达 = %s（期望 True）" % (open_ok and gap_ok))
    return 0 if (sealed_ok and open_ok and gap_ok) else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="跑内置场景清单")
    parser.add_argument("--scene", help="场景名（见 SCENES）")
    parser.add_argument("--fixture", nargs="+", help="直接给 mcfunction 文件（按顺序叠加）")
    parser.add_argument("--start", nargs=3, type=int, help="起点脚位 x y z")
    parser.add_argument("--verbose", action="store_true", help="打印每个目标与 ASCII 切片")
    parser.add_argument("--warn-only", action="store_true", help="只报告，不以退出码表示失败")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if args.fixture:
        start = tuple(args.start) if args.start else (0, 64, 66)
        return check("--fixture", [], start, args.verbose, args.warn_only) if False else _check_paths(
            args.fixture, start, args.verbose, args.warn_only)
    if args.scene:
        if args.scene not in SCENES:
            raise SystemExit("未知场景 %s（可选：%s）" % (args.scene, ", ".join(sorted(SCENES))))
        return check(args.scene, SCENES[args.scene], args.verbose, args.warn_only)
    if args.all:
        failures = []
        missing = []
        for name, spec in sorted(SCENES.items()):
            if any(not os.path.exists(p) for p in scene_files(spec["scenes"])):
                missing.append(name)
                continue
            try:
                if check(name, spec, args.verbose, warn_only=False, quiet=not args.verbose) != 0:
                    failures.append(name)
            except Exception as exc:                       # 单场景失败不拖垮整轮
                print("  ! 场景 %s 检查异常：%s" % (name, exc))
                failures.append(name + "(error)")
        print("=" * 78)
        print("汇总：%d 个场景检查完毕；跳过（场景函数不存在）%d 个：%s"
              % (len(SCENES) - len(missing), len(missing), ", ".join(missing) or "-"))
        print("有目标无可达站位的场景：%s" % (", ".join(failures) or "无"))
        return 0 if (not failures or args.warn_only) else 1
    parser.error("需要 --all / --scene / --fixture / --selftest 之一")


def _check_paths(paths, start, verbose, warn_only):
    missing = [p for p in paths if not os.path.exists(p)]
    if missing:
        raise SystemExit("文件不存在: %s" % ", ".join(missing))
    world = MultiFixtureWorld(paths)
    scene = ana.Scene(world, start, start)
    entry, reachable, all_stands = flood(scene, start)
    results = scan_targets(scene, reachable)
    hard = [t for (t, stands, ok) in results if stands and not ok]
    soft = [t for (t, stands, ok) in results if not stands]
    print("=" * 78)
    print("起点=%s(实际 %s)  可达站位 %d 格  目标 %d 个（封航线 %d / 需清障或协助 %d）"
          % (start, entry, len(reachable), len(results), len(hard), len(soft)))
    for target, stands, ok in results:
        tag = "✓" if ok else ("✗ 封航线" if stands else "… 需清障/协助")
        if ok and not verbose:
            continue
        print("  %s 目标 %-14s 合法站位 %2d 可达 %2d" % (tag, target, len(stands), len(ok)))
    if verbose:
        ys = [p[1] for p in reachable]
        for line in render_slice(scene, reachable, all_stands, min(ys), max(ys)):
            print("   " + line)
    return 1 if (hard and not warn_only) else 0


if __name__ == "__main__":
    sys.exit(main())
