#!/usr/bin/env python3
"""Anvil（`.mca`）区域文件**只读**方块普查 —— 离线核对"真机挖掘测试的候选集是否完整"。

为什么需要它（2026-09-20 真机 A 路线第一轮）：日志说 `select #1` 有 55 个候选，而分片扫描只
推进了 `visited=8192/117649`（≈ bot 所在高度的 3.4 层）。要判断"只挖了一部分"是
"**配额**停的"还是"**扫描没看见**"，必须知道**世界里到底有多少矿**——日志里没有、
截图看不全、也不能让 bot 再跑一轮，只能读存档。

只读：不写、不改存档。只用 stdlib（`zlib` + `struct`）解 Anvil 的 location 表与 NBT。

用法：
  python3 tools/region-ore-scan.py --save <存档目录> --center 470 69 91 --radius 24 \
      --band 68 71 \
      --restore 480,68,97,thermal:nickel_ore --restore 478,68,102,minecraft:coal_ore \
      --mined 480,68,97 --mined 478,68,102

自校验（`--expect x,y,z,id`）：给一个**日志能证明**的格子，解析结果必须一致——
这是"索引数学没错"的证据，不是"看起来对"。
"""
import argparse
import collections
import os
import re
import struct
import sys
import zlib


# --------------------------------------------------------------------------- NBT
class Reader:
    __slots__ = ("b", "i")

    def __init__(self, b):
        self.b = b
        self.i = 0

    def u8(self):
        v = self.b[self.i]
        self.i += 1
        return v

    def i8(self):
        v = self.b[self.i]
        self.i += 1
        return v - 256 if v > 127 else v

    def i16(self):
        v = struct.unpack_from(">h", self.b, self.i)[0]
        self.i += 2
        return v

    def i32(self):
        v = struct.unpack_from(">i", self.b, self.i)[0]
        self.i += 4
        return v

    def i64(self):
        v = struct.unpack_from(">q", self.b, self.i)[0]
        self.i += 8
        return v

    def f32(self):
        v = struct.unpack_from(">f", self.b, self.i)[0]
        self.i += 4
        return v

    def f64(self):
        v = struct.unpack_from(">d", self.b, self.i)[0]
        self.i += 8
        return v

    def string(self):
        n = struct.unpack_from(">H", self.b, self.i)[0]
        self.i += 2
        s = self.b[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return s

    def payload(self, t):
        if t == 1:
            return self.i8()
        if t == 2:
            return self.i16()
        if t == 3:
            return self.i32()
        if t == 4:
            return self.i64()
        if t == 5:
            return self.f32()
        if t == 6:
            return self.f64()
        if t == 7:
            n = self.i32()
            v = self.b[self.i:self.i + n]
            self.i += n
            return v
        if t == 8:
            return self.string()
        if t == 9:
            et = self.u8()
            n = self.i32()
            if et == 10:
                return [self.compound() for _ in range(n)]
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            return self.compound()
        if t == 11:
            n = self.i32()
            v = list(struct.unpack_from(">%di" % n, self.b, self.i))
            self.i += 4 * n
            return v
        if t == 12:
            n = self.i32()
            v = list(struct.unpack_from(">%dq" % n, self.b, self.i))
            self.i += 8 * n
            return v
        raise ValueError("未知 TAG %d @%d" % (t, self.i))

    def compound(self):
        out = {}
        while True:
            t = self.u8()
            if t == 0:
                return out
            name = self.string()
            out[name] = self.payload(t)


# ------------------------------------------------------------------- region 读取
def read_chunk(root, cx, cz):
    """读一个区块的 NBT 根 compound；不存在返回 None。"""
    rx, rz = cx >> 5, cz >> 5
    path = os.path.join(root, "region", "r.%d.%d.mca" % (rx, rz))
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        slot = 4 * ((cx & 31) + (cz & 31) * 32)
        f.seek(slot)
        loc = f.read(4)
        if len(loc) < 4:
            return None
        offset = int.from_bytes(loc[:3], "big")
        sectors = loc[3]
        if offset == 0 or sectors == 0:
            return None
        f.seek(offset * 4096)
        head = f.read(5)
        if len(head) < 5:
            return None
        length = struct.unpack(">i", head[:4])[0]
        comp = head[4]
        raw = f.read(length - 1)
    if comp == 1:
        raw = zlib.decompress(raw, 16 + zlib.MAX_WBITS)
    elif comp == 2:
        raw = zlib.decompress(raw)
    elif comp == 3:
        pass
    else:
        raise SystemExit("不支持的压缩类型 %d（r.%d.%d chunk %d,%d）" % (comp, rx, rz, cx, cz))
    r = Reader(raw)
    t = r.u8()
    if t != 10:
        raise SystemExit("区块根不是 compound（TAG=%d）" % t)
    r.string()
    return r.compound()


def section_blocks(sec):
    """产出一个 section 的 (localIndex, blockName)；无 block_states 时为空。"""
    bs = sec.get("block_states")
    if not bs:
        return
    palette = [p["Name"] for p in bs.get("palette", [])]
    if not palette:
        return
    if len(palette) == 1:
        for i in range(4096):
            yield i, palette[0]
        return
    data = bs.get("data")
    if not data:
        return
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    unsigned = [v & 0xFFFFFFFFFFFFFFFF for v in data]
    for i in range(4096):
        li = i // per_long
        if li >= len(unsigned):
            break
        v = (unsigned[li] >> ((i % per_long) * bits)) & mask
        if v < len(palette):
            yield i, palette[v]


# ------------------------------------------------------------------------ 主流程
ORE_RE = re.compile(r"(^|_)ores?($|_)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--save", required=True)
    ap.add_argument("--center", nargs=3, type=int, required=True, metavar=("X", "Y", "Z"))
    ap.add_argument("--radius", type=int, default=24)
    ap.add_argument("--band", nargs=2, type=int, metavar=("Y0", "Y1"),
                    help="已扫描的 y 带（含端点）；用来对照「带内 / 带外」")
    ap.add_argument("--y-range", nargs=2, type=int, default=[0, 255], metavar=("YMIN", "YMAX"),
                    help="只统计这个 y 区间（默认全高；扫描立方体 = 中心±radius）")
    ap.add_argument("--restore", action="append", default=[],
                    metavar="X,Y,Z,ID", help="把已被挖掉的格子按原方块还原（日志里能查到 id）")
    ap.add_argument("--mined", action="append", default=[], metavar="X,Y,Z")
    ap.add_argument("--expect", action="append", default=[], metavar="X,Y,Z,ID",
                    help="自校验：该格现在的方块必须是 ID（否则解析器有问题）")
    ap.add_argument("--pattern", default=None, help="自定义方块 id 正则（默认 _ore 家族）")
    ap.add_argument("--list-y", type=int, action="append", default=[],
                    help="列出这一层所有矿的坐标（一致性核对用）")
    args = ap.parse_args()

    cx, cy, cz = args.center
    r = args.radius
    x0, x1 = cx - r, cx + r
    y0, y1 = args.y_range
    z0, z1 = cz - r, cz + r
    if args.band:
        assert args.band[0] <= args.band[1]
    ore = re.compile(args.pattern) if args.pattern else ORE_RE

    blocks = {}
    chunk_count = 0
    for ccx in range(x0 >> 4, (x1 >> 4) + 1):
        for ccz in range(z0 >> 4, (z1 >> 4) + 1):
            root = read_chunk(args.save, ccx, ccz)
            if root is None:
                continue
            chunk_count += 1
            for sec in root.get("sections", []):
                sy = sec.get("Y", 0)
                sy = sy - 256 if sy > 127 else sy
                base = sy * 16
                if base + 15 < y0 or base > y1:
                    continue
                touch = (base + 15 >= (args.band[0] if args.band else y0)) or True
                if not touch:
                    continue
                for idx, name in section_blocks(sec):
                    lx = idx & 15
                    lz = (idx >> 4) & 15
                    ly = idx >> 8
                    wx = (ccx << 4) + lx
                    wz = (ccz << 4) + lz
                    wy = base + ly
                    if x0 <= wx <= x1 and z0 <= wz <= z1 and y0 <= wy <= y1:
                        blocks[(wx, wy, wz)] = name

    for spec in args.restore:
        sx, sy, sz, bid = spec.split(",", 3)
        blocks[(int(sx), int(sy), int(sz))] = bid

    print("存档: %s" % args.save)
    print("盒子: x[%d..%d] y[%d..%d] z[%d..%d] · 体积=%d · 已载入区块=%d"
          % (x0, x1, y0, y1, z0, z1, (2 * r + 1) ** 2 * (y1 - y0 + 1), chunk_count))

    # ---- 自校验 ----
    bad = 0
    for spec in args.expect:
        sx, sy, sz, want = spec.split(",", 3)
        got = blocks.get((int(sx), int(sy), int(sz)), "<未载入>")
        ok = "OK " if got == want else "!! "
        if got != want:
            bad += 1
        print("  %s expect %s @%s,%s,%s 实际=%s" % (ok, want, sx, sy, sz, got))
    for spec in args.mined:
        sx, sy, sz = (int(v) for v in spec.split(","))
        print("  挖过 %s,%s,%s 现在=%s" % (sx, sy, sz, blocks.get((int(sx), int(sy), int(sz)), "<未载入>")))

    # ---- 矿普查 ----
    by_id = collections.Counter()
    by_y = collections.defaultdict(collections.Counter)
    ore_pos = {}
    for pos, name in blocks.items():
        if ore.search(name.split(":")[-1]):
            by_id[name] += 1
            by_y[pos[1]][name] += 1
            ore_pos[pos] = name

    total = sum(by_id.values())
    print("\n=== 本盒矿方块（还原后）合计=%d ===" % total)
    for name, n in by_id.most_common():
        print("  %-40s %d" % (name, n))

    print("\n=== 按 y 分层（只列有矿的层）===")
    for y in sorted(by_y, reverse=True):
        parts = ", ".join("%s×%d" % (k.split(":")[-1], v) for k, v in by_y[y].most_common())
        print("  y=%-4d 共%-4d %s" % (y, sum(by_y[y].values()), parts))

    if args.band:
        b0, b1 = args.band
        inside = {p: n for p, n in ore_pos.items() if b0 <= p[1] <= b1}
        below = {p: n for p, n in ore_pos.items() if p[1] < b0}
        above = {p: n for p, n in ore_pos.items() if p[1] > b1}
        print("\n=== 扫描带 y[%d..%d] 对照 ===" % (b0, b1))
        print("  带内 %d · 带下(<%d) %d · 带上(>%d) %d"
              % (len(inside), b0, len(below), b1, len(above)))
        if below:
            print("  ⚠ 带下有矿（扫描从没到过）:")
            for p in sorted(below)[:40]:
                print("     %s %s" % (p, below[p]))

    if args.list_y:
        for y in args.list_y:
            layer = sorted((p for p in ore_pos if p[1] == y), key=lambda q: (q[2], q[0]))
            print("\n=== y=%d 的矿（%d 个）===" % (y, len(layer)))
            for p in layer:
                print("     %s %s" % (p, ore_pos[p]))

    # ---- 已挖目标的同簇残留 ----
    if args.mined:
        mined = set()
        for spec in args.mined:
            sx, sy, sz = (int(v) for v in spec.split(","))
            mined.add((sx, sy, sz))
        neigh = [(dx, dy, dz) for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)
                 if (dx, dy, dz) != (0, 0, 0)]
        adj = {}
        for m in mined:
            for d in neigh:
                p = (m[0] + d[0], m[1] + d[1], m[2] + d[2])
                if p in ore_pos:
                    adj[p] = ore_pos[p]
        print("\n=== 26 邻域对照：已挖 %d 格，仍与它们相邻的矿 =%d ===" % (len(mined), len(adj)))
        for p in sorted(adj, key=lambda q: (q[2], q[0], q[1])):
            print("     %s %s" % (p, adj[p]))

    if bad:
        print("\n自校验失败 %d 项 ⇒ 解析结果不可信" % bad)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
