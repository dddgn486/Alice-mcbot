#!/usr/bin/env python3
"""解析 `[TRACE]` 轨迹行并给出"连续行动/顿挫"摘要。

用法：python3 tools/analyze-trace.py <latest.log 路径> [玩家/机器人名]

说明：
- 数据包版（Baritone 侧玩家）的 `Motion` NBT 恒为 0（客户端驱动移动），因此水平速度
  一律用**位置差分**计算；Alice 侧 `BotTrace` 的 Motion 是真的，两种口径以位置差分为准。
- 输出：每次记录段的样本数、行走区间、每 tick 水平位移序列、停顿 tick（位移 <0.02）位置。
"""
import math
import re
import sys

PATTERN = re.compile(
    r'\[TRACE\] (\S+) t=(\d+) x=(-?\d+) y=(-?\d+) z=(-?\d+) vx=(-?\d+) vy=(-?\d+) vz=(-?\d+) og=(\d+) yaw=(-?\d+)')


def parse(path, who=None):
    rows = []
    with open(path, errors='ignore') as fh:
        for line in fh:
            m = PATTERN.search(line)
            if not m:
                continue
            name = m.group(1)
            if who and name != who:
                continue
            t, x, y, z = int(m.group(2)), int(m.group(3)) / 1000, int(m.group(4)) / 1000, int(m.group(5)) / 1000
            og = int(m.group(9))
            rows.append((name, t, x, y, z, og))
    return rows


def segments(rows):
    out, cur = [], []
    for r in rows:
        if r[1] == 1 and cur:
            out.append(cur)
            cur = []
        cur.append(r)
    if cur:
        out.append(cur)
    return out


def report(seg, label):
    name = seg[0][0]
    print(f"\n== {label}（{name}）样本 {len(seg)} tick ==")
    moving = []
    prev = None
    for (_, t, x, y, z, og) in seg:
        if prev is not None:
            dh = math.hypot(x - prev[1], z - prev[3])
            dy = y - prev[2]
            moving.append((t, dh, dy, og))
        prev = (t, x, y, z)
    walk = [m for m in moving if m[1] > 0.02]
    stops = [m for m in moving if m[1] <= 0.02]
    print(f"行走 tick={len(walk)}  停顿 tick={len(stops)}")
    if walk:
        speeds = [m[1] for m in walk]
        print(f"水平位移/tick: 最大={max(speeds):.3f} 平均={sum(speeds)/len(speeds):.3f} 最小={min(speeds):.3f}")
        t0 = walk[0][0]
        print("行走段序列（t:位移）:", " ".join(f"{t}:{dh:.2f}" for t, dh, _, _ in walk))
        print("停顿位置（t:z）:", ", ".join(f"{t}:{seg[t - 1][4]:.2f}" for t, _, _, _ in stops[:12]) or "无")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    rows = parse(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
    segs = segments(rows)
    print(f"解析到 {len(rows)} 条 TRACE，{len(segs)} 段")
    for i, seg in enumerate(segs, 1):
        report(seg, f"段{i}")
