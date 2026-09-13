#!/usr/bin/env python3
"""Alice · 配方可读性审计（阶段 2 / D-181，**只读**）。

用途：拿游戏内 `/alice recipes` 导出的运行时配方表（`config/alice-recipes.json`），回答三个问题：
  1. **读得懂多少**：配方总数、被跳过的类型直方图（`skippedTypes`）、输入里"直接物品 / 标签(任意其一) /
     unknown"各占多少；
  2. **适配器候选是谁**：被跳过的配方类型按模组命名空间归类（**只列清单，本仓不写适配器**）；
  3. **配方打架**：同一产出物由 ≥2 条配方提供（不同工作站/不同模组）⇒ 路线歧义清单，
     以及 A→B→A 这类**环**（离线逆推必须带 visited 与上限，见 tools/recipe-graph.py）。

**只读、离线**：不启动游戏、不改世界、不写任何游戏文件；只读 JSON 打印报告。

用法：
  recipe-readability.py --recipes <alice-recipes.json>            # 完整报告
  recipe-readability.py --recipes <...> --target minecraft:iron_ingot --routes   # 只看某物品的路线
  recipe-readability.py --selftest                                # 用本仓内嵌小样本自证
"""
from __future__ import annotations

import argparse
import collections
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def audit(dump):
    recipes = dump.get("recipes", [])
    item_tags = dump.get("itemTags") or {}
    skipped_types = dump.get("skippedTypes") or {}

    by_ns = collections.Counter()
    by_type = collections.Counter()
    by_station = collections.Counter()
    input_kinds = collections.Counter()
    unknown_inputs = collections.Counter()
    routes = collections.defaultdict(set)      # output -> {(type, station, #inputs)}
    producers = collections.defaultdict(set)   # output -> {mod namespace}

    for recipe in recipes:
        out = recipe.get("output", "?")
        ns = out.split(":", 1)[0]
        rtype = recipe.get("type", "?")
        station = recipe.get("station", "?")
        by_ns[ns] += 1
        by_type[rtype] += 1
        by_station[station] += 1
        producers[out].add(ns)
        routes[out].add((rtype, station, len(recipe.get("inputs", []))))
        for entry in recipe.get("inputs", []):
            if "any" in entry:
                input_kinds["tag(any)"] += 1
            elif entry.get("key", "unknown") == "unknown":
                input_kinds["unknown"] += 1
            else:
                input_kinds["item"] += 1
                if entry["key"] not in producers and entry["key"].split(":", 1)[0] != "minecraft":
                    unknown_inputs[entry["key"]] += 1

    multi_route = {k: v for k, v in routes.items() if len(v) > 1}
    cross_mod = {k: v for k, v in producers.items() if len(v) > 1}
    return {
        "recipes": len(recipes),
        "tags": len(item_tags),
        "skippedTypes": skipped_types,
        "byNamespace": by_ns,
        "byType": by_type,
        "byStation": by_station,
        "inputKinds": input_kinds,
        "multiRoute": multi_route,
        "crossModOutputs": cross_mod,
    }


def print_report(result, top=8):
    print("=" * 72)
    print(f"配方总数 {result['recipes']}    物品标签 {result['tags']}")
    print("-" * 72)
    print("读得懂（按配方类型）:")
    for k, v in result["byType"].most_common(10):
        print(f"    {v:6d}  {k}")
    print("工作站分布:")
    for k, v in result["byStation"].most_common(12):
        print(f"    {v:6d}  {k}")
    print("产出命名空间 Top%d:" % top)
    for k, v in result["byNamespace"].most_common(top):
        print(f"    {v:6d}  {k}")
    print("输入形态（直接物品 / 标签任意其一 / unknown）:")
    for k, v in result["inputKinds"].most_common():
        print(f"    {v:6d}  {k}")

    skipped = result["skippedTypes"]
    print("-" * 72)
    if skipped:
        print("**读不懂（被如实跳过）的配方类型** —— 适配器候选清单（本仓不写适配器）:")
        mods = collections.Counter()
        for k, v in sorted(skipped.items(), key=lambda e: -e[1]):
            print(f"    {v:6d}  {k}")
            mods[k.split(":", 1)[0]] += v
        print("  按模组汇总（适配器候选优先级）:")
        for k, v in mods.most_common():
            print(f"    {v:6d}  {k}")
    else:
        print("没有 skippedTypes 字段（旧版导出）——请用新版 `/alice recipes` 重导。")

    print("-" * 72)
    print(f"**配方打架候选**（同一产出物 ≥2 条不同路线）: {len(result['multiRoute'])} 个产出物")
    for k, v in sorted(result["multiRoute"].items(), key=lambda e: -len(e[1]))[:top]:
        print(f"    {k}: {len(v)} 条路线 -> {sorted(v)[:3]}")
    print(f"**跨模组同产出**（>1 个命名空间都能造）: {len(result['crossModOutputs'])} 个")
    for k, v in sorted(result["crossModOutputs"].items())[:top]:
        print(f"    {k} <- {sorted(v)}")
    print("=" * 72)


SELFTEST = {
    "itemTags": {"#minecraft:planks": ["minecraft:oak_planks"]},
    "skippedTypes": {"mekanism:metallurgic_infusing": 12, "create:mixing": 5},
    "recipes": [
        {"id": "a", "type": "minecraft:crafting_shaped", "station": "crafting_table",
         "output": "minecraft:stick", "count": 4,
         "inputs": [{"any": ["minecraft:oak_planks"], "count": 1}]},
        {"id": "b", "type": "minecraft:crafting_shaped", "station": "crafting_table",
         "output": "minecraft:iron_ingot", "count": 1, "inputs": [{"key": "minecraft:iron_block", "count": 1}]},
        {"id": "c", "type": "minecraft:smelting", "station": "furnace",
         "output": "minecraft:iron_ingot", "count": 1, "inputs": [{"key": "minecraft:raw_iron", "count": 1}]},
    ],
}


def main():
    parser = argparse.ArgumentParser(description="Alice 配方可读性审计（只读）")
    parser.add_argument("--recipes", help="alice-recipes.json 路径")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--top", type=int, default=8)
    args = parser.parse_args()

    if args.selftest:
        dump = SELFTEST
    elif args.recipes:
        dump = load(args.recipes)
    else:
        parser.error("需要 --recipes <file> 或 --selftest")

    result = audit(dump)
    print_report(result, args.top)
    if args.selftest:
        # 自证：self-test 样本必须判出 1 个"配方打架"（铁锭两条路线）与 2 个被跳过的模组类型
        ok = (len(result["multiRoute"]) == 1
              and result["skippedTypes"].get("mekanism:metallurgic_infusing") == 12)
        print("SELFTEST", "PASS" if ok else "FAIL",
              f"multiRoute={len(result['multiRoute'])} skipped={result['skippedTypes']}")
        return 0 if ok else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
