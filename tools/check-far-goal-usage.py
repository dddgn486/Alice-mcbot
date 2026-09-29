#!/usr/bin/env python3
r"""⭐ **粗目标分类门禁**（红线 `D-132` 的牙；`D-500` §V：**点名白名单 → 分类判据**，2026-09-29 落地）。

## 为什么这条规则该存在

`D-337` 实测（不是推测）：`GoalNearXZ` 是"粗目标（只认 XZ 列 + 半径）"，它的到达判断
**纯算术、不读世界** ⇒ 内核那条"目标未加载就拒"的前置守卫**对它不生效**
⇒ **A\* 扩展时会读未加载区块的方块，把它们同步加载进来**（`far_path_bench` 沿路采样实证：
采样前只有 192 是 true，采样后 192/224/256/288/320/352/384 **全变 true**）。

⇒ 远距离的**正确形状**（`D-337` 附注二）= **一跳一跳逼近**：`FarTravelHop` 先用 `hasChunkAt`
采样已加载前沿，把目标**夹到边界内侧**再按 `GoalNearXZ` 规划（新区块由 bot 自己的移动自然加载
= 合法的世界推进）。

## ⛔ 旧口径的洞（这就是 `D-500` §V 要改它的原因）

旧版是**点名白名单**：只抓字面 `GoalNearXZ.around(`。
⇒ ⭐ **一个新写的粗目标类能从它下面走过去** —— 名字里没有那个字符串，门禁看不见。
> `§12.1.2` 逐字：「`check-far-goal-usage.sh` **只按字面 `GoalNearXZ.around(` 抓**
> ⇒ ⭐ **一个新类能从它下面走过去**（它是"**点名白名单**"，不是"禁止粗目标"）」

## ✅ 新口径 = **分类判据**（`exactFoot() == false` 的实现**必须登记**）

判据的**唯一开关**是 `GoalSpec.exactFoot()`：`AStarMovementSearch` 里
`if (goal.exactFoot() && !context.chunkLoaded(goal.goalFoot()))` ⇒ `false` 的实现
**整条前置守卫被跳过**。所以：

> ⭐ **凡 `exactFoot()` 返回 `false` 的实现，必须在下面的 `FAR_GOAL_REGISTRY` 里登记；
> 且登记表要**同时记录它的取值**（`exact_foot`），取值与实际不符 ⇒ 红。**

⚠️ **判据为什么不是"别读世界"**（`A-5` 的勘误，2026-09-28）：
初版曾想加"`isInGoal` 不读世界"作为**合取项**，读码后确认那是**假信号** ——
任何 A\* 扩展都会读周围几格，风险与 `isInGoal` 无关。
`D-132` 有**三层防线**：① 本门禁管的这条 `:70` 守卫（只对精确目标生效，作用是"目标区没加载就早退"）
→ ② ⭐ **读脚印闸门**（`MovementContext.READ_FOOTPRINT_RADIUS`，在**读之前**判断 —— **真防线**）
→ ③ `AStarMovementSearch` 扩展处的 `skippedUnloaded` 检查（**后置，拦不住读**，已降级为见证）。
⇒ 用 `exactFoot()==false` 的**正确理由**：**粗目标没有唯一目标区块可预检**（所以第 1 道必然跳过）
＋ **它天生要朝未加载区推进**（这正是 `GoalNearXZ` 存在的理由）。

## 断言（任一不成立 ⇒ 非零退出）

1. **人口下限**：扫到的 `GoalSpec` 实现 ≥ `MIN_IMPLS` —— 防"解析写坏 ⇒ 0 个实现 ⇒ 永远绿"。
2. ⭐ **未登记的 `exactFoot()==false` 实现 ⇒ 红**（列出文件）。
3. ⭐ **登记条目必须仍然命中**：文件在、确实是 `GoalSpec` 实现、且 `exact_foot` 取值**逐字相符**
   —— 条目"得手"（不再成立）⇒ 红（逼改名/改取值时同步改登记表）。
4. **登记条目必须带理由**（≥ `MIN_REASON` 字符 ⇒ 挡 `# TODO` 占位符）。
5. ⭐ **认不出来的 `exactFoot()` 体 ⇒ 红**（不许把"解析不了"当"默认 true"放绿）。
6. （**第二颗牙，保留**）生产代码里出现字面 `GoalNearXZ.around(` ⇒ 红。
   ⚠️ 它与断言 2 是**互补的两面**：2 管**实现**（新类绕不过去），6 管**调用点**
   （谁在生产路径上真的构造了一个粗目标）。⇒ 保留 6 **比裁定更强，不是更弱**（`D-517` 已登记）。

## 跑法

`python3 tools/check-far-goal-usage.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src/main/java"

# ---- 参数 ----
MIN_IMPLS = 3      # 实测 3：GoalFoot / GoalNearXZ / GoalAdjacent
MIN_REASON = 8     # 挡占位符

# ==================== 登记表（`D-500` §V：粗目标实现必须在这里）====================
# ⚠️ 键 = 相对 `src/main/java/` 的路径。`exact_foot` 必须与代码里的取值**逐字相符**。
FAR_GOAL_REGISTRY: dict[str, dict] = {
    "com/dddgn/alice/pathing/core/search/GoalNearXZ.java": {
        "exact_foot": False,
        "reason": "粗目标（`D-337`，用户 2026-09-19 选 A）：到达 = 进 XZ 半径，**纯算术、不读方块** "
                  "⇒ 前置守卫对它不适用。⚠️ 它是「远距离」的**合法**形状，但必须**只经** `FarTravelHop` "
                  "夹到已加载边界内侧再规划（`D-337` 附注二），⛔ 不许在生产路径上直接构造。",
    },
    "com/dddgn/alice/pathing/core/search/GoalAdjacent.java": {
        "exact_foot": False,
        "reason": "相邻目标（`K2` 第一刀 ＋ `1a`=甲，`D-517`）：到达 = 与目标方块曼哈顿相邻，**纯算术** "
                  "⇒ 同上。⚠️ 它**没有唯一目标区块可预检**（候选脚位最多 5 个）⇒ 第 1 道守卫必然跳过 "
                  "⇒ 安全依赖**第 2 道读脚印闸门**（`READ_FOOTPRINT_RADIUS`）。"
                  "⛔ 本实现今天**不接线**（生产路径零改动）：消费者只有夹具。",
    },
}

# ---- 第二颗牙：生产代码里的字面粗目标（保留，见 docstring 断言 6）----
PRODUCTION_NEEDLE = "GoalNearXZ.around("
NEEDLE_ALLOW_SUFFIX = (
    "pathing/core/search/FarTravelHop.java",   # 合法的"夹到已加载边界"层
    "pathing/core/search/GoalNearXZ.java",     # 工厂自身
)


def _brace_body(text: str, open_idx: int) -> str:
    """从 `{` 的下标取到配对的 `}`（简易：不处理字符串里的花括号 —— Java 源码里极少）。"""
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
    return ""


def exact_foot_value(text: str):
    """返回 `exactFoot()` 覆写体的取值：`False` / `True` / `None`（未覆写 ⇒ 接口默认 `true`）/ `"?"`（认不出来）。"""
    m = re.search(r"\bboolean\s+exactFoot\s*\(\s*\)\s*\{", text)
    if not m:
        return None
    body = _brace_body(text, m.end() - 1)
    if re.search(r"\breturn\s+false\s*;", body):
        return False
    if re.search(r"\breturn\s+true\s*;", body):
        return True
    return "?"


def impls(paths: list[str]) -> dict[str, dict]:
    """`{相对路径: {"exact_foot": 取值}}` —— 只收**声明**了 `implements …GoalSpec` 的文件。"""
    found: dict[str, dict] = {}
    for rel in sorted(paths):
        p = SRC / rel
        text = p.read_text(encoding="utf-8")
        if not re.search(r"\bimplements\s+[^{]*\bGoalSpec\b", text):
            continue
        found[rel] = {"exact_foot": exact_foot_value(text)}
    return found


def judge_impls(all_impls: dict[str, dict], registry: dict[str, dict], exists, floor: int = MIN_IMPLS,
                needle_hits: list[str] | None = None) -> list[str]:
    """⭐ **断言 ②~⑥ 的唯一实现**。`all_impls` 与 `exists` 由调用方注入
    ⇒ **合成臂与真树走同一条代码**（`D-254`：判据自己必须先被反向对照；⛔ 不许在臂里另写一份判据）。"""
    problems: list[str] = []

    # ② 人口下限（实现侧）—— 防"解析写坏 ⇒ 0 个实现 ⇒ 永远绿"
    if len(all_impls) < floor:
        problems.append(f"只认出 {len(all_impls)} 个 `GoalSpec` 实现（下限 {floor}，实测 3）"
                        "⇒ 解析写坏了；**不许把这种情况当通过**")

    # ⑤ 取值认不出来 ⇒ 红（别把它当默认 true 放绿）
    for rel, info in sorted(all_impls.items()):
        if info["exact_foot"] == "?":
            problems.append(f"`{rel}` 的 `exactFoot()` 体**认不出来**（本规则要求它是单一的 "
                            "`return true;` 或 `return false;`）⇒ 结构变了就改本规则，别静默放行")

    # ② 未登记的 `exactFoot()==false` 实现
    for rel, info in sorted(all_impls.items()):
        if info["exact_foot"] is False and rel not in registry:
            problems.append(f"`{rel}` 的 `exactFoot()` 返回 `false`（**粗目标**）却**没有登记** ⇒ "
                            "它整条 `GOAL_NOT_LOADED` 前置守卫被跳过（红线 `D-132`）。"
                            "⇒ 要么登记进 `FAR_GOAL_REGISTRY`（并写清它为什么安全），要么别返回 false")

    # ③ 登记条目必须仍然命中，且取值逐字相符
    for rel, entry in registry.items():
        actual = all_impls.get(rel)
        if actual is None:
            if exists(rel):
                problems.append(f"登记 `{rel}` 里**不再是** `GoalSpec` 实现 ⇒ 必须删掉本条（豁免得手也 FAIL）")
            else:
                problems.append(f"登记 `{rel}` 的文件**不在了** ⇒ 必须删掉本条，或改到新家"
                                "（**不许静默消失**）")
            continue
        if actual["exact_foot"] != entry.get("exact_foot"):
            problems.append(f"登记 `{rel}` 的取值漂了：登记 `exact_foot={entry.get('exact_foot')}`，"
                            f"实际 `{actual['exact_foot']}` ⇒ 取值本身是**红线开关**，必须同步改登记表")

    # ④ 登记条目必须带理由
    for rel, entry in registry.items():
        reason = str(entry.get("reason", "")).strip()
        if len(reason) < MIN_REASON:
            problems.append(f"登记 `{rel}` 的理由太短（{len(reason)} < {MIN_REASON}）⇒ 占位符不算理由")

    # ⑥ 第二颗牙：生产代码里的字面粗目标
    for hit in (needle_hits or []):
        problems.append(f"生产代码里出现**字面粗目标** `{PRODUCTION_NEEDLE}`：{hit} ⇒ "
                        "会让内核搜索读未加载区块（红线 `D-132`）。正确做法：远距离走 `FarTravelHop`"
                        "（一跳一跳，先 `hasChunkAt` 夹到已加载边界内侧）—— 见 `D-337` 附注二。")

    return problems


def judge(paths: list[str], registry: dict[str, dict], floor: int = MIN_IMPLS,
          needle_hits: list[str] | None = None) -> list[str]:
    """**真树入口**：① 人口下限（文件侧）→ 算实现表 → 交给 {@link judge_impls}。"""
    problems: list[str] = []

    # ① 人口下限（防解析/glob 崩了 ⇒ 空清单 ⇒ 永远绿）
    if len(paths) < floor:
        problems.append(f"只扫到 {len(paths)} 个 `.java`（下限 {floor}）⇒ 扫描崩了/glob 写坏了；"
                        "**不许把这种情况当通过**")

    problems += judge_impls(impls(paths), registry, exists=lambda rel: (SRC / rel).exists(),
                            floor=floor, needle_hits=needle_hits)
    return problems


def needle_violations() -> tuple[list[str], int]:
    """扫生产代码里的字面粗目标 ⇒ (违规清单, 全仓命中数)。夹具（`*CheckTask` / `*Bench*`）不计。"""
    violations: list[str] = []
    total = 0
    for p in sorted(SRC.rglob("*.java")):
        text = p.read_text(encoding="utf-8")
        for i, line in enumerate(text.splitlines(), 1):
            if PRODUCTION_NEEDLE not in line:
                continue
            total += 1
            name = p.name
            if name.endswith("CheckTask.java") or "Bench" in name:
                continue
            rel = str(p.relative_to(SRC))
            if any(rel.endswith(s) for s in NEEDLE_ALLOW_SUFFIX):
                continue
            violations.append(f"{rel}:{i}")
    return violations, total


# ==================== 合成臂（判据自己先被反向对照，`D-254`）====================

# ⚠️ 臂**不碰真树**：impl 表与登记表都在内存里造，然后调**同一个** `judge_impls`
# ⇒ 臂测的是真判据，不是它的副本（`D-254`）。
_EX_REASON = "合成臂的登记条目（理由足够长）"

ARMS: list[tuple[str, dict]] = [
    ("对照臂：有 false 实现且已登记 ⇒ 必须过", {}),
    ("R1 有未登记的 `exactFoot()==false` ⇒ 必须红", {"impls": [("a/New.java", False)]}),
    ("R2 登记条目取值漂了（登 true 实际 false）⇒ 必须红",
     {"impls": [("a/Drift.java", False)],
      "registry": {"a/Drift.java": {"exact_foot": True, "reason": _EX_REASON}}}),
    ("R3 登记条目已不再是实现（豁免得手）⇒ 必须红", {"registry_extra": "a/Gone.java"}),
    ("R4 登记条目理由太短 ⇒ 必须红",
     {"registry": {"a/Coarse.java": {"exact_foot": False, "reason": "x"}}}),
    ("R5 `exactFoot()` 体认不出来 ⇒ 必须红", {"impls": [("a/Weird.java", "?")]}),
    ("R6 实现人口低于下限 ⇒ 必须红", {"floor": 99}),
    ("R7 生产代码里出现字面粗目标 ⇒ 必须红", {"needle": ["a/Prod.java:1"]}),
]


def _arm_problems(override: dict) -> list[str]:
    """合成臂：造 impl 表 + 登记表，交给**真判据** `judge_impls`。"""
    impl_map: dict[str, dict] = {"a/Exact.java": {"exact_foot": True},
                                 "a/Coarse.java": {"exact_foot": False},
                                 "a/Inherit.java": {"exact_foot": None}}
    for rel, val in override.get("impls", []):
        impl_map[rel] = {"exact_foot": val}
    reg: dict[str, dict] = {"a/Coarse.java": {"exact_foot": False, "reason": _EX_REASON}}
    reg.update(override.get("registry", {}))
    if "registry_extra" in override:
        reg[override["registry_extra"]] = {"exact_foot": False, "reason": _EX_REASON}
    return judge_impls(impl_map, reg, exists=lambda _rel: True,
                       floor=override.get("floor", MIN_IMPLS),
                       needle_hits=override.get("needle"))


def main() -> int:
    if not SRC.is_dir():
        print(f"FAR_GOAL_CHECK_RESULT FAIL\n  ✗ 源目录不存在：{SRC}")
        return 1

    # ---- ① 先跑合成臂：判据自己必须能被反向对照（顺序有意：先证明尺子好，再量真树）----
    arm_problems: list[str] = []
    for label, override in ARMS:
        expect_ok = not override
        ok = not _arm_problems(override)
        if ok != expect_ok:
            arm_problems.append(f"{label} ⇒ 期望 {'过' if expect_ok else '红'}，"
                                f"实际 {'过' if ok else '红'}"
                                + (f"（问题：{_arm_problems(override)}）" if ok else ""))

    # ---- ② 再量真树 ----
    paths = sorted(str(p.relative_to(SRC)) for p in SRC.rglob("*.java"))
    violations, needle_total = needle_violations()
    problems = judge(paths, FAR_GOAL_REGISTRY, needle_hits=violations)
    all_impls = impls(paths)

    if arm_problems or problems:
        print("FAR_GOAL_CHECK_RESULT FAIL")
        for p in arm_problems:
            print(f"  ✗ [合成臂] {p}")
        for p in problems:
            print(f"  ✗ [真树] {p}")
        print("  ⇒ 依据：`D-132`（内核不得静默加载区块）· `D-500` §V（点名 → 分类判据）· `D-337` 附注二")
        return 1

    far = sorted(r for r, i in all_impls.items() if i["exact_foot"] is False)
    print("FAR_GOAL_CHECK_RESULT PASS："
          f"`GoalSpec` 实现 {len(all_impls)} 个（扫了 {len(paths)} 个 `.java`）· "
          f"其中 **exactFoot()==false（粗目标）{len(far)} 个，全部已登记**；"
          f"登记表 {len(FAR_GOAL_REGISTRY)} 条，取值逐字相符")
    for rel in far:
        print(f"    · 粗目标 {rel}")
    print(f"  第二颗牙（字面 `{PRODUCTION_NEEDLE}`）：全仓 {needle_total} 处，"
          f"**全在允许层**（`FarTravelHop` 夹边界层 / 工厂自身 / 夹具）")
    print(f"  合成臂 {len(ARMS)}/{len(ARMS)}（含 R3 豁免得手必须红 · R4 占位符理由必须红 · "
          f"R5 取值认不出来必须红）")
    print("  ⛔ 不覆盖：`exactFoot()` 的**运行期**效果（登记了但仍静默加载 ⇒ 升级为电池侧 "
          "`newlyLoaded` 采样，见 `D-500` §V 的回收条件）· 匿名/λ 实现（Java 里 `GoalSpec` 是接口，"
          "本项目要求实现是**顶层文件**）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
