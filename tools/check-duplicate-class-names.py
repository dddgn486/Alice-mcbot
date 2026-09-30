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
**而检查不会因此长出来**（下一个重名照样没人知道）。
⇒ 当时**趁改名之前挂**：那一刻有**活的真树反例**可验（见下面的"真树红臂"）。
> 勘测侧原话（`survey/46 §1.1`）：「**重名不是靠"记得改"解决，是靠"改名时它会疼"解决** —— 而今天**它不疼**。」

⭐⭐ **2026-09-29 实测（本检查第一次在真树上生效，不是推演）**：改名落地时，`GoalSpec` 那条豁免
**按断言 ③ 得手判红**（原文：「豁免 `GoalSpec` **已不再是重名** ⇒ 必须删掉白名单条目」）
⇒ **强迫同刀删除**（`D-516`）。⇒ 这项检查从"提案"变成"**确实拦住过一次**"。
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
- ✅ **2026-09-30 起覆盖嵌套/内部类名**（用户裁定；此前只扫顶层文件名 ⇒ `survey/46` 那句
  「靠 `grep 类名` 的锚定全部失准」在**嵌套层仍然活着**，实测活实例 2 处）；
- **不覆盖**：`同包内`的名字冲突（Java 本身禁止）、
  以及 ⚠️ **"名字不同但语义重叠"**（那正是 `survey/44`/`45` 的词表问题，属术语表的事，不是本门禁的事）。

跑法：`python3 tools/check-duplicate-class-names.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import hashlib
import re
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
    #    ⇒ 按纪律**同刀删除**（`D-516`）。⭐ **这是本检查第一次在真树上生效**
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
    # ⭐ **2026-09-30 新增两条** —— 「扩到嵌套类型」时冒出来的两组（用户裁定扩口径；`O80`）。
    #    两条都按零容忍三选一里的「**豁免 ＋ 复核触发**」登记（⛔ 不是无限期放着）。
    "Candidate": {
        "paths": [
            "src/main/java/com/dddgn/alice/job/Candidate.java",
            "src/main/java/com/dddgn/alice/reach/StandingPointSelector.java",
            "src/main/java/com/dddgn/alice/road/ContinuousRoadCurve.java",
            "src/main/java/com/dddgn/alice/task/craft/CraftStation.java",
        ],
        "reason": "`job/Candidate.java` 是**作业候选**（顶层）；另三处各自声明本地的 `Candidate`"
                  "（站位候选 / 道路曲线采样点 / 合成站候选）。⚠️ **归属 = `P1` 立家刀** 之后的"
                  "**术语面收口（批次 3）**；**复核触发 = 该组任一路径变化**。",
    },
    "SearchNode": {
        "paths": [
            "src/main/java/com/dddgn/alice/pathing/core/search/SearchNode.java",
            "src/main/java/com/dddgn/alice/road/RoadPlan.java",
        ],
        "reason": "`pathing/core/search/SearchNode.java` 是**搜索节点**（顶层）；`road/RoadPlan` 里"
                  "`SearchNode` 是道路规划的**本地节点**。**归属 = 批次 3 术语面收口**；"
                  "**复核触发 = 该组任一路径变化**。",
    },
    "Step": {
        "paths": [
            "src/main/java/com/dddgn/alice/compat/ftbteams/FtbPartyBinder.java",
            "src/main/java/com/dddgn/alice/fixture/RegressionBatteryTask.java",
            "src/main/java/com/dddgn/alice/task/Step.java",
        ],
        "reason": "`task/Step.java` 是 **step 原语注册口**（`D-539`）；另两处是**各自的局部数据载体**："
                  "`FtbPartyBinder` 里 `record Step(String what, boolean ok, String detail)`（FTB 队伍绑定的"
                  "单步结果）· `RegressionBatteryTask` 里 `private record Step(String name, …)`（电池的场景步）。"
                  "⚠️ 三处语义毫不相干 ⇒ 同名纯属巧合。**归属 = `P1` 立家刀**（立家时一并改名）；"
                  "**复核触发 = 该组任一路径变化**，或 `Step` 接口搬到 `step/` 包时（届时这条必须同刀处理）。",
    },
    "Task": {
        "paths": [
            "src/main/java/com/dddgn/alice/task/Task.java",
            "src/main/java/com/dddgn/alice/write/WritePolicyMatrix.java",
        ],
        "reason": "`task/Task.java` 是**框架的组合面**（`O58`：`target`/`Status`/`safeToCancel`/`failureReason`）；"
                  "`write/WritePolicyMatrix` 里的 `public enum Task` 是**写入策略的任务类别**"
                  "（TRAVERSAL/MINING/…/UNREGISTERED）。⚠️ 同名纯属巧合，但**改名不是小刀**："
                  "`enum Task` 在本体出现 **85** 处，且 `tools/policy-map.py` **按名字硬写**解析它"
                  "（`:258` `parse_enum(text, 「Task」)` · `:280-283` `private static Task derivedTask` ⇒ 6 处）。"
                  "**归属 = `P1` 立家刀**（连同 `policy-map.py` 的硬写路径一起改）；"
                  "**复核触发 = 该组任一路径变化**，或 `Task` 退役（`D-540` §二）启动时。",
    },
}


DECL_RE = re.compile(
    r"^\s*(?:public\s+|private\s+|protected\s+|static\s+|final\s+|abstract\s+|sealed\s+|"
    r"non-sealed\s+|strictfp\s+)*(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)",
    re.M)


def strip_comments_and_strings(text: str) -> str:
    """掏空注释**与字符串字面量**（类型声明不可能来自字符串 ⇒ 掏掉可免"字符串里写着 class Foo" 的假命中）。"""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            out.append('""')
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            i = min(j + 1, n)
        else:
            out.append(c)
            i += 1
    return "".join(out)


def declared_names(path: str) -> list[str]:
    """该文件声明的**全部**类型名（**顶层 ＋ 嵌套**），按声明顺序去重。

    ⭐ **2026-09-30 扩到嵌套类型**（用户裁定；`O80`）：此前只看文件名 stem，于是
    ⚠️ `survdey/46` 那句「所有靠 `grep 类名` 的锚定全部失准」在**嵌套层仍然活着** ——
    实测活实例 2 处：`compat/ftbteams/FtbPartyBinder.Step`、`write/WritePolicyMatrix.Task`
    （后者还让我自己的退役台账凭空多出 2 条假风险）。

    ⚠️ 合成臂传的是**不存在的假路径** ⇒ 那时退回文件名 stem；真实运行由 `main()` 的
    **存在性断言**兜住，所以这条退路**不会**在生产路径上静默生效。
    """
    f = ROOT / path
    base = path.rsplit("/", 1)[-1]
    stem = base[:-len(".java")] if base.endswith(".java") else base
    if not f.is_file():
        return [stem]
    names: list[str] = []
    for m in DECL_RE.finditer(strip_comments_and_strings(f.read_text(encoding="utf-8", errors="replace"))):
        if m.group(1) not in names:
            names.append(m.group(1))
    return names or [stem]          # 一个类型都没声明（`package-info.java`）⇒ 退回 stem，与旧口径一致


def nested_names(paths: list[str], namer=declared_names) -> dict[str, list[str]]:
    """⇒ `{嵌套声明的名字: [所在文件…]}`（用于把"嵌套层到底有多少"打成读数）。"""
    out: dict[str, list[str]] = {}
    for path in paths:
        base = path.rsplit("/", 1)[-1]
        stem = base[:-len(".java")] if base.endswith(".java") else base
        for name in namer(path):
            if name != stem:
                out.setdefault(name, []).append(path)
    return out


def group(paths: list[str], namer=declared_names) -> dict[str, list[str]]:
    """按**简单类型名**分组 —— ⚠️ 现在是**全部声明**（顶层 ＋ 嵌套），不再只是文件名 stem。

    ⚠️ 去后缀是承重的：本文件第一版把键写成 `Foo.java`，于是豁免键与它**永不相等**
    ⇒ 真树上同时报出"两个都未豁免" **和** "两个豁免条目都已得手"（自相矛盾的 4 条）——
    **是合成臂 R1 先红的**（`D-254`：判据自己必须先被反向对照）。
    """
    by_name: dict[str, list[str]] = {}
    for path in paths:
        for name in namer(path):
            by_name.setdefault(name, []).append(path)
    return by_name


def _is_top_level(path: str, name: str) -> bool:
    """该声明是不是**顶层**的（判据 = 文件名 stem 就等于这个名字）。"""
    base = path.rsplit("/", 1)[-1]
    return base == name + ".java"


def duplicates(paths: list[str], namer=declared_names) -> dict[str, list[str]]:
    """**判红**的那一类同名 ⇒ `{简单名: [路径…]}`（已排序）：**「顶层 ↔ 嵌套」同名**。

    ⭐ **为什么只判这一类**（2026-09-30 实测定的口径）：`survey/46` 的**真实危害**是
    「靠 `grep 类名` / `import X` 的锚定失准」—— 而那个锚定**只在一个"看起来权威"的
    `X.java` 存在时才真正骗人**（`import X` 解析到顶层那个，嵌套那个在本地把它遮住）。
    ⚠️ 扩到**全部**嵌套声明后实测爆出 **30 组**（`Phase` 一名 **×66**）⇒ 那是**范围炸弹**，
    且绝大多数是"各写各的局部状态机"，不构成"锚定失准"。
    ⇒ **判红 = 顶层 ↔ 嵌套**（今天 **4** 组）；**仅"嵌套 ↔ 嵌套"的其余组只出读数**。
    """
    out: dict[str, list[str]] = {}
    for name, ps in group(paths, namer).items():
        if len(ps) < 2 or name in RULE_EXCLUDED:
            continue
        # ⚠️ 口径 = **至少一个是顶层声明**：既覆盖本门禁最初的「顶层 ↔ 顶层」
        #    （`GoalSpec`／`DecisionTrace` 那一类），也覆盖新增的「顶层 ↔ 嵌套」。
        #    ⛔ 早期写成 `tops and nests`（两边都要有）⇒ 把 `DecisionTrace` 漏掉 ⇒ 它的豁免"得手"报红。
        if any(_is_top_level(q, name) for q in ps):
            out[name] = sorted(ps)
    return out


def nested_only(paths: list[str], namer=declared_names) -> dict[str, list[str]]:
    """**不判红、只出读数**的那一类：同名全部都是嵌套声明（无顶层 `X.java`）。"""
    out: dict[str, list[str]] = {}
    for name, ps in group(paths, namer).items():
        if len(ps) < 2 or name in RULE_EXCLUDED:
            continue
        if not any(_is_top_level(q, name) for q in ps):
            out[name] = sorted(ps)
    return out


def judge(paths: list[str], exempt: dict[str, dict], floor: int = MIN_FILES,
          namer=declared_names) -> list[str]:
    """对一份**路径清单**跑全部断言 ⇒ 返回问题清单（空 = 通过）。合成臂直接调它。"""
    problems: list[str] = []

    # ① 人口下限（防解析/glob 崩了 ⇒ 空清单 ⇒ 永远绿）
    if len(paths) < floor:
        problems.append(f"只扫到 {len(paths)} 个 `.java`（下限 {floor}，实测应为 516）"
                        " ⇒ 扫描崩了/glob 写坏了；**不许把这种情况当通过**")

    dupes = duplicates(paths, namer)

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


def _namer(mapping: dict[str, list[str]]):
    """合成臂用的假 namer（真树用 `declared_names`）——
    ⚠️ 必须有它：假路径**永远看着像顶层**（stem == 名字）⇒ 造不出"嵌套声明"的形状，
    新口径那两条臂就测不了。"""
    def f(path: str) -> list[str]:
        base = path.rsplit("/", 1)[-1]
        return mapping.get(path, [base[:-len(".java")] if base.endswith(".java") else base])
    return f


_TOP_AND_NESTED = (_BASE + ["src/main/java/d/New.java", "src/main/java/e/Holder.java"],
                   _namer({"src/main/java/e/Holder.java": ["Holder", "New"]}))
_NESTED_ONLY = (_BASE + ["src/main/java/d/H1.java", "src/main/java/e/H2.java"],
                _namer({"src/main/java/d/H1.java": ["H1", "Shared"],
                        "src/main/java/e/H2.java": ["H2", "Shared"]}))

# (标签, 路径, 豁免表, 人口下限, 是否期望通过, namer)
ARMS: list[tuple[str, list[str], dict, int, bool, object]] = [
    ("对照臂：无重名 ⇒ 必须过", _BASE, {}, 3, True, declared_names),
    ("R1 ⭐ **顶层 ↔ 嵌套**同名 ⇒ 必须红（新口径正例）",
     _TOP_AND_NESTED[0], {}, 3, False, _TOP_AND_NESTED[1]),
    ("R2 ⭐ **仅嵌套 ↔ 嵌套**同名 ⇒ **必须过**（只出读数；⛔ 全量判红会爆 30 组）",
     _NESTED_ONLY[0], {}, 3, True, _NESTED_ONLY[1]),
    ("R3 顶层 ↔ 顶层同名且未豁免 ⇒ 必须红（本门禁最初的用途，别在新口径下丢掉）",
     _DUP_PATHS + ["src/main/java/c/New2.java", "src/main/java/d/New2.java"], {}, 3, False, declared_names),
    ("R4 豁免的名字里**多出第三份** ⇒ 必须红", _DUP_PATHS + ["src/main/java/c/Dup.java"], _EX, 3, False,
     declared_names),
    ("R5 豁免条目已不再重名（豁免得手）⇒ 必须红", _BASE, _EX, 3, False, declared_names),
    ("R6 豁免条目理由太短 ⇒ 必须红", _DUP_PATHS, {"Dup": {"paths": _DUP_PATHS, "reason": "x"}}, 3, False,
     declared_names),
    ("R7 `package-info.java` 重复 ⇒ **必须过**（按规则排除，不是欠账）",
     _BASE + ["src/main/java/a/package-info.java", "src/main/java/b/package-info.java"], {}, 3, True,
     declared_names),
    ("R8 人口低于下限（glob 崩了）⇒ 必须红", _BASE, {}, 400, False, declared_names),
]


def main() -> int:
    if not JAVA.is_dir():
        print(f"DUPLICATE_CLASS_NAMES_CHECK_RESULT FAIL\n  ✗ 源目录不存在：{JAVA}")
        return 1

    # ---- ① 先跑合成臂：判据自己必须能被反向对照（顺序有意：先证明尺子好，再量真树）----
    arm_problems: list[str] = []
    for label, paths, exempt, floor, expect_ok, namer in ARMS:
        problems_arm = judge(paths, exempt, floor, namer)
        ok = not problems_arm
        if ok != expect_ok:
            arm_problems.append(f"{label} ⇒ 期望 {'过' if expect_ok else '红'}，实际 {'过' if ok else '红'}"
                                + (f"（问题：{problems_arm}）" if ok else ""))

    # ---- ② 再量真树 ----
    paths = sorted(str(p.relative_to(ROOT)) for p in JAVA.rglob("*.java"))
    missing = [p for p in paths if not (ROOT / p).is_file()]
    if missing:
        print(f"DUPLICATE_CLASS_NAMES_CHECK_RESULT FAIL\n  ✗ {len(missing)} 个路径不存在 ⇒ "
              f"`declared_names` 会退回 stem（读数会**静默变窄**）：{missing[:3]}")
        return 1
    problems = judge(paths, DUP_EXEMPT)
    dupes = duplicates(paths)
    nested = nested_names(paths)
    only_nested = nested_only(paths)

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
    print(f"  ⭐ 口径（2026-09-30 已扩）：扫描范围 = **全部声明**（顶层 ＋ 嵌套）——"
          f"其中**嵌套声明**的独立名字 **{len(nested)}** 个")
    print(f"  判红 = **「顶层 ↔ 嵌套」同名**（{len(dupes)} 组，全部已豁免）；"
          f"⚠️ 另有 **{len(only_nested)}** 组「仅嵌套 ↔ 嵌套」同名**只出读数、不判红**"
          f"（例：{sorted(only_nested, key=lambda k: -len(only_nested[k]))[:5]}）"
          f" —— 那是「各写各的局部状态机」，不构成 `survey/46` 说的锚定失准；"
          f"⛔ 全量判红会一次爆 30 组（`Phase` 一名 ×66）＝范围炸弹")
    print("  ⛔ 仍不覆盖：`tools/*.py` 模块名 · 「名字不同但语义重叠」（那是术语表的事）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
