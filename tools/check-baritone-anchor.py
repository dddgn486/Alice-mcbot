#!/usr/bin/env python3
"""⭐ **Baritone 对照门禁**（`O41` A③ 的牙；口径 = `D-036` 规则 2/4/5 ＋ `D-532` §八 裁定 2）。

## 为什么这条规则该存在

`D-532` §八 裁定 2 逐字：**「每条内核改动必须给 Baritone 对照（`文件:行`），无对照则显式登记
『Alice 特有 ＋ 理由』」** —— 而 `D-430`（内核关门线）失效的根因正是**约束没有锚**：
站位枚举被改了无数次，因为**没有任何一处**说得出它该长什么样
（`survey/48` §1.1；`O29` §7/§8 实测「**Baritone 的挖掘从不枚举站位**」）。

⚠️ 本刀之前，这条规则的**载体 = 提交信息 ＋ 人的记忆** ⇒ `O41` A③ 把它做成**能红的判据**。

## 判据（谁会被判 / 什么算过）

- **判谁**：`48b5619b..HEAD`（批次 1 起点）里**动过内核路径**、**且改了代码**的提交。
  ⚠️ **剥注释后没有实质行 ⇒ 跳过**（纯注释/文档改动不该被要求写锚 —— 例：`56ac28c8` 那个
  「退役说明」词形替换）。
- **算过**：该提交在 `docs/BARITONE_ANCHORS.md` 里有**一行**，且其对照字段是三类之一：
  ① 真锚 `<File>.java:NN(-NN)` ；② `Alice 特有：<具体理由>`（理由 ≥ 6 字符）；
  ③ `待补锚：<归属刀>` ⇒ ⚠️ **这一类的计数 > 0 即判红**（"覆盖率 100%"的字面意思）。
- **反空转（4 条，⛔ 不许"没扫到"被读成"通过"）**：
  ① 区间非空；② 区间内**至少有 1 笔**动过内核路径（否则路径清单过期）；
  ③ 内核路径在树里的**人口下限**（`KERNEL_MIN_FILES`）；④ `--selftest` 的**分类器合成臂**每次都跑。
- ⛔ **本门禁不判"对照对不对"** —— 机器只判"有没有一行、这一行是否可分类"。内容对错仍要人核
  （这是 `D-532` §八 的原意：**先有记录**，才有可核的对象）。

## 三类之外的一律红

`|` 行缺列 · 提交不在区间内（陈旧行）· 提交有代码改动但**无行** · 对照字段为空或无法分类。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
REGISTRY = REPO / "docs" / "BARITONE_ANCHORS.md"

# ⚠️ 起点：批次 1 设计单（`O31`）。之前的提交**不判**（历史不追溯）。
BASE = "48b5619b"

# ⚠️ 内核路径清单 = `D-532` §九 冻结清单里"内核"那一半 ＋ `D-036` 的差异面。
# 与 `tools/check-frozen-code.py` 的 `FREEZE_REGISTRY` 是**两个用途**（那个判"冻结内容有没有被改"，
# 本门禁判"改了有没有锚"）⇒ 允许各自维护，但**同一刀改一条时要互看一眼**。
KERNEL_PREFIXES = (
    "src/main/java/com/dddgn/alice/pathing/",
    "src/main/java/com/dddgn/alice/reach/",
    "src/main/java/com/dddgn/alice/action/MineBlockRunner.java",
    "src/main/java/com/dddgn/alice/action/BlockInteraction.java",
    "src/main/java/com/dddgn/alice/task/mining/",
    "src/main/java/com/dddgn/alice/task/MineTask.java",
    "src/main/java/com/dddgn/alice/job/mine/",
    "src/main/java/com/dddgn/alice/job/fishbone/",
)

# 人口下限（反空转 ③）：内核路径今天匹配多少个 `.java`（实测远大于此；⛔ 不写死精确值 ⇒ 会腐烂）
KERNEL_MIN_FILES = 60

ANCHOR_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\.java:\d+(?:-\d+)?")
SPECIFIC_RE = re.compile(r"Alice\s*特有[^：:\n]{0,12}[：:]\s*\**\s*(\S.*)")
PENDING_RE = re.compile(r"待补锚\s*[:：]\s*(\S+)")
ROW_RE = re.compile(r"^\|\s*`([0-9a-f]{7,40})`\s*\|(.+?)\|(.+?)\|\s*$")


def sh(*args: str) -> str:
    return subprocess.run(args, cwd=REPO, capture_output=True, text=True, check=True).stdout


def classify(cell: str) -> tuple[str, str]:
    """⇒ ('anchor'|'alice'|'pending'|'invalid', 说明)。**这是唯一的分类入口**（合成臂测它）。"""
    cell = cell.strip()
    if not cell:
        return "invalid", "对照字段为空"
    if PENDING_RE.search(cell):
        return "pending", PENDING_RE.search(cell).group(1)
    if ANCHOR_RE.search(cell):
        return "anchor", ANCHOR_RE.search(cell).group(0)
    m = SPECIFIC_RE.search(cell)
    if m and len(m.group(1).strip()) >= 6:          # 理由**非空且具体**（⛔ 光四个字不算）
        return "alice", m.group(1).strip()[:40]
    if "Alice 特有" in cell:
        return "invalid", "写了「Alice 特有」但**没给理由**"
    return "invalid", "既不是 `<File>.java:NN`、也不是 `Alice 特有：<理由>` / `待补锚：<刀>`"


def strip_comments(text: str) -> str:
    """剥 `//` 与 `/* … */`（跨行状态机）；行首 `*` 的续行也一并吃掉。"""
    out, i, n, block = [], 0, len(text), False
    while i < n:
        if block:
            j = text.find("*/", i)
            if j < 0:
                i = n
                break
            i = j + 2
            block = False
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            block = True
            i += 2
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def code_lines(commit: str) -> list[str]:
    """⇒ 该提交在**内核路径上真正改动的代码行**（判"是否只是注释/文档改动"）。

    ⚠️ **为什么不用"逐行剥注释"**：diff 的差集里看不到 javadoc 的 `/**` 开头 ⇒ 逐行剥注释会把
    纯注释改动（例：`56ac28c8` 的「退役说明」词形替换）误判成"改了代码"。
    ⇒ 改成**整文件剥注释后比对**（那时 `/**` 在，状态机才准）。
    """
    touched = []
    for f in kernel_files(commit):
        try:
            old = sh("git", "show", f"{commit}^:{f}")
        except subprocess.CalledProcessError:
            old = None                      # 新增文件 ⇒ 必然算"改了代码"
        try:
            new = sh("git", "show", f"{commit}:{f}")
        except subprocess.CalledProcessError:
            new = None                      # 删除文件 ⇒ 同样算
        if old is None or new is None:
            touched.append(f + ("（新增）" if old is None else "（删除）"))
            continue
        if strip_comments(old) != strip_comments(new):
            touched.append(f)
    return touched


def kernel_files(commit: str) -> list[str]:
    out = sh("git", "show", "--name-only", "--format=", commit, "--", *KERNEL_PREFIXES)
    return [l for l in out.splitlines() if l.startswith("src/")]


def load_registry() -> tuple[dict[str, str], list[str]]:
    rows: dict[str, str] = {}
    problems: list[str] = []
    for n, line in enumerate(REGISTRY.read_text(encoding="utf-8").splitlines(), 1):
        if not line.startswith("|") or line.startswith("|---") or "提交" in line:
            continue
        m = ROW_RE.match(line)
        if not m:
            if "`" in line and re.search(r"`[0-9a-f]{7,40}`", line):
                problems.append(f"{REGISTRY.name}:{n} 行格式不合法（要三列：提交 | 内核面 | 对照）")
            continue
        commit, _face, cell = m.group(1), m.group(2), m.group(3)
        rows[commit] = cell
    return rows, problems


def selftest() -> list[str]:
    """⭐ 分类器合成臂（反空转 ④）：口径自证 —— 改坏分类器 ⇒ 这里先红。"""
    cases = [
        ("`MovementHelper.java:68-100`", "anchor", "真锚"),
        ("**Alice 特有**：目标形状由 `D-520` 裁定新增", "alice", "Alice 特有＋理由"),
        ("待补锚：归属 1-4 同刀", "pending", "待补锚（可见负债）"),
        ("Alice 特有", "invalid", "只写四个字、没理由 ⇒ 不认"),
        ("就照着感觉写的", "invalid", "既非锚也非登记 ⇒ 不认"),
        ("", "invalid", "空 ⇒ 不认"),
    ]
    bad = []
    for text, want, why in cases:
        got, _ = classify(text)
        if got != want:
            bad.append(f"合成臂：`{text}` ⇒ 期望 {want}，实得 {got}（{why}）")
    return bad


def main() -> int:
    rows, problems = load_registry()
    fails = list(problems)
    fails += selftest()

    commits = sh("git", "log", "--format=%H", f"{BASE}..HEAD").split()
    if not commits:
        fails.append(f"区间 `{BASE}..HEAD` 为空 ⇒ 反空转：本门禁什么都没判")

    judged, skipped, missing = [], [], []
    for c in commits:
        if not kernel_files(c):
            continue
        if not code_lines(c):
            skipped.append(c)
            continue
        judged.append(c)
        if not any(c.startswith(k) or k.startswith(c) for k in rows):
            missing.append(c)

    if commits and not judged and not skipped:
        fails.append("区间内**没有一笔**动过内核路径 ⇒ 反空转：内核路径清单可能过期")

    tree = sh("git", "ls-files", "--", *KERNEL_PREFIXES).splitlines()
    if len(tree) < KERNEL_MIN_FILES:
        fails.append(f"内核路径只匹配到 {len(tree)} 个文件 < 下限 {KERNEL_MIN_FILES} ⇒ 反空转")

    counts = {"anchor": 0, "alice": 0, "pending": 0, "invalid": 0}
    detail: list[tuple[str, str, str]] = []
    for c in judged:
        hit = next((k for k in rows if c.startswith(k) or k.startswith(c)), None)
        if hit is None:
            continue
        kind, note = classify(rows[hit])
        counts[kind] += 1
        detail.append((c[:8], kind, note))

    for k in rows:
        if not any(c.startswith(k) or k.startswith(c) for c in commits):
            fails.append(f"登记表里的 `{k[:8]}` **不在区间 `{BASE}..HEAD` 内** ⇒ 陈旧行（删掉或改对）")
    for c in missing:
        fails.append(f"内核提交 **{c[:8]}** 改了代码但登记表里**没有行**"
                     f"（{', '.join(f.split('/')[-1] for f in kernel_files(c)[:4])}）")
    for c, kind, note in detail:
        if kind == "invalid":
            fails.append(f"内核提交 **{c}** 的对照字段无法分类：{note}")
        if kind == "pending":
            fails.append(f"内核提交 **{c}** 是**待补锚**（归属 {note}）⇒ 覆盖率**不到 100%**（`O41` A③ 的字面要求）")

    if fails:
        print("BARITONE_ANCHOR_RESULT FAIL: " + str(len(fails)) + " 项")
        for f in fails:
            print(f"  ✗ {f}")
        return 1

    print(f"BARITONE_ANCHOR_RESULT PASS: 区间 `{BASE}..HEAD` N={len(commits)} · "
          f"**内核提交 {len(judged)}** 笔 · 真锚 {counts['anchor']} · Alice 特有 {counts['alice']} · "
          f"待补 {counts['pending']} · 纯注释跳过 {len(skipped)}")
    for c, kind, note in detail:
        print(f"  · {c} [{kind}] {note}")
    print(f"  ⭐ 三类之外一律红；`待补锚>0` 即红（覆盖率 100% 的字面意思）")
    print(f"  反空转 4 条：区间非空 ✓ · 区间内至少一笔动过内核 ✓ · 内核文件人口 {len(tree)} ≥ {KERNEL_MIN_FILES} ✓ · "
          f"分类器合成臂 {len(selftest()) == 0 and '6/6' or 'FAIL'} ✓")
    print("  ⛔ 不覆盖：对照**内容对不对**（机器只判「有没有一行、能否分类」）· 非内核路径 · 历史（`" + BASE + "` 之前）")

    # ⚠️ 警告面：Alice 特有 : 真锚 的比例是**可读指标**（`survey/48` §1.1 的病根量化）
    if counts["alice"] > counts["anchor"]:
        print(f"  ⚠️ **可读指标**：`Alice 特有 : 真锚 = {counts['alice']} : {counts['anchor']}` —— "
              f"内核里**没有 Baritone 参照物**的部分有多大；⛔ 别把它读成「都合规所以没事」")
    return 0


if __name__ == "__main__":
    sys.exit(main())
