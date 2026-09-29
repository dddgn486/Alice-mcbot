#!/usr/bin/env python3
"""⭐ 同名类门禁（`survey/46 §8.1` 的提案，用户 2026-09-27 拍「按乙来」）。

## 为什么这条规则该存在

`survey/44`/`45`/`46` 三轮术语审计挖到**三个逐级上升的层**：
① 一词多义（同一个中文词，多个义项）→ ② 7 条正交层级轴共用一套词 → ③ ⭐ **`src/` 里真的存在同名类**。

③ 不是"词没选好"，是**锚定问题**：
> ⚠️ **所有靠 `grep 类名` / `import X` 的锚定全部失准，而 `grep` 不会报错，只会多给一半结果。**

实测（2026-09-28，`ef6dbc4d`，516 个 `.java`）：

| 同名 | 两个是什么 |
|---|---|
| `GoalSpec` ✅**已消除** | `job/GoalSpec.java` **record**（目标级任务的全部外部输入）—— **2026-09-29 按 `D-478`/`P12/A` 改名 `job/JobDeclaration.java`** vs `pathing/core/search/GoalSpec.java` **interface**（规划目标，对齐 Baritone `Goal`） |
| `DecisionTrace` | `decision/DecisionTrace.java`（**决策层**的 trace） vs `job/DecisionTrace.java`（**L3 决策缝之四** / L3 的唯一日志出口） |

⚠️ `DecisionTrace` 那组更危险：**两个都与"决策"有关** ⇒ 读者**无法从名字判断**指哪一个。
⚠️ 而且 `pathing` 那个只在同包 6 个文件里用（**同包 ⇒ 无 `import`**）⇒ **`grep import` 根本看不见它**。

## ⭐ 它的时效性（为什么"当时挂"最对）—— ✅ **已实测生效一次**

`D-478` 的 `P12/A` 把 `job/GoalSpec` **改名成 `JobDeclaration`** ⇒ 重名会**自然地**消失，
**而牙不会因此长出来**（下一个重名照样没人知道）。
⇒ 当时**趁改名之前挂**：那一刻有**活的真树反例**可验（见下面的"真树红臂"）。
> 勘测侧原话（`survey/46 §1.1`）：「**重名不是靠"记得改"解决，是靠"改名时它会疼"解决** —— 而今天**它不疼**。」

⭐⭐ **2026-09-29 实测（本牙第一次在真树上生效，不是推演）**：改名落地时，`GoalSpec` 那条豁免
**按断言 ③ 得手判红**（原文：「豁免 `GoalSpec` **已不再是重名** ⇒ 必须删掉白名单条目」）
⇒ **强迫同刀删除**（`D-516`）。⇒ 这颗牙从"提案"变成"**确实拦住过一次**"。
⚠️ 此后 `GoalSpec` 那组只剩**合成臂**（真树反例已随改名消失）—— 这也说明下一条重名
仍然需要"**趁改名之前挂**"。

## 断言（任一不成立 ⇒ 非零退出）

1. **人口下限**：扫到的 `.java` ≥ `MIN_FILES`（实测 516）—— 防"glob 写坏 ⇒ 0 个文件 ⇒ 永远绿"。
2. ⭐ **非豁免的同名类 ⇒ 红**（列出名字 + 全部路径）。
3. ⭐ **豁免条目必须"仍然命中、且路径集合逐字相同"** —— 豁免得手 ⇒ 红（逼改名时同步删条目）；
   ⚠️ **路径集合比对是承重的**：只登记"名字"的话，**往已有的豁免里再加第三个同名类不会被发现**。
4. **豁免条目必须带理由**（≥ `MIN_REASON` 字符 ⇒ 挡 `# TODO` 这种占位符）。

## ⛔ 按规则排除、**不进白名单**的

- `package-info.java` —— 它是**包声明文件**，Java 不允许它声明普通类 ⇒ 不是"同名类"（实测 2 个）。
  ⇒ 排除**写在规则里**，不写成白名单条目（白名单只放**真欠账**）。

## ⛔ 它**不**覆盖什么（别把绿读成"取名没问题"）

- 只扫 **`src/main/java`** 的**顶层文件名**（简单名）。⚠️ 当前**无 `src/test`**；`tools/*.py` 的模块名**不扫**；
- **不覆盖**：嵌套/内部类名、`同包内`的名字冲突（Java 本身禁止）、
  以及 ⚠️ **"名字不同但语义重叠"**（那正是 `survey/44`/`45` 的词表问题，属术语表的事，不是本门禁的事）。

跑法：`python3 tools/check-duplicate-class-names.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java"

# ---- 参数 ----
MIN_FILES = 400          # 实测 516；留足余量但足以抓"glob 写坏 ⇒ 0 命中"
MIN_REASON = 8           # 挡占位符（`TODO` / `-` / 空）

# ---- 按规则排除（**不是**白名单；理由写在 docstring）----
# ⚠️ 键是**简单类名**（不带 `.java`）—— 与 `group()` 的口径必须一致。
RULE_EXCLUDED = {"package-info"}

# ---- 白名单：**只放真欠账**，每条必须带理由；路径集合逐字比对 ----
DUP_EXEMPT: dict[str, dict] = {
    # ⭐ **2026-09-29 删除留痕**（不许静默消失）：这里原有 `"GoalSpec"` 一条
    #    （`job/GoalSpec.java` record vs `pathing/core/search/GoalSpec.java` interface）。
    #    `D-478` 的 `P12/A` 把 `job/GoalSpec` **改名成 `JobDeclaration`** ⇒ 断言 ③ **得手判红**
    #    ⇒ 按纪律**同刀删除**（`D-516`）。⭐ **这是本牙第一次在真树上生效**
    #    （`survey/46 §1.1`：「重名不是靠"记得改"解决，是靠"改名时它会疼"解决」）。
    #    ⛔ **别再加回来**：`GoalSpec` 现在**不再重名** ⇒ 再登记会**立刻**触发断言 ③。
    #    旧条目原文见 `git show 2517ebe5:tools/check-duplicate-class-names.py`。
    "DecisionTrace": {
        "paths": [
            "src/main/java/com/dddgn/alice/decision/DecisionTrace.java",
            "src/main/java/com/dddgn/alice/job/DecisionTrace.java",
        ],
        "reason": "decision/(决策层的 trace) vs job/(L3 决策缝之四 / L3 的唯一日志出口) "
                  "—— ⚠️ 两处都与「决策」有关 ⇒ 读者无法从名字判断指哪一个；**暂无改名计划（待裁）**。"
                  "⚠️ 承重细节：pathing 那组的同包用法不产生 import ⇒ `grep import` 看不见（本门禁才看得见）",
    },
}


def group(paths: list[str]) -> dict[str, list[str]]:
    """按**简单类名**分组 —— ⚠️ 最后一个 `/` 之后、**并去掉 `.java`**。

    ⚠️ 去后缀是承重的：本文件第一版把键写成 `Foo.java`，于是豁免键（当时是 `GoalSpec`，
    2026-09-29 已随改名删除）与它**永不相等**
    ⇒ 真树上同时报出"两个都未豁免" **和** "两个豁免条目都已得手"（自相矛盾的 4 条）——
    **是合成臂 R1 先红的**（`D-254`：判据自己必须先被反向对照）。
    """
    by_name: dict[str, list[str]] = {}
    for path in paths:
        base = path.rsplit("/", 1)[-1]
        by_name.setdefault(base[:-len(".java")] if base.endswith(".java") else base, []).append(path)
    return by_name


def duplicates(paths: list[str]) -> dict[str, list[str]]:
    """真·同名类（排除 `RULE_EXCLUDED`）⇒ `{简单名: [路径…]}`（路径已排序）。"""
    return {name: sorted(ps) for name, ps in group(paths).items()
            if len(ps) > 1 and name not in RULE_EXCLUDED}


def judge(paths: list[str], exempt: dict[str, dict], floor: int = MIN_FILES) -> list[str]:
    """对一份**路径清单**跑全部断言 ⇒ 返回问题清单（空 = 通过）。合成臂直接调它。"""
    problems: list[str] = []

    # ① 人口下限（防解析/glob 崩了 ⇒ 空清单 ⇒ 永远绿）
    if len(paths) < floor:
        problems.append(f"只扫到 {len(paths)} 个 `.java`（下限 {floor}，实测应为 516）"
                        " ⇒ 扫描崩了/glob 写坏了；**不许把这种情况当通过**")

    dupes = duplicates(paths)

    # ② 非豁免的同名类
    for name, ps in sorted(dupes.items()):
        if name not in exempt:
            problems.append(f"`{name}.java` 同名出现 {len(ps)} 次：{ps}"
                            " ⇒ 靠名字找东西会**静默多给一半结果**（`survey/46 §0`）")

    # ③ 豁免条目必须仍然命中，且**路径集合逐字相同**
    for name, entry in exempt.items():
        listed = sorted(entry["paths"])
        actual = dupes.get(name)
        if actual is None:
            problems.append(f"豁免 `{name}` **已不再是重名** ⇒ 必须删掉白名单条目"
                            "（豁免得手也 FAIL —— 与 `check-provision-containment.sh` 的 `BOT_EXEMPT` 同一条纪律）")
            continue
        if actual != listed:
            extra = [p for p in actual if p not in listed]
            missing = [p for p in listed if p not in actual]
            problems.append(f"豁免 `{name}` 的路径集合变了：登记 {listed}，实际 {actual}"
                            + (f"；**多出** {extra}（往已豁免的名字里再加一份 ⇒ 这条比对是唯一能拦住它的东西）" if extra else "")
                            + (f"；**少了** {missing}" if missing else ""))

    # ④ 豁免条目必须带理由
    for name, entry in exempt.items():
        reason = str(entry.get("reason", "")).strip()
        if len(reason) < MIN_REASON:
            problems.append(f"豁免 `{name}` 的理由太短（{len(reason)} < {MIN_REASON}）⇒ 占位符不算理由")

    return problems


# ==================== 合成臂（判据自己先被反向对照，`D-254`）====================

_BASE = ["src/main/java/a/Foo.java", "src/main/java/b/Bar.java", "src/main/java/c/Baz.java"]
_EX = {"Dup": {"paths": ["src/main/java/a/Dup.java", "src/main/java/b/Dup.java"],
               "reason": "合成臂用的豁免条目（理由足够长）"}}
_DUP_PATHS = ["src/main/java/a/Dup.java", "src/main/java/b/Dup.java"]

ARMS: list[tuple[str, list[str], dict, int, bool]] = [
    ("对照臂：无重名 ⇒ 必须过", _BASE, {}, 3, True),
    ("R1 一份未豁免的重名 ⇒ 必须红",
     _BASE + ["src/main/java/a/New.java", "src/main/java/b/New.java"], {}, 3, False),
    ("R2 豁免的名字里**多出第三份** ⇒ 必须红", _DUP_PATHS + ["src/main/java/c/Dup.java"], _EX, 3, False),
    ("R3 豁免条目已不再重名（豁免得手）⇒ 必须红", _BASE, _EX, 3, False),
    ("R4 豁免条目理由太短 ⇒ 必须红", _DUP_PATHS, {"Dup": {"paths": _DUP_PATHS, "reason": "x"}}, 3, False),
    ("R5 `package-info.java` 重复 ⇒ **必须过**（按规则排除，不是欠账）",
     _BASE + ["src/main/java/a/package-info.java", "src/main/java/b/package-info.java"], {}, 3, True),
    ("R6 人口低于下限（glob 崩了）⇒ 必须红", _BASE, {}, 400, False),
]


def main() -> int:
    if not JAVA.is_dir():
        print(f"DUPLICATE_CLASS_NAMES_CHECK_RESULT FAIL\n  ✗ 源目录不存在：{JAVA}")
        return 1

    # ---- ① 先跑合成臂：判据自己必须能被反向对照（顺序有意：先证明尺子好，再量真树）----
    arm_problems: list[str] = []
    for label, paths, exempt, floor, expect_ok in ARMS:
        ok = not judge(paths, exempt, floor)
        if ok != expect_ok:
            arm_problems.append(f"{label} ⇒ 期望 {'过' if expect_ok else '红'}，实际 {'过' if ok else '红'}"
                                + (f"（问题：{judge(paths, exempt, floor)}）" if ok else ""))

    # ---- ② 再量真树 ----
    paths = sorted(str(p.relative_to(ROOT)) for p in JAVA.rglob("*.java"))
    problems = judge(paths, DUP_EXEMPT)
    dupes = duplicates(paths)

    if arm_problems or problems:
        print("DUPLICATE_CLASS_NAMES_CHECK_RESULT FAIL")
        for p in arm_problems:
            print(f"  ✗ [合成臂] {p}")
        for p in problems:
            print(f"  ✗ [真树] {p}")
        print("  ⇒ 依据：`survey/46 §8.1`（同名类 ⇒ 红；§1.1：趁 `P12/A` 改名之前挂）")
        return 1

    digest = hashlib.sha256("\n".join(paths).encode("utf-8")).hexdigest()[:16]
    print("DUPLICATE_CLASS_NAMES_CHECK_RESULT PASS")
    print(f"  扫描：src/main/java 下 `.java` **{len(paths)}** 个（下限 {MIN_FILES}，实测 516）· "
          f"清单 sha256:{digest}")
    print(f"  按规则排除：{sorted(RULE_EXCLUDED)}（包声明文件，Java 不允许它声明普通类）"
          f" —— 实测同名 {len(group(paths).get("package-info", []))} 个，**不计欠账**")
    print(f"  真·同名类 **{len(dupes)}** 组，**全部已带理由豁免**（0 组无理由）：")
    for name, ps in sorted(dupes.items()):
        entry = DUP_EXEMPT[name]
        print(f"    · `{name}` ×{len(ps)}:")
        for p in ps:
            print(f"        {p}")
        print(f"      理由：{entry['reason']}")
    print(f"  ⭐ 豁免条目是**逐字比对路径集合**的：往已豁免的名字里再加一份 ⇒ 红（合成臂 R2）；"
          f"改名后条目得手 ⇒ 红（R3）")
    print(f"  合成臂 {len(ARMS)}/{len(ARMS)}（含 R5：`package-info` 重复**必须过** —— 口径自证）")
    print("  ⛔ 不覆盖：嵌套/内部类名 · `tools/*.py` 模块名 · 「名字不同但语义重叠」（那是术语表的事）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
