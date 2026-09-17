#!/usr/bin/env python3
"""S-P1（队列第①项 / survey 14 §11-1 + survey 15 §3.6，2026-09-17）：**站点映射两处必须一致**。

事实：站点映射活在**两个不同输入域**里 ——
① `decision/RecipeDump.java` 的 `STATION_BY_TYPE`：**运行时** `recipe.getType()` 给的**类型 id**（如 `minecraft:crafting`）
   ＋ **兼容行**（数据包直接用序列化器 id 注册时也能命中）；
② `tools/recipe-graph.py` 的 `STATION_BY_TYPE`：解析**原版数据 JSON** 时用的**序列化器 id**（如 `minecraft:crafting_shaped`）。
两者服务不同输入 ⇒ **不该合并**，但必须**锁死一致**：否则导出与图谱会对"这条配方属于哪个站点"各说各话
（D-183 的教训：把序列化器 id 当类型 id ⇒ 2136 条工作台配方被"如实跳过"、可读率掉到 15%）。

本规则：**Python 那张表必须是 Java 那张表的子集**（键与站点名**都要**相同）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision" / "RecipeDump.java"
PY_TOOL = ROOT / "tools" / "recipe-graph.py"

ENTRY = re.compile(r'Map\.entry\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)')
PY_ENTRY = re.compile(r'^\s*"([^"]+)"\s*:\s*"([^"]+)"\s*,?\s*$', re.MULTILINE)


def java_pairs() -> dict[str, str]:
    text = JAVA.read_text(encoding="utf-8")
    start = text.index("STATION_BY_TYPE")
    end = text.index("private RecipeDump()", start)
    return {k: v for k, v in ENTRY.findall(text[start:end])}


def python_pairs() -> dict[str, str]:
    text = PY_TOOL.read_text(encoding="utf-8")
    start = text.index("STATION_BY_TYPE")
    end = text.index("}", start)
    return {k: v for k, v in PY_ENTRY.findall(text[start:end])}


def main() -> int:
    j, p = java_pairs(), python_pairs()
    problems = []
    if len(j) < 5:
        problems.append(f"Java 站点表只解析出 {len(j)} 条（表改了写法？同步本规则）")
    if len(p) < 5:
        problems.append(f"Python 站点表只解析出 {len(p)} 条（表改了写法？同步本规则）")
    for key, station in sorted(p.items()):
        if key not in j:
            problems.append(f"Python 有 `{key}`→`{station}`，Java **没有**这个键 ⇒ 图谱与导出会对不上")
        elif j[key] != station:
            problems.append(f"`{key}` 的站点不一致：Python=`{station}` vs Java=`{j[key]}`")
    for line in problems:
        print(f"[S·站点映射] {line}")
    only_java = sorted(set(j) - set(p))
    ok = not problems
    print(f"STATION_MAPPING_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"Java={len(j)} 条 / Python={len(p)} 条 / 仅 Java 有={only_java}"
          f"（S-P1 = Python 表必须是 Java 表的子集；仅 Java 有的通常是运行时类型 id，属正常）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
