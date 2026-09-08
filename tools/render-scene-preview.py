#!/usr/bin/env python3
"""场景预览图渲染器（纯 stdlib：zlib + 手写 PNG 编码 + 5x7 位图字模）。

用途：设计验收场景时生成"精确的方块布局预览"，供用户审批后再写 mcfunction。
不依赖 PIL/matplotlib/ImageMagick。

用法：python3 tools/render-scene-preview.py
输出：tools/test-scenes/previews/*.png
"""

import struct
import zlib
from pathlib import Path

# ---------------------------------------------------------------- PNG 编码
WHITE = (245, 245, 245)
BLACK = (20, 20, 20)
GRAY = (150, 150, 150)
STONE = (128, 128, 128)
STONE_TOP = (168, 168, 168)
AIR = (238, 244, 250)
HOLE = (60, 70, 90)
START = (60, 130, 240)
GOAL = (240, 200, 60)
ROUTE_A = (220, 70, 70)
ROUTE_B = (50, 170, 90)
GRID = (205, 205, 205)
PANEL = (255, 255, 255)
LAVA = (240, 120, 40)
WATER = (70, 140, 220)


class Canvas:
    def __init__(self, w, h, bg=PANEL):
        self.w = w
        self.h = h
        self.px = list(bg) * (w * h)  # flat rgb list（w*h*3 个分量）

    def _set(self, x, y, color):
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 3
            self.px[i] = color[0]
            self.px[i + 1] = color[1]
            self.px[i + 2] = color[2]

    def rect(self, x, y, w, h, color):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self._set(xx, yy, color)

    def frame(self, x, y, w, h, color, thickness=1):
        self.rect(x, y, w, thickness, color)
        self.rect(x, y + h - thickness, w, thickness, color)
        self.rect(x, y, thickness, h, color)
        self.rect(x + w - thickness, y, thickness, h, color)

    def line(self, x0, y0, x1, y1, color, thickness=2):
        """整数 Bresenham，带方形笔宽。"""
        dx = abs(x1 - x0)
        dy = abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx - dy
        while True:
            half = thickness // 2
            self.rect(x0 - half, y0 - half, thickness, thickness, color)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 > -dy:
                err -= dy
                x0 += sx
            if e2 < dx:
                err += dx
                y0 += sy

    def write(self, path):
        raw = bytearray()
        for y in range(self.h):
            raw.append(0)
            row = self.px[y * self.w * 3:(y + 1) * self.w * 3]
            raw.extend(bytes(row))

        def chunk(typ, data):
            return (struct.pack('>I', len(data)) + typ + data
                    + struct.pack('>I', zlib.crc32(typ + data) & 0xFFFFFFFF))

        ihdr = struct.pack('>IIBBBBB', self.w, self.h, 8, 2, 0, 0, 0)
        blob = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
                + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_bytes(blob)


# ---------------------------------------------------------------- 5x7 字模
F = {
    '0': ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    '1': ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    '2': ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    '3': ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    '4': ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    '5': ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    '6': ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    '7': ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    '8': ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    '9': ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    'A': ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    'B': ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    'C': ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    'D': ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    'E': ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    'F': ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    'G': ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    'H': ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    'I': ["01110", "00100", "00100", "00100", "00100", "00100", "01110"],
    'K': ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    'L': ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    'M': ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    'N': ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    'O': ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    'P': ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    'R': ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    'S': ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    'T': ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    'U': ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    'V': ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    'W': ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    'X': ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    'Y': ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    'Z': ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    ':': ["00000", "00100", "00100", "00000", "00100", "00100", "00000"],
    '-': ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    '.': ["00000", "00000", "00000", "00000", "00000", "00100", "00100"],
    ',': ["00000", "00000", "00000", "00000", "00100", "00100", "01000"],
    '(': ["00010", "00100", "01000", "01000", "01000", "00100", "00010"],
    ')': ["01000", "00100", "00010", "00010", "00010", "00100", "01000"],
    '>': ["10000", "01000", "00100", "00010", "00100", "01000", "10000"],
    '+': ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
    '=': ["00000", "00000", "11111", "00000", "11111", "00000", "00000"],
    ' ': ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}


def text(canvas, x, y, s, color=BLACK, scale=2):
    cx = x
    for ch in s.upper():
        glyph = F.get(ch, F[' '])
        for ry, row in enumerate(glyph):
            for rx, bit in enumerate(row):
                if bit == '1':
                    canvas.rect(cx + rx * scale, y + ry * scale, scale, scale, color)
        cx += 6 * scale
    return cx


# ---------------------------------------------------------------- 场景绘制
CELL = 34          # 每个方块在预览图里的像素尺寸
MARGIN = 24


def draw_top_view(c, ox, oy, xs, zs, floor_y, hole, start, goal, route_a, route_b):
    """俯视图：x 向右，z 向下。"""
    text(c, ox, oy - 26, "TOP VIEW X/Z", BLACK, 2)
    for i, x in enumerate(xs):
        for j, z in enumerate(zs):
            px = ox + i * CELL
            py = oy + j * CELL
            has_floor = not (x, z) in hole
            c.rect(px, py, CELL, CELL, STONE_TOP if has_floor else HOLE)
            c.frame(px, py, CELL, CELL, GRID, 1)
            if not has_floor:
                text(c, px + 11, py + 10, "H", WHITE, 1)
    # 坐标刻度
    for i, x in enumerate(xs):
        text(c, ox + i * CELL + 11, oy - 14, str(abs(x)), GRAY, 1)
    for j, z in enumerate(zs):
        text(c, ox - 16, oy + j * CELL + 12, str(z % 10), GRAY, 1)

    def center(p):
        return (ox + (p[0] - xs[0]) * CELL + CELL // 2,
                oy + (p[1] - zs[0]) * CELL + CELL // 2)

    # 路线
    for pts, color in ((route_a, ROUTE_A), (route_b, ROUTE_B)):
        for k in range(len(pts) - 1):
            x0, y0 = center(pts[k])
            x1, y1 = center(pts[k + 1])
            c.line(x0, y0, x1, y1, color, 4)

    sx, sy = center(start)
    c.rect(sx - 9, sy - 9, 18, 18, START)
    text(c, sx - 6, sy - 4, "S", WHITE, 1)
    gx, gy = center(goal)
    c.rect(gx - 9, gy - 9, 18, 18, GOAL)
    text(c, gx - 6, gy - 4, "G", BLACK, 1)


def draw_side_view(c, ox, oy, zs, ys, floor_y, hole, start, goal, route_a, route_b):
    """侧视图：z 向右，y 向上（脚位坐标系）。"""
    text(c, ox, oy - 26, "SIDE VIEW Z/Y", BLACK, 2)
    for j, z in enumerate(zs):
        for i, y in enumerate(ys):
            px = ox + j * CELL
            py = oy + (len(ys) - 1 - i) * CELL
            is_support = (y == floor_y) and not (0, z) in hole
            is_bottom = (y == floor_y - 1)
            color = STONE if (is_support or is_bottom) else AIR
            c.rect(px, py, CELL, CELL, color)
            c.frame(px, py, CELL, CELL, GRID, 1)
    for j, z in enumerate(zs):
        text(c, ox + j * CELL + 11, oy + len(ys) * CELL + 4, str(z % 10), GRAY, 1)
    for i, y in enumerate(ys):
        text(c, ox - 18, oy + (len(ys) - 1 - i) * CELL + 12, str(y % 10), GRAY, 1)

    def center(p):
        # p = (z, foot_y)
        return (ox + (p[0] - zs[0]) * CELL + CELL // 2,
                oy + (len(ys) - 1 - (p[1] - ys[0])) * CELL + CELL // 2)

    for pts, color in ((route_a, ROUTE_A), (route_b, ROUTE_B)):
        for k in range(len(pts) - 1):
            x0, y0 = center(pts[k])
            x1, y1 = center(pts[k + 1])
            c.line(x0, y0, x1, y1, color, 4)

    for p, color, label, fg in ((start, START, "S", WHITE), (goal, GOAL, "G", BLACK)):
        px, py = center(p)
        c.rect(px - 9, py - 9, 18, 18, color)
        text(c, px - 6, py - 4, label, fg, 1)


def legend(c, x, y, entries):
    cx = x
    for color, label in entries:
        c.rect(cx, y, 14, 14, color)
        c.frame(cx, y, 14, 14, BLACK, 1)
        cx = text(c, cx + 20, y + 2, label, BLACK, 1) + 14


def dip_course():
    """Q7 验收场景：1 格深坑 vs 同层绕路（路线偏好翻转）。"""
    xs = list(range(-3, 4))            # -3..3
    zs = list(range(60, 71))           # 60..70
    floor_y = 63                       # 支撑层（脚位 64）
    hole = {(x, 65) for x in range(-1, 3)}   # x=-1..2、z=65 的 1 格深坑
    start = (0, 66)
    goal = (-1, 63)
    route_a = [(0, 66), (0, 65), (0, 64), (-1, 63)]              # 旧模型：下降+上升+对角
    route_b = [(0, 66), (-1, 66), (-2, 65), (-1, 64), (-1, 63)]  # 标定后：西侧绕路

    w = MARGIN * 2 + len(xs) * CELL + 40 + len(zs) * CELL
    h = MARGIN * 2 + 40 + len(zs) * CELL + 60
    c = Canvas(w, h)
    text(c, MARGIN, 8, "DIP COURSE - ROUTE PREFERENCE (Q7)", BLACK, 2)
    draw_top_view(c, MARGIN, MARGIN + 30, xs, zs, floor_y, hole, start, goal, route_a, route_b)
    side_x = MARGIN + len(xs) * CELL + 40
    ys = [62, 63, 64, 65]
    draw_side_view(c, side_x, MARGIN + 30, zs, ys, floor_y, hole,
                   (66, 64), (63, 64), [(66, 64), (65, 63), (64, 64), (63, 64)],
                   [(66, 64), (66, 64), (65, 64), (64, 64), (63, 64)])
    legend(c, MARGIN, h - 26, [
        (STONE_TOP, "STONE FLOOR"), (HOLE, "HOLE 1X1"),
        (START, "START (0,64,66)"), (GOAL, "GOAL (-1,64,63)"),
        (ROUTE_A, "OLD: DESCEND+ASCEND+DIAG 34 TICK"), (ROUTE_B, "NEW: WEST DETOUR 28 TICK"),
    ])
    return c


def fluid_course_preview():
    """已验收场景（D-037）示意：2 格高充水墙。"""
    xs = list(range(-3, 8))
    zs = list(range(62, 71))
    start = (0, 66)
    goal = (4, 66)
    wall_x = 2
    w = MARGIN * 2 + len(xs) * CELL + 40
    h = MARGIN * 2 + 40 + len(zs) * CELL + 60
    c = Canvas(w, h)
    text(c, MARGIN, 8, "FLUID COURSE - WATERLOGGED WALL (D-037)", BLACK, 2)
    ox, oy = MARGIN, MARGIN + 30
    for i, x in enumerate(xs):
        for j, z in enumerate(zs):
            px, py = ox + i * CELL, oy + j * CELL
            color = WATER if x == wall_x else (STONE_TOP if -3 <= x <= 6 else AIR)
            c.rect(px, py, CELL, CELL, color)
            c.frame(px, py, CELL, CELL, GRID, 1)
    for j, z in enumerate(zs):
        text(c, ox - 16, oy + j * CELL + 12, str(z % 10), GRAY, 1)

    def center(p):
        return (ox + (p[0] - xs[0]) * CELL + CELL // 2, oy + (p[1] - zs[0]) * CELL + CELL // 2)

    sx, sy = center(start)
    c.rect(sx - 9, sy - 9, 18, 18, START)
    text(c, sx - 6, sy - 4, "S", WHITE, 1)
    gx, gy = center(goal)
    c.rect(gx - 9, gy - 9, 18, 18, GOAL)
    text(c, gx - 6, gy - 4, "G", BLACK, 1)
    legend(c, MARGIN, h - 26, [
        (STONE_TOP, "STONE FLOOR"), (WATER, "WATERLOGGED WALL 2 HIGH"),
        (START, "START (0,64,66)"), (GOAL, "GOAL (4,64,66)"),
    ])
    return c


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "tools" / "test-scenes" / "previews"
    dip_course().write(out / "dip_course.png")
    fluid_course_preview().write(out / "fluid_course.png")
    print("wrote", out / "dip_course.png")
    print("wrote", out / "fluid_course.png")
