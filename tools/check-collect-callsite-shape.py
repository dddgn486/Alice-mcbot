#!/usr/bin/env python3
"""`step 5b` 刀① · **`丙①` 静态形状 pin**（`D-496`，用户 2026-09-27 拍「全甲」）。

## 为什么这条规则该存在

批量收集那一次调用（`FishboneJob` 的 `startCollect`）传下去的 5 个"有讲究"的实参，
每一个都是**过去的血**换来的决定（`D-453` / `D-375` / `1.4z`）：

| 实参 | 含义 | 改坏会怎样 |
|---|---|---|
| `allowWorldModification = false` | 不许为捡东西改造世界 | 改 `true` ⇒ 又回去挖穿地形捡掉落物（真机烧过 19 段计划） |
| 显式给额度 | 不靠默认值 | 省略 ⇒ 静默退回默认，作业侧失去控制（`D-493 3甲` 要清掉的就是这个"默认口"） |
| `STANDABLE_ONLY` | 只能站现成的格 | 改宽 ⇒ 为捡一件东西搭桥 |
| `null`（候选源） | = `scope.liveDrops()`（本作业在册） | 换掉 ⇒ 会去捡不属于本作业的东西 |
| `PRODUCT_FILTER::matches` | 主动清单 = 产物 | 改 `null` ⇒ 又回去追那 61% 的石头 |

**这 5 样任何一样被改，代码照样编译、游戏照样跑、没有一行报错。**
而且**行为夹具也抓不到** —— `CollectConservationCheckTask` 臂④ 传的是**它自己**的实参：
生产那边把 `false` 改成 `true`，夹具**依然全绿**（`D-496` 丙② 注释里逐字写明的那条边界）。
⇒ 这一半只能靠**读文本**的静态门禁。两半合起来才是一件事。

## ⚠️ 本门禁最容易写错的地方（实测，2026-09-27）

直觉写法是"在文件里断言 `PRODUCT_FILTER::matches` 出现在批量收集的调用里"。**这会被骗过**：
`FishboneJob` 里 `PRODUCT_FILTER::matches` 有 **3 处** —— `:571` / `:1308` 是 **`MineTask`** 的
`activePickup` 实参（形状与收集器几乎一样），只有 `:1388` 是收集器。
⇒ 锚错 = **那两个 `MineTask` 调用点就能满足断言** = 生产那一行被改坏而门禁依然绿。
所以：**必须锚 `new CollectDropsTask(` 这个被调方名字**，且用配平括号取**完整实参**（不用行号）。
本文件自带合成红臂 `R6` 专门钉这一条（`check-frozen-code.py` 记过同族的"只写 `foo(` 会被调用点满足"）。

## 断言（任一不成立 ⇒ 非零退出）

1. **目标文件存在**，且里面 `new CollectDropsTask(` **恰好 1 处**（多于 1 处 ⇒ pin 的靶子不唯一 ⇒ 红）。
2. 该调用的实参**恰好 9 个**（= 走完整构造器 ⇒ **额度是显式传的**，不是靠重载默认）。
3. 五个**从右往左**数的实参形状（从右锚定：`bot/origin/scope/ids` 前缀怎么变都不影响）：
   - `arg[4]` == `false`
   - `arg[5]` **非空**（额度表达式；⚠️ **不断言它是什么**，见"边界"）
   - `arg[6]` 含 `STANDABLE_ONLY`
   - `arg[7]` == `null`
   - `arg[8]` == `PRODUCT_FILTER::matches`

## ⛔ 它**不断言**什么（别把绿读成"这件事全合规"）

- **不断言额度的值 / 常量名**：`D-493 3甲` 要删 `DEFAULT_TOTAL_BUDGET_TICKS`，
  断言常量名会让本门禁在那一刀**假红**。这里只要求"那一格有东西"（非空）。
- **不断言调用时机**：什么时候起批量收集是结构边界的事（进 `step 5b` 未覆盖清单）。
- **不断言行为**：这一组实参打下去行为对不对，是 `CollectConservationCheckTask` 臂④ 的事。
- ⚠️ **刀② 故意重排构造器形状时本门禁会红，那是特性不是故障** —— pin 的作用就是逼人**显式决定**
  "新形状是不是还保得住这 5 条"；确认保住就同步更新本文件的断言并写明为什么。

## 解析纪律（本仓反复踩过的三条）

- **先去注释/字符串再匹配**（`check-phase-transition-outlet.py` 的同一条教训）；
  mask 只把内容换成空格、**保留长度与换行** ⇒ 行号可直接算。
- **配平括号取完整实参**：按第一个 `)` 截断会把 `List.of()` 之后的实参全丢掉
  （`D-254` 实测过一次"参数表按第一个 `)` 截断 ⇒ 规则永远绿"）。
- **判据自己先被反向对照**：本文件带 1 条对照臂 + 7 条合成红臂，全部必须如预期红/绿。

跑法：`python3 tools/check-collect-callsite-shape.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "src/main/java/com/dddgn/alice/job/fishbone/FishboneJob.java"
CALLEE = "new CollectDropsTask("

# ---- 期望形状（实参按位置；`None` = 只要非空）----
EXPECT_ARITY = 9
EXPECT_WORLD_MOD = "false"
EXPECT_GAIN_PROFILE_SUBSTR = "STANDABLE_ONLY"
EXPECT_SOURCE = "null"
EXPECT_ACTIVE_PICKUP = "PRODUCT_FILTER::matches"


def mask(text: str) -> str:
    """把注释 / 字符串 / 字符字面量的**内容**换成空格（长度与换行保持不变 ⇒ 行号可算）。

    ⚠️ 不做这一步，`// PRODUCT_FILTER::matches` 这类注释里的引用会**假满足**断言
    （`check-provision-containment.sh` 的 `{@link …}` 事故同族）。
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch == "/" and nxt == "*":
            out.append("  ")
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("  " if i < n else "")
            i += 2 if i < n else 0
            continue
        if text.startswith('"""', i):
            out.append("   ")
            i += 3
            while i < n and not text.startswith('"""', i):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append("   ")
                i += 3
            continue
        if ch in ('"', "'"):
            quote = ch
            out.append(" ")
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\":
                    out.append("  ")
                    i += 2
                    continue
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def split_args(raw: str) -> list[str]:
    """按**顶层**逗号切实参（嵌套 `()`/`[]`/`{}` 里的逗号不算）。

    `raw` 必须是已 mask 的文本（因此不必再处理引号/转义）。
    """
    args: list[str] = []
    depth = 0
    current: list[str] = []
    for ch in raw:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
            continue
        current.append(ch)
    tail = "".join(current).strip()
    if tail:
        args.append(tail)
    return args


def calls(text: str) -> list[tuple[int, list[str] | None]]:
    """`new CollectDropsTask(` 的全部调用点 → `[(偏移, 实参表 | None)]`（配平括号取完整实参）。"""
    found: list[tuple[int, list[str] | None]] = []
    start = 0
    while True:
        idx = text.find(CALLEE, start)
        if idx < 0:
            return found
        open_idx = idx + len(CALLEE) - 1
        depth = 0
        i = open_idx
        while i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        if i >= len(text):
            found.append((idx, None))       # 括号不配平 ⇒ 如实报"解析不出来"，不许静默跳过
            return found
        found.append((idx, split_args(text[open_idx + 1:i])))
        start = i + 1


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def judge(snippet: str) -> list[str]:
    """对一段**源码文本**跑全部形状断言 ⇒ 返回问题清单（空 = 通过）。"""
    problems: list[str] = []
    sites = calls(mask(snippet))
    if len(sites) != 1:
        problems.append(f"`{CALLEE}` 调用点应**恰好 1 处**，实际 {len(sites)} 处"
                        "（多于 1 处 ⇒ pin 的靶子不唯一）")
        return problems
    _, args = sites[0]
    if args is None:
        problems.append("调用点括号不配平 ⇒ 解析不出来（**不许**把解析失败当通过）")
        return problems
    if len(args) != EXPECT_ARITY:
        problems.append(f"实参应 {EXPECT_ARITY} 个（= 走完整构造器 ⇒ **额度显式传**），"
                        f"实际 {len(args)} 个：{args}")
        return problems
    if args[4] != EXPECT_WORLD_MOD:
        problems.append(f"arg[4]（allowWorldModification）应 `{EXPECT_WORLD_MOD}`，实际 `{args[4]}`")
    if not args[5]:
        problems.append("arg[5]（额度）为空 ⇒ 等于靠重载默认值（`D-493 3甲` 要清的就是这个默认口）")
    if EXPECT_GAIN_PROFILE_SUBSTR not in args[6]:
        problems.append(f"arg[6]（能力信封）应含 `{EXPECT_GAIN_PROFILE_SUBSTR}`，实际 `{args[6]}`")
    if args[7] != EXPECT_SOURCE:
        problems.append(f"arg[7]（候选源）应 `{EXPECT_SOURCE}`（= scope.liveDrops()），实际 `{args[7]}`")
    if args[8] != EXPECT_ACTIVE_PICKUP:
        problems.append(f"arg[8]（主动清单）应 `{EXPECT_ACTIVE_PICKUP}`，实际 `{args[8]}`")
    return problems


# ==================== 合成臂（口径自证：判据自己先被反向对照）====================
# 每一臂只改**一处**，且必须如预期地 通过 / 失败 —— 否则本门禁的判定逻辑本身是坏的。

GOOD = """
        collector = new CollectDropsTask(bot, template.startFoot(), scope, List.of(), false,
                CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS,
                com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY, null,
                PRODUCT_FILTER::matches);
"""

# R6 = `FishboneJob:571/:1308` 那两处的**真实形状**（`MineTask` 的 activePickup）。
# ⭐ 它是本门禁最要紧的一条红臂：锚错（锚 `PRODUCT_FILTER::matches`）就会让这一臂**假绿**。
MINETASK_SHAPE = """
            oreTask = new MineTask(bot, ore, scope,
                    MiningBudget.forTarget(bot, level, ore, false),
                    cellProfile(), grant,
                    PRODUCT_FILTER::matches);
"""

ARMS: list[tuple[str, str, bool]] = [
    ("对照臂（真形状必须过）", GOOD, True),
    ("R1 `allowWorldModification` 改成 true", GOOD.replace(", false,", ", true,"), False),
    ("R2 能力信封改成别的档", GOOD.replace("STANDABLE_ONLY", "FULL_GAIN"), False),
    ("R3 候选源换成自定义", GOOD.replace("null,\n                PRODUCT_FILTER", "this::liveItem,\n                PRODUCT_FILTER"), False),
    ("R4 主动清单改成 null", GOOD.replace("PRODUCT_FILTER::matches", "null"), False),
    ("R5 省略额度（走 8 参重载）",
     GOOD.replace("CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS,\n                ", ""), False),
    ("R6 `MineTask` 的形状（含 PRODUCT_FILTER::matches，但**不是**收集器调用）", MINETASK_SHAPE, False),
    ("R7 两个收集器调用点（靶子不唯一）", GOOD + GOOD, False),
]


def main() -> int:
    if not TARGET.is_file():
        print(f"COLLECT_CALLSITE_SHAPE_CHECK_RESULT FAIL\n  ✗ 目标文件不存在：{TARGET}")
        return 1
    text = TARGET.read_text(encoding="utf-8")
    masked = mask(text)

    # ---- ① 先跑合成臂：判据自己必须能被反向对照（顺序有意：先证明尺子是好的，再量真树）----
    arm_problems: list[str] = []
    for label, snippet, expect_ok in ARMS:
        ok = not judge(snippet)
        if ok != expect_ok:
            arm_problems.append(f"{label}：期望 {'过' if expect_ok else '红'}，实际 {'过' if ok else '红'}"
                                + (f"（问题：{judge(snippet)}）" if ok else ""))

    # ---- ② 再量真树 ----
    problems = judge(text)
    sites = calls(masked)

    if arm_problems or problems:
        print("COLLECT_CALLSITE_SHAPE_CHECK_RESULT FAIL")
        for p in arm_problems:
            print(f"  ✗ [合成臂] {p}")
        for p in problems:
            print(f"  ✗ [真树] {p}")
        print("  ⇒ 依据：`D-496` 丙①（批量收集调用点的实参形状 = 那一半「实参被改坏」的安全带）")
        return 1

    sha = hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]
    line = line_of(masked, sites[0][0])
    args = sites[0][1] or []
    print("COLLECT_CALLSITE_SHAPE_CHECK_RESULT PASS")
    print(f"  靶子：{TARGET.relative_to(ROOT)}:{line}（行号**仅供人读**；判定不看行号）· sha256:{sha}")
    print(f"  实参 {len(args)} 个（从右锚定，前缀怎么变都不影响判定）：")
    for i, a in enumerate(args):
        label = {4: "allowWorldModification", 5: "额度", 6: "能力信封", 7: "候选源", 8: "主动清单"}.get(i, "")
        print(f"    arg[{i}] {a}" + (f"   ← {label}" if label else ""))
    print(f"  合成臂 {len(ARMS)}/{len(ARMS)}（含 R6：`MineTask` 的**同形调用**必须不被本门禁满足 —— "
          "锚错就会假绿）")
    print("  ⛔ 不断言：额度的值/常量名（`3甲` 要删那个常量）、调用时机、行为（那是 "
          "`CollectConservationCheckTask` 臂④）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
