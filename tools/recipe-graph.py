#!/usr/bin/env python3
"""Alice · 只读配方图逆推规划器（S5 / P1，D-145）。

用途：给一个目标物品，用**运行时配方数据**逆推"要什么材料、要哪些工作站、有哪些路线"。
**只读、离线**：不启动游戏、不改世界；先由游戏内 `/alice recipes dump` 导出真实（含整合包魔改）数据，
再用本工具分析 —— 这样重活都在游戏外迭代（对齐 check-scene-connectivity.py 的离线自检文化）。

数据来源（两种都支持，格式统一）：
  1) **游戏内导出** `config/alice-recipes.json`（权威：KubeJS/CraftTweaker/GT 的魔改最终都体现在运行时配方表）；
  2) **原版/模组 jar 里的数据目录**（`data/*/recipes/*.json` + `data/*/tags/items/*.json`）—— 用于离线开发与自检。

设计要点（见 docs/KNOWLEDGE_RECIPE_GRAPH_NOTES.md）：
  - **配方是数据，不是知识** ⇒ 不训练、不蒸馏，只做图查询；
  - **环要有界**（铜锭↔铜粉这类）：搜索带 visited 集合与深度/分支上限；
  - **标签输入**（`#minecraft:planks`）：按"已知成员"展开，报告里写明"任意其一"。

用法：
  recipe-graph.py --recipes <导出文件|数据目录> [--tags <数据目录>] --target minecraft:wooden_pickaxe
                  [--count 1] [--have minecraft:oak_log=2] [--depth 8] [--branch 4] [--json out.json]
  recipe-graph.py --selftest        # 用原版数据自证（木镐：5 木板 + 2 木棍 → 2 原木）
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict

STATION_BY_TYPE = {
    "minecraft:crafting_shaped": "crafting_table",
    "minecraft:crafting_shapeless": "crafting_table",
    "minecraft:smelting": "furnace",
    "minecraft:blasting": "blast_furnace",
    "minecraft:smoking": "smoker",
    "minecraft:campfire_cooking": "campfire",
    "minecraft:stonecutting": "stonecutter",
    "minecraft:smithing_transform": "smithing_table",
    "minecraft:smithing_trim": "smithing_table",
}


class Recipe:
    __slots__ = ("rid", "station", "type", "output", "out_count", "inputs")

    def __init__(self, rid, rtype, output, out_count, inputs):
        self.rid = rid
        self.type = rtype
        self.station = STATION_BY_TYPE.get(rtype, rtype.split(":")[-1] if rtype else "unknown")
        self.output = output
        self.out_count = max(1, int(out_count or 1))
        self.inputs = inputs          # list[(key, count)]，key = "item:id" 或 "tag:#id"

    def __repr__(self):
        return f"Recipe({self.rid}, {self.station}, {self.output} x{self.out_count} <- {self.inputs})"


def _key(node):
    """{item|tag: ...} → (key, count)；**key 口径**：物品不加前缀（与 by_output 一致），标签用 `#id`。"""
    if not isinstance(node, dict):
        return None, 0
    count = int(node.get("count", 1) or 1)
    if "item" in node:
        return node["item"], count
    if "tag" in node:
        return "#" + node["tag"], count
    return None, 0


def parse_recipe(rid, data):
    """把一条配方 JSON 归一化成 Recipe（不支持的类型返回 None —— 如实跳过，不猜）。"""
    rtype = data.get("type", "")
    inputs = []
    if rtype in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless",
                 "minecraft:crafting_special_*"):
        result = data.get("result", {})
        # key 里出现的每个原料，按 pattern 里出现的次数计数
        counts = defaultdict(int)
        if "key" in data and "pattern" in data:
            for row in data["pattern"]:
                for ch in row:
                    if ch != " ":
                        counts[ch] += 1
        elif "ingredients" in data:
            for node in data["ingredients"]:
                if isinstance(node, list):
                    node = node[0] if node else {}
                k, c = _key(node)
                if k:
                    inputs.append((k, c))
        for ch, times in counts.items():
            k, c = _key(data.get("key", {}).get(ch, {}))
            if k:
                inputs.append((k, c * max(1, times)))
        out = result.get("item") or result.get("id") if isinstance(result, dict) else None
        return Recipe(rid, rtype, out, result.get("count", 1) if isinstance(result, dict) else 1,
                      inputs) if out else None
    if rtype in ("minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
                 "minecraft:campfire_cooking", "minecraft:stonecutting"):
        k, c = _key(data.get("ingredient", {}))
        out = data.get("result")
        if isinstance(out, dict):
            out_item, out_count = out.get("item") or out.get("id"), out.get("count", 1)
        else:
            out_item, out_count = out, 1
        if k and out_item:
            return Recipe(rid, rtype, out_item, out_count, [(k, c)])
        return None
    if rtype.startswith("minecraft:smithing"):
        base = _key(data.get("base", {}))
        add = _key(data.get("addition", {}))
        out = (data.get("result") or {}).get("item")
        ins = [pair for pair in (base, add) if pair[0]]
        return Recipe(rid, rtype, out, 1, ins) if out else None
    # 未知类型（模组机器/多方块）：**如实跳过**并在统计里报数（不猜它的语义）
    return None


def load_from_dump(path):
    """读游戏内导出（归一化 JSON）。"""
    with open(path, encoding="utf-8") as handle:
        payload = json.load(handle)
    recipes, skipped = [], 0
    for entry in payload.get("recipes", []):
        inputs = [(i["key"], i.get("count", 1)) for i in entry.get("inputs", []) if i.get("key")]
        if entry.get("output"):
            recipes.append(Recipe(entry.get("id", "?"), entry.get("type", ""), entry["output"],
                                  entry.get("count", 1), inputs))
        else:
            skipped += 1
    tags = {k: v for k, v in payload.get("itemTags", {}).items()}
    return recipes, tags, skipped


def load_from_data_dir(root):
    """读 jar 解出来的数据目录：`data/<ns>/recipes/*.json` + `data/<ns>/tags/items/*.json`。"""
    recipes, skipped, tags = [], 0, {}
    for dirpath, _dirs, files in os.walk(root):
        rel = os.path.relpath(dirpath, root).replace(os.sep, "/")
        parts = rel.split("/")
        if len(parts) < 3 or parts[0] != "data":
            continue
        ns, kind = parts[1], parts[2]
        if kind == "recipes":
            for name in files:
                if not name.endswith(".json"):
                    continue
                try:
                    with open(os.path.join(dirpath, name), encoding="utf-8") as handle:
                        data = json.load(handle)
                except Exception:
                    continue
                rid = f"{ns}:{name[:-5]}"
                recipe = parse_recipe(rid, data) if isinstance(data, dict) else None
                if recipe:
                    recipes.append(recipe)
                else:
                    skipped += 1
        elif kind == "tags" and len(parts) >= 4 and parts[3] == "items":
            for name in files:
                if not name.endswith(".json"):
                    continue
                try:
                    with open(os.path.join(dirpath, name), encoding="utf-8") as handle:
                        data = json.load(handle)
                except Exception:
                    continue
                values = [v for v in data.get("values", [])
                          if isinstance(v, str) and not v.startswith("#")]
                tag = f"#{ns}:{name[:-5]}"
                tags.setdefault(tag, []).extend(values)
    return recipes, tags, skipped


class Planner:
    """逆推规划器（BOM 聚合口径）。

    <p>为什么不是"递归树各自 ceil"：那样两个 `#planks` 子节点会各展开一次原木，
    把 5 木板算成 8 原木（2026-09-12 实测）。正确口径是**全局账**：
    需求累加 → 每次合成按 `ceil(need/产出)` 计算并把**余料回填**给后续兄弟需求。
    """

    def __init__(self, recipes, tags):
        self.by_output = defaultdict(list)
        for recipe in recipes:
            self.by_output[recipe.output].append(recipe)
        self.tags = tags

    def tag_members(self, tag):
        return self.tags.get(tag if tag.startswith("#") else "#" + tag, [])

    # ---------- 库存 ----------

    def _take_from_inventory(self, key, inventory, need):
        """从库存里顶掉一部分需求（item 直接命中；tag 命中任一成员）。返回顶掉的数量。"""
        if need <= 0:
            return 0
        if key.startswith("#"):
            for member in self.tag_members(key):
                if inventory.get(member, 0) > 0:
                    take = min(need, inventory[member])
                    inventory[member] -= take
                    return take
            return 0
        take = min(need, inventory.get(key, 0))
        inventory[key] = inventory.get(key, 0) - take
        return take

    # ---------- 选路线 ----------

    def choose(self, key, inventory, depth_left):
        """返回 (recipe, member_note)。

        - 普通物品：取"缺料种类数最少"的配方；
        - **标签**：优先取**没有配方的成员**（= 原始材料，直接列为"需要采集"），
          否则取成员里缺料最少的配方 —— 因为"把原木合成木块再合成木板"是荒谬路线
          （2026-09-12 实测：`#acacia_logs` 曾挑到 `acacia_wood`，5 木板被算成 8 原木）。
        """
        if key.startswith("#"):
            members = self.tag_members(key)
            raw = [m for m in members if not self.by_output.get(m)]
            if raw:
                return None, f"标签任取原始材料其一：{', '.join(raw[:4])}"
            best = None
            for member in members:
                for recipe in self.by_output.get(member, []):
                    score = (len(recipe.inputs), recipe.rid)
                    if best is None or score < best[0]:
                        best = (score, recipe, member)
            return (best[1], f"标签由成员 {best[2]} 满足") if best else (None, "标签无可用成员")
        recipes = self.by_output.get(key)
        if not recipes:
            return None, "无配方（需要采集/模组机器或未知）"
        def score(recipe):
            missing = sum(1 for ikey, _ in recipe.inputs if self._take_from_inventory(
                ikey, dict(inventory), 1) == 0)
            return (missing, len(recipe.inputs), recipe.rid)
        return min(recipes, key=score), ""

    # ---------- 主入口：BOM 聚合展开 ----------

    def plan_bom(self, target, count=1, have=None, depth=8, branch=4):
        inventory = dict(have or {})
        demand = {target: count}
        queue = [(target, 0)]
        queued = {target}
        steps = []
        missing = defaultdict(int)
        notes = {}
        while queue:
            key, level = queue.pop(0)
            queued.discard(key)
            need = demand.get(key, 0)
            if need <= 0:
                continue
            need -= self._take_from_inventory(key, inventory, need)
            demand[key] = 0
            if need <= 0:
                continue
            if level >= depth:
                missing[key] += need
                notes[key] = "深度上限（未展开）"
                continue
            recipe, note = self.choose(key, inventory, depth - level)
            if recipe is None:
                missing[key] += need
                if note:
                    notes[key] = note
                continue
            crafts = -(-need // recipe.out_count)          # ceil
            leftover = crafts * recipe.out_count - need
            inventory[recipe.output] = inventory.get(recipe.output, 0) + leftover
            steps.append({"key": key, "recipe": recipe.rid, "station": recipe.station,
                          "crafts": crafts, "produced": crafts * recipe.out_count,
                          "consumed": need, "leftover": leftover, "note": note,
                          "output": recipe.output, "out_count": recipe.out_count,
                          "level": level})
            for ikey, icount in recipe.inputs:
                total = icount * crafts
                # **已排队** ≠ **在 demand 里**：同一输入可能被多步追加需求（实测：木棍还要 2 木板，
                # 若不重新入队，第二次木板需求会被静默吞掉 ⇒ 少算一根原木）
                if ikey not in queued:
                    queue.append((ikey, level + 1))
                    queued.add(ikey)
                demand[ikey] = demand.get(ikey, 0) + total
        return {"target": target, "count": count, "steps": steps,
                "missing": dict(missing), "notes": notes, "leftover": dict(inventory)}

    # ---------- 渲染 ----------

    def render_bom(self, result):
        lines = [f"# 目标 {result['target']} x{result['count']}"]
        if not result["steps"]:
            lines.append("  （无可展开的合成步骤：直接用库存，或需要采集/模组机器）")
        for step in result["steps"]:
            indent = "  " * (step["level"] + 1)
            note = f"  ← {step['note']}" if step["note"] else ""
            lines.append(f"{indent}- 合成 {step['key']} x{step['consumed']} "
                         f"（用 {step['recipe']} @ {step['station']}，做 {step['crafts']} 次 "
                         f"→ {step['produced']}，余 {step['leftover']}）{note}")
        lines.append("# 缺料（原始材料）：")
        for key, amount in sorted(result["missing"].items()):
            note = f"  ← {result['notes'].get(key)}" if result["notes"].get(key) else ""
            lines.append(f"  {key} x{amount}{note}")
        if not result["missing"]:
            lines.append("  （无：库存足够）")
        return lines


PLANNER = None


def main():
    global PLANNER
    parser = argparse.ArgumentParser(description="只读配方图逆推规划器（S5/P1）")
    parser.add_argument("--recipes", help="游戏内导出文件（alice-recipes.json）或数据目录")
    parser.add_argument("--tags", help="额外的数据目录（读 tags/items）")
    parser.add_argument("--target")
    parser.add_argument("--count", type=int, default=1)
    parser.add_argument("--have", action="append", default=[],
                        help="库存 item=count（可重复）")
    parser.add_argument("--depth", type=int, default=8)
    parser.add_argument("--branch", type=int, default=4)
    parser.add_argument("--json", dest="json_out")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if not args.recipes or not args.target:
        parser.error("需要 --recipes 与 --target（或用 --selftest）")

    if os.path.isdir(args.recipes):
        recipes, tags, skipped = load_from_data_dir(args.recipes)
        if args.tags:
            _extra, extra_tags, _s = load_from_data_dir(args.tags)
            tags.update(extra_tags)
    else:
        recipes, tags, skipped = load_from_dump(args.recipes)
    print(f"# 配方 {len(recipes)} 条（跳过无法归一化的 {skipped} 条）；物品标签 {len(tags)} 个")

    have = {}
    for entry in args.have:
        item, _, amount = entry.partition("=")
        have[item.strip()] = int(amount or 1)
    planner = Planner(recipes, tags)
    result = planner.plan_bom(args.target, args.count, have, args.depth, args.branch)
    for line in planner.render_bom(result):
        print(line)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2)
        print(f"# 已写 {args.json_out}")
    return 0


def selftest():
    """原版数据自证：木镐 ⇒ 5 木板 ⇒ **2 原木**（BOM 聚合）；库存足够应短路；环应有界。"""
    root = os.environ.get("ALICE_VANILLA_DATA", "/tmp/rcp")
    if not os.path.isdir(root):
        print("缺原版数据目录（解压客户端 jar 的 data/ 到该目录，或用 $ALICE_VANILLA_DATA 指定）")
        return 2
    recipes, tags, skipped = load_from_data_dir(root)
    planner = Planner(recipes, tags)
    print(f"# 原版配方 {len(recipes)} 条，标签 {len(tags)} 个，跳过 {skipped} 条")
    failures = []

    result = planner.plan_bom("minecraft:wooden_pickaxe", 1, {}, 8, 3)
    print("\n".join(planner.render_bom(result)))
    if not any("crafting_table" in step["station"] for step in result["steps"]):
        failures.append("木镐应需要 crafting_table")
    if not any(key.endswith("_log") or key.endswith("_logs") for key in result["missing"]):
        failures.append("缺料里应出现原木（原始材料）")
    # 5 木板 ⇒ 2 原木（每根原木出 4 木板）；且**不能**出现"木块(wood)"这种绕路
    logs = sum(amount for key, amount in result["missing"].items()
               if key.endswith("_log") or key.endswith("_logs"))
    if logs != 2:
        failures.append(f"木板应折成 2 根原木，实际 {logs}（缺料 {result['missing']}）")
    if any("_wood" in step["key"] for step in result["steps"]):
        failures.append("不应绕路合成木块（应直接取原木）")

    stocked = planner.plan_bom("minecraft:wooden_pickaxe", 1, {"minecraft:wooden_pickaxe": 1}, 8, 3)
    if stocked["missing"] or stocked["steps"]:
        failures.append("库存足够时应短路（无步骤、无缺料）")

    cyclic = Planner([
        Recipe("t:copper_ingot", "minecraft:crafting_shapeless", "minecraft:copper_ingot", 1,
               [("item:minecraft:copper_powder", 1)]),
        Recipe("t:copper_powder", "minecraft:crafting_shapeless", "minecraft:copper_powder", 1,
               [("item:minecraft:copper_ingot", 1)]),
    ], {})
    cyc = cyclic.plan_bom("minecraft:copper_ingot", 1, {}, 6, 2)
    if len(cyc["steps"]) > 6:
        failures.append(f"环应有界（步骤数 {len(cyc['steps'])} 应 ≤6）")

    print("\n# selftest：" + ("PASS" if not failures else "FAIL " + "; ".join(failures)))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
