#!/usr/bin/env python3
"""`G3`（2026-09-21 用户裁定「这不是小事」）+ **`G3-b`（2026-09-28 用户裁定「甲+丙」）**。

## 牙① 结构牙（`G3`，原有）：架构红线必须带门禁指针，或带复核触发的「未门禁」标记

背景（可复核的计数）：`AGENTS.md` §不可悄悄改变的架构边界 有 6 条红线——**只有 `D-076` 一条真正被门禁覆盖**，
其余完全靠人记得。本次事故（`D-374`：一格高夹缝在整张图里没有入边）正好落在**零门禁**的那条上。
一条红线只有三种合法状态：① `[gate: <脚本>]`（点名脚本必须真实存在，否则是**假指针**）；
② `[未门禁: <原因>；复核触发: <条件>]`；③ ❌ 什么都没有 ⇒ 判红。
反模式：**别把「未门禁」当免死金牌** —— 没有 `复核触发:` 的 `[未门禁: …]` 一律判红。

## 牙② 内容牙（`G3-b` 新增）：**指针指向的东西必须与项目自身一致**

⚠️ 为什么需要它（2026-09-28 实测的**活反例**，就在真树上）：`牙①` 只能验「有没有指针」，**验不了正文还对不对**。
于是两条硬伤同时存在而门禁全绿：
  · `AGENTS.md` 的 `D-036` 条指向 `reference/baritone/` —— 那是 **MC 1.21.4** 的树，而本项目是 **1.20.1**
    （正解 = `reference/baritone-1.20.1/`）⇒ **照它办事会读错版本的源码**；
  · `D-076` 条写「`miningApproach` 禁用 `PILLAR/FALL/DOWNWARD`」—— 与代码相反（`D-366b` 已取消该禁用）。
⇒ 本牙把「版本钉」做成可复算：**`AGENTS.md` 里出现的 `reference/baritone*` 路径，其 `gradle.properties`
的 `minecraft_version` 必须等于本项目 `gradle.properties` 的 `minecraft_version`。**
新增一颗钉子 = 往 `PINS` 加一行（表驱动，不散落判断）。

## 牙③ 准入锁（`G3-b` 新增）：**只收高确定性方案，且只许经用户显式同意写入**

用户 2026-09-28 原话：「**在这个文件里只有被判定高确定性方案，且要经过我显式同意，才能写入**」，
以及当场质问：「**tm 怎么 `AGENTS.md` 也有散文规则**」——
⭐ 即：本文件**自己就是「散文规则」的最大载体**（实测 **23 条目里 17 条没有任何可执行标记**），
而它的正文还写着「本项目**不再默认增加散文规则**」⇒ **自我违反**。
三条硬约束（**不追溯旧账、但禁止新增；旧账可见且只许减**）：
  1. **新增**一条既无任何标记、又不在基线的条目 ⇒ **红**（它只能是"高确定性 + 已同意"的东西）；
  2. 基线条目**静默消失** ⇒ **红**（红线不许无声删除）；
  3. `散文条目=<n>/<总数>` **每次打印**（可见读数，不判红）—— 让这个数**只许降**，且不必靠人记得。

两种合法写入方式（都**在 diff 里可见**）：① 条目带 `[用户确认: YYYY-MM-DD]`；
② 或同步更新本文件的 `BASELINE_PROSE` 基线（改基线这个动作本身就暴露在 review 里）。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENTS = ROOT / "AGENTS.md"
SECTION = "## 不可悄悄改变的架构边界"
PROJECT_PROPS = ROOT / "gradle.properties"

GATE = re.compile(r"\[gate:\s*([^\]]*)\]")
UNENFORCED = re.compile(r"\[未门禁:\s*([^\]]*)\]")
SCRIPT = re.compile(r"([A-Za-z0-9_.-]+\.(?:sh|py))")

# ==================== 牙② 的钉表（表驱动：新增一颗钉子 = 加一行） ====================
#
# (AGENTS.md 里要抓的路径模式, 用来对齐的 gradle.properties 键)
# ⚠️ 路径可能是**绝对**的（Baritone 两棵树在仓库外：`<workspace>/reference/baritone*`）
# ⇒ 相对路径按 `ROOT.parent`（= 工作区）解析，绝对路径原样用。
PINS: tuple[tuple[str, str], ...] = (
    (r"(?:/[A-Za-z0-9_./\-]*)?reference/baritone[A-Za-z0-9_.\-]*", "minecraft_version"),
)

# ==================== 牙③ 的基线（2026-09-28 全文件实测：23 条目 / 17 条散文） ====================
#
# ⚠️ **这是一份"欠账白名单"**：指纹 = 条目首行的前 20 个字符（`- ` 之后，逐字）。
# 它**只许变短**：条目被升级（补上 `[gate: …]` 等标记）时不必改它（指纹仍在，见 `missing` 的算法）；
# 条目被**重写首行**或**新增**时，必须要么带上 `[用户确认: YYYY-MM-DD]`，要么在**同一次提交**里改这里。
PROSE_MARKERS: tuple[str, ...] = ("[gate:", "[未门禁:", "[检查:", "[用户确认:")

BASELINE_PROSE: frozenset[str] = frozenset({
    '**`AGENTS.md` + `AI_',
    '任何新的验证手段**必须挂在已有命令上*',
    '涉及渲染、实体、物理、GUI、同步或玩家',
    '优先使用可 `/give` 获得的游戏内',
    '**测试入口必须零参数**：优先"游戏内',
    '**测试夹具一次动作覆盖全部**：需要特',
    'AI 应主动查找可访问的 Windows',
    '测试反馈要主动询问操作、预期/实际、复现',
    '**物理场景必须主动询问**：当关键证据',
    '**用户有最终决策权，但不必把用户的每句',
    'AI 的默认动作：**照做**；但**允',
    '**临时裁定要标注 `（临时）` 并写复',
    '详见 `docs/AI_DEVELOPM',
    '**active goal 可用（202',
    '**上下文：不估算、不围绕它思考（用户 ',
    '**场景夹具两条硬纪律**：夹具**自己',
    '**不要为"省用户一轮"而停**：下一步',
})

FP_LEN = 20


def fingerprint(first_line: str) -> str:
    """条目首行（含 `- ` 前缀）的指纹：去掉前缀后取前 `FP_LEN` 个字符。"""
    return first_line[2:2 + FP_LEN]


def all_bullets(text: str) -> list[str]:
    """**全文件**的条目块（`- ` 起，含缩进续行；空行不断，`##` / `>` / `|` 断）。"""
    result: list[str] = []
    current: list[str] = []
    for line in text.split("\n"):
        if line.startswith("- "):
            if current:
                result.append("\n".join(current))
            current = [line]
        elif current and line.strip() and not line.startswith(("#", ">", "|")):
            current.append(line)
        elif current and line.startswith(("#", ">", "|")):
            result.append("\n".join(current))
            current = []
    if current:
        result.append("\n".join(current))
    return result


def bullets(text: str) -> list[tuple[str, str]]:
    """**只取**红线小节里的 (首行摘要, 整块文本)，按出现顺序。"""
    lines = text.split("\n")
    try:
        start = next(i for i, line in enumerate(lines) if line.strip() == SECTION)
    except StopIteration:
        raise SystemExit(f"在 AGENTS.md 里找不到小节 {SECTION!r}（结构变了 ⇒ 本门禁要跟着改）")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    result: list[tuple[str, str]] = []
    current: list[str] = []
    for line in lines[start + 1:end]:
        if line.startswith("- "):
            if current:
                result.append((current[0], "\n".join(current)))
            current = [line]
        elif current and line.strip():
            current.append(line)
    if current:
        result.append((current[0], "\n".join(current)))
    return result


def gradle_property(path: pathlib.Path, key: str) -> str | None:
    """读 `<path>/gradle.properties` 的某个键（读不到 ⇒ None）。"""
    props = path / "gradle.properties"
    if not props.is_file():
        return None
    for line in props.read_text(encoding="utf-8", errors="replace").split("\n"):
        stripped = line.strip()
        if stripped.startswith(key + "="):
            return stripped.split("=", 1)[1].strip()
    return None


def check_boundary(text: str) -> tuple[list[str], int, int, int]:
    """牙①：红线小节的结构。返回 (problems, 红线数, 有门禁数, 未门禁数)。"""
    problems: list[str] = []
    gated = 0
    unenforced = 0
    for index, (first, block) in enumerate(bullets(text), start=1):
        label = first[2:62]
        gate = GATE.search(block)
        unenf = UNENFORCED.search(block)
        if not gate and not unenf:
            problems.append(f"红线#{index}（{label}）既没有 `[gate: …]` 也没有 `[未门禁: …]`"
                            " ⇒ 它在文档里与「有门禁」长得一模一样，但没有任何东西会在它被破坏时变红")
            continue
        if gate:
            gated += 1
            names = SCRIPT.findall(gate.group(1))
            if not names:
                problems.append(f"红线#{index}（{label}）的 `[gate: …]` 里没有一个脚本名"
                                "（形如 `check-xxx.sh`）⇒ 指针不可解析")
            for name in names:
                if not (ROOT / "tools" / name).exists():
                    problems.append(f"红线#{index}（{label}）指向的门禁 `tools/{name}` **不存在**"
                                    "（假指针：读起来像有门禁，实际没有）")
        if unenf:
            unenforced += 1
            if "复核触发" not in unenf.group(1):
                problems.append(f"红线#{index}（{label}）的 `[未门禁: …]` 没有写 `复核触发:`"
                                " ⇒ 「未门禁」会变成免死金牌（永远不回头补）")
        if gate and unenf:
            problems.append(f"红线#{index}（{label}）同时标了 `[gate: …]` 与 `[未门禁: …]`"
                            " ⇒ 状态自相矛盾，读者无法判断到底有没有门禁")
    return problems, len(bullets(text)), gated, unenforced


def resolve_pin(path_text: str) -> pathlib.Path:
    """钉住的路径：绝对路径原样用，相对路径按工作区（`ROOT.parent`）解析。"""
    p = pathlib.Path(path_text)
    return p if p.is_absolute() else ROOT.parent / p


def check_pins(text: str, project_version: str | None) -> tuple[list[str], int]:
    """牙②：内容牙 —— 钉住的路径其版本必须与项目一致。返回 (problems, 钉数)。"""
    problems: list[str] = []
    if project_version is None:
        problems.append(f"读不到本项目 {PROJECT_PROPS} 的 `minecraft_version` ⇒ 版本钉无法复算"
                        "（项目结构变了 ⇒ 本规则要跟着改）")
        return problems, 0
    found: set[tuple[str, str]] = set()
    for pattern, key in PINS:
        for match in re.finditer(pattern, text):
            found.add((match.group(0), key))
    for path_text, key in sorted(found):
        resolved = resolve_pin(path_text)
        pinned = gradle_property(resolved, key)
        if pinned is None:
            problems.append(f"`AGENTS.md` 钉的路径 `{path_text}`（解析为 `{resolved}`）"
                            f"读不到 `gradle.properties` 的 `{key}`"
                            " ⇒ 这个指针无法复算（路径写错 / 树不在了）")
        elif pinned != project_version:
            problems.append(f"`AGENTS.md` 钉的路径 `{path_text}` 是 **{key}={pinned}**，"
                            f"而本项目是 **{key}={project_version}** ⇒ **照它办事会读错版本的源码**"
                            "（内容牙：指针真实存在 ≠ 指对了东西）")
    return problems, len(found)


def check_admission(text: str,
                    baseline: frozenset[str] = BASELINE_PROSE) -> tuple[list[str], int, int, int]:
    """牙③：准入锁。返回 (problems, 条目总数, 散文条目数, 已有标记数)。`baseline` 可注入（自检用）。"""
    problems: list[str] = []
    blocks = all_bullets(text)
    seen: set[str] = set()
    prose: list[str] = []
    marked = 0
    for block in blocks:
        first = block.split("\n")[0]
        seen.add(fingerprint(first))
        if any(marker in block for marker in PROSE_MARKERS):
            marked += 1
        else:
            prose.append(first)
    for first in prose:
        fp = fingerprint(first)
        if fp not in baseline:
            problems.append(
                f"`AGENTS.md` 出现了**新的无标记条目**：`{first[2:70]}`"
                " ⇒ 本文件只收【高确定性方案 + 用户显式同意】的东西；"
                "请要么补上 `[gate: …]` / `[未门禁: …；复核触发: …]` / `[检查: <命令>]`，"
                "要么带 `[用户确认: YYYY-MM-DD]`（经用户显式同意）")
    missing = baseline - seen
    for fp in sorted(missing):
        problems.append(f"基线里的散文条目 **静默消失了**（指纹 `{fp}`）"
                        " ⇒ 红线/规则不许无声删除；确实要删就同一次提交里改 `BASELINE_PROSE`")
    return problems, len(blocks), len(prose), marked


def check_all(agents_text: str, project_version: str | None,
              baseline: frozenset[str] = BASELINE_PROSE) -> tuple[list[str], str]:
    """跑三颗牙，返回 (problems, 读数行)。`baseline` 可注入（自检用）。"""
    p1, redlines, gated, unenforced = check_boundary(agents_text)
    p2, pins = check_pins(agents_text, project_version)
    p3, entries, prose, marked = check_admission(agents_text, baseline)
    problems = p1 + p2 + p3
    reading = (f"红线={redlines} 有门禁={gated} 未门禁={unenforced}"
               f" · 版本钉={pins}/{len(PINS)} · 条目={entries}（有标记={marked} 散文={prose}）")
    return problems, reading


# ==================== 自检（`--selftest`：1 对照臂 + 正对照 + 红臂，不碰真树） ====================

# 自检用的小基线（注入 `check_admission(..., baseline=SB)`）——
# ⚠️ 刻意**不用真基线**：真基线有 17 条，合成文本里凑不齐 ⇒ `missing` 会误报（第一版就踩了）。
_BASE_BULLET = "- 自检基线条目：本条只用于自检。"
# ⚠️ 基线里存的是**指纹**（首行前 20 字符），不是前缀 ⇒ 必须用同一个 `fingerprint()` 生成。
SB: frozenset[str] = frozenset({fingerprint(_BASE_BULLET)})


def _agents(boundary: str = "- 一条红线。`[gate: check-redline-gates.sh]`",
            other: str = _BASE_BULLET, extra: str = "") -> str:
    return f"{SECTION}\n\n{boundary}\n\n## 别的节\n\n{other}\n{extra}"


def selftest() -> int:
    cases: list[tuple[str, str, str, bool]] = [
        # (名字, AGENTS 文本, 项目版本, 期望 problems 是否为空)
        ("A1 对照臂：正常（应全绿）", _agents(), "1.20.1", True),
        ("R1 版本钉错（真树本轮的活反例）",
         _agents("- 内核路线，先对照 `/home/fb486/projects/reference/baritone/`。"
                 "`[gate: check-kernel-predicates.sh]`"), "1.20.1", False),
        ("A2 版本钉对（正对照）",
         _agents("- 内核路线，先对照 `reference/baritone-1.20.1/`。"
                 "`[gate: check-kernel-predicates.sh]`"), "1.20.1", True),
        ("R2 新增无标记条目（散文偷偷长出来）",
         _agents(extra="\n- 一条新的散文规则，没有任何标记"), "1.20.1", False),
        ("A3 新增 + `[用户确认]`（正对照：显式同意的能进来）",
         _agents(extra="\n- 一条新规则。`[用户确认: 2026-09-28]`"), "1.20.1", True),
        ("R3 基线条目静默消失", _agents(other=""), "1.20.1", False),
        ("R4 红线什么都不标", _agents("- 一条红线，什么都没标。"), "1.20.1", False),
        ("R5 未门禁缺 `复核触发`",
         _agents("- 一条红线。`[未门禁: 没有判据]`"), "1.20.1", False),
        ("R6 假指针（脚本不存在）",
         _agents("- 一条红线。`[gate: check-not-there.sh]`"), "1.20.1", False),
        ("R7 `gate` 与 `未门禁` 双标",
         _agents("- 一条红线。`[gate: check-redline-gates.sh]`"
                 "`[未门禁: 没有判据；复核触发: 某天]`"), "1.20.1", False),
        ("R8 找不到红线小节（结构变了）",
         "# 什么都没有\n", "1.20.1", False),
    ]
    bad = 0
    for name, text, version, want_ok in cases:
        try:
            problems, _ = check_all(text, version, SB)
        except SystemExit:
            problems = ["SystemExit：小节结构变了"]
        ok = not problems
        if ok != want_ok:
            bad += 1
        print(f"  [{'PASS' if ok == want_ok else 'FAIL'}] {name}  problems={len(problems)}")
        if ok != want_ok:
            for line in problems[:2]:
                print(f"         {line[:130]}")
    print(f"REDLINE_GATE_SELFTEST {'PASS' if bad == 0 else 'FAIL'}: "
          f"cases={len(cases)} mismatched={bad}")
    return 0 if bad == 0 else 1


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    text = AGENTS.read_text(encoding="utf-8")
    project_version = gradle_property(ROOT, "minecraft_version")
    problems, reading = check_all(text, project_version)
    for line in problems:
        print(f"[G3·红线门禁] {line}")
    ok = not problems
    print(f"REDLINE_GATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: {reading}"
          f" · problems={len(problems)}"
          f"（牙①结构：指针必须真实存在 · 牙②内容：版本钉必须与本项目一致 · "
          f"牙③准入：只许经显式同意新增、旧条目不许静默消失）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
