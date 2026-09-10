#!/usr/bin/env python3
"""从存档抓取一段场景，生成可复现的数据包函数（Alice 夹具工具）。

用途：真实地形（例如玩家亲手种出来的真树）无法靠手写 `setblock` 复现，
本工具直接读 Anvil 区域文件（1.18+ 扁平区块格式：palette + packed longs），
把指定长方体区域内的方块导出成 `.mcfunction`，作为**场景夹具源码**入库。

用法：
    tools/capture-scene.py --world <存档目录> \
        --from 18 59 204 --to 30 80 222 \
        --out tools/test-scenes/alice_test/data/alice_test/functions/lumber_course_terrain.mcfunction \
        [--summary]

- 第一行输出 `fill … air`（保证重放幂等），随后每个非空气方块一行 `setblock`。
- `--summary` 额外打印方块类型统计（便于确认抓到的确实是树）。
"""

import argparse
import gzip
import os
import struct
import sys
import zlib
from collections import Counter

# ── NBT 解析（只实现需要的标签类型）──


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.i = 0

    def u1(self):
        value = self.data[self.i]
        self.i += 1
        return value

    def i1(self):
        value = struct.unpack_from(">b", self.data, self.i)[0]
        self.i += 1
        return value

    def i2(self):
        value = struct.unpack_from(">h", self.data, self.i)[0]
        self.i += 2
        return value

    def i4(self):
        value = struct.unpack_from(">i", self.data, self.i)[0]
        self.i += 4
        return value

    def i8(self):
        value = struct.unpack_from(">q", self.data, self.i)[0]
        self.i += 8
        return value

    def s(self):
        size = struct.unpack_from(">H", self.data, self.i)[0]
        self.i += 2
        value = self.data[self.i:self.i + size].decode("utf-8")
        self.i += size
        return value

    def payload(self, tag: int):
        if tag == 1:
            return self.i1()
        if tag == 2:
            return self.i2()
        if tag == 3:
            return self.i4()
        if tag == 4:
            return self.i8()
        if tag == 5:
            value = struct.unpack_from(">f", self.data, self.i)[0]
            self.i += 4
            return value
        if tag == 6:
            value = struct.unpack_from(">d", self.data, self.i)[0]
            self.i += 8
            return value
        if tag == 7:
            return [self.i1() for _ in range(self.i4())]
        if tag == 8:
            return self.s()
        if tag == 9:
            element = self.u1()
            return [self.payload(element) for _ in range(self.i4())]
        if tag == 10:
            result = {}
            while True:
                child = self.u1()
                if child == 0:
                    return result
                # 注意：必须先读名字再读值——`result[self.s()] = self.payload(child)`
                # 会先求值右侧（Python 赋值先算 RHS），导致名称/值顺序颠倒而错位。
                name = self.s()
                result[name] = self.payload(child)
        if tag == 11:
            return [self.i4() for _ in range(self.i4())]
        if tag == 12:
            return [self.i8() for _ in range(self.i4())]
        raise ValueError("unsupported NBT tag %d" % tag)


def parse_nbt(data: bytes) -> dict:
    reader = Reader(data)
    if reader.u1() != 10:
        raise ValueError("root tag is not a compound")
    reader.s()
    return reader.payload(10)


# ── Anvil 区域文件读取 ──


def read_chunk(world: str, cx: int, cz: int):
    region = "r.%d.%d.mca" % (cx >> 5, cz >> 5)
    path = os.path.join(world, "region", region)
    if not os.path.exists(path):
        return None
    with open(path, "rb") as handle:
        header = handle.read(4096)
        index = (cx & 31) + (cz & 31) * 32
        entry = header[index * 4:index * 4 + 4]
        offset = int.from_bytes(entry[:3], "big")
        if offset == 0 or entry[3] == 0:
            return None
        handle.seek(offset * 4096)
        length = struct.unpack(">I", handle.read(4))[0]
        compression = handle.read(1)[0]
        payload = handle.read(length - 1)
    if compression == 1:
        payload = gzip.decompress(payload)
    elif compression == 2:
        payload = zlib.decompress(payload)
    return parse_nbt(payload)


class World:
    """按需读取区块的方块查询器（缓存已解析区块）。"""

    def __init__(self, world: str):
        self.world = world
        self.chunks = {}

    def chunk(self, cx: int, cz: int):
        key = (cx, cz)
        if key not in self.chunks:
            self.chunks[key] = read_chunk(self.world, cx, cz)
        return self.chunks[key]

    def block_at(self, x: int, y: int, z: int) -> str:
        chunk = self.chunk(x >> 4, z >> 4)
        if chunk is None:
            return "minecraft:air"
        for section in chunk.get("sections", []):
            if section.get("Y") != (y >> 4):
                continue
            states = section.get("block_states")
            if not states:
                return "minecraft:air"
            palette = states["palette"]
            if len(palette) == 1:
                return format_entry(palette[0])
            data = states.get("data")
            if not data:
                return format_entry(palette[0])
            bits = max(4, (len(palette) - 1).bit_length())
            per_long = 64 // bits
            index = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
            long_index = index // per_long
            shift = (index % per_long) * bits
            if long_index >= len(data):
                return "minecraft:air"
            value = (data[long_index] >> shift) & ((1 << bits) - 1)
            if value >= len(palette):
                return "minecraft:air"
            return format_entry(palette[value])
        return "minecraft:air"


def format_entry(entry: dict) -> str:
    name = entry.get("Name", "minecraft:air")
    properties = entry.get("Properties")
    if properties:
        pairs = ",".join("%s=%s" % (key, value) for key, value in sorted(properties.items()))
        return "%s[%s]" % (name, pairs)
    return name


def capture(world: World, start, end, out_path: str, summary: bool) -> Counter:
    x1, y1, z1 = start
    x2, y2, z2 = end
    if x1 > x2:
        x1, x2 = x2, x1
    if y1 > y2:
        y1, y2 = y2, y1
    if z1 > z2:
        z1, z2 = z2, z1

    counter = Counter()
    lines = []
    for y in range(y1, y2 + 1):
        for z in range(z1, z2 + 1):
            for x in range(x1, x2 + 1):
                block = world.block_at(x, y, z)
                if block == "minecraft:air":
                    continue
                counter[block.split("[")[0]] += 1
                lines.append("setblock %d %d %d %s" % (x, y, z, block))

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write("# 区域：x %d..%d, y %d..%d, z %d..%d（由 tools/capture-scene.py 从存档抓取）\n"
                     % (x1, x2, y1, y2, z1, z2))
        handle.write("fill %d %d %d %d %d %d minecraft:air\n" % (x1, y1, z1, x2, y2, z2))
        handle.write("\n".join(lines))
        handle.write("\n")

    if summary:
        for block, count in counter.most_common():
            print("  %-40s %d" % (block, count))
    print("wrote %s (%d blocks)" % (out_path, len(lines)), file=sys.stderr)
    return counter


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--world", required=True, help="存档目录（含 region/）")
    parser.add_argument("--from", dest="start", nargs=3, type=int, required=True)
    parser.add_argument("--to", dest="end", nargs=3, type=int, required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
    capture(World(args.world), args.start, args.end, args.out, args.summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
