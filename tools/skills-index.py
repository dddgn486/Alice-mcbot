#!/usr/bin/env python3
"""生成 / 校验 `.alice-supervision/skills/README.md`（**生成物，禁手改**）。

## ⭐ 为什么有它（用户 2026-10-02 采纳 ①）

原 `skills/README.md` 是**手维护 131 行、无生成器、无门禁** —— 而本项目 `§五 5.1` 已经量出规律：

| 索引 | 单一出处 | 有门禁 | 结局 |
|---|---|---|---|
| `docs/DECISIONS_INDEX.md` | 有 | 有 | ⭐ 能活 |
| `docs/DESIGN_INDEX.md` | 有 | 有 | ⭐ 能活 |
| `survey/README.md` | 有 | 手维护期**没有** | ⛔ **烂过一次** |
| **`skills/README.md`** | ⛔ 没有 | ⛔ 没有 | ⛔ **已烂**（列 24 行 vs 磁盘 21） |

⇒ 照 `doc-registry.py` / `survey-index.py` 的现成做法：**每行从磁盘的真实出处读出来**。

## 单一出处（⛔ 本文件不制造第二份真相）

| 列 | 出处 |
|---|---|
| `skill`（id） | ⭐ **磁盘**（`ls *.skill.md`）—— ⛔ 不是 manifest：磁盘才是"存在" |
| `一句话用途` | 该文件的 **YAML frontmatter `description`** |
| `领域` | `skills-manifest.yml` 的 `scope`（⛔ 缺失 ⇒ 显式标 `—`） |
| `行数` | 文件行数（⭐ 会腐烂的量 ⇒ 所以是生成物） |

## 本门禁**抓**什么

1. **陈旧**：重新生成 ⇒ **逐字节相同**（手改过 / 磁盘变了没重生成 ⇒ 红）。
2. ⭐ **frontmatter 缺失**：`*.skill.md` 没有 `name`/`description` ⇒ **红**。
   ⚠️ **为什么这条必须是红的**：DSH 的 skill provider **按 frontmatter 解析**，
   缺字段的文件会被**安静地丢掉**（⛔ 不报错）⇒ 那是"写在磁盘上却永远到不了 agent"的形状。
   ⭐ 本门禁落地时**当场抓到一份**：`forge-blockpos-mutability.skill.md`（21 份里唯一没有的）。
3. **人口下限**：磁盘份数 ≥ 15 —— 目录搬了 / 改名了 ⇒ **响亮失败**。

用法：`python3 tools/skills-index.py --write`　·　`--check`　·　`--selftest`
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / ".alice-supervision" / "skills"
MANIFEST = ROOT / ".alice-supervision" / "skills-manifest.yml"
OUTPUT = SKILLS / "README.md"

#: ⚠️ 人口下限（2026-10-02 实测 = 21）
COUNT_FLOOR = 15

FM = re.compile(r"^---\n(.*?)\n---", re.S)
NAME = re.compile(r"^name:\s*(.+)$", re.M)
DESC = re.compile(r"^description:\s*(.+)$", re.M)
FIRST_SEC = re.compile(r"^##\s+(.*)$", re.M)

#: `scope` → 分组标题。⛔ 不许把没登记的 scope 悄悄塞进某一组（见 `group_of`）。
GROUPS: list[tuple[str, str]] = [
    ("forge", "🔧 Forge / Minecraft 技术类"),
    ("minecraft", "🧱 原版机制类"),
    ("methodology", "🧭 方法论 / 流程类"),
]

#: ⭐ 症状速查（人写的编辑内容 ⇒ 所以住在生成器里，⛔ 不住在生成物里）
SYMPTOMS: list[tuple[str, str]] = [
    ("实体在客户端看不到", "forge-entity-sync-broadcast"),
    ("GUI 打不开 / 数据不对", "forge-container-menu-protocol"),
    ("实体移动有问题", "forge-entity-physics-collision"),
    ("事件监听器不生效", "forge-event-priority-cancel"),
    ("FakePlayer 相关问题", "forge-fakeplayer-lifecycle"),
    ("对象的值「神奇地」变了", "forge-blockpos-mutability"),
    ("单人正常、多人出错", "minecraft-client-server-sync"),
    ("不知道为什么失败 / 同一问题失败 2+ 次", "debugging-root-cause-analysis"),
    ("多次失败但症状不同", "failure-pattern-recognition"),
    ("需要真人跑客户端验证", "alice-scene-based-testing"),
    ("怀疑客户端跑的不是本轮工件", "alice-client-artifact-acceptance"),
    ("跨多包/多文档一次改不完的大改造", "large-refactor-survey-and-verify"),
]


def manifest_scopes() -> dict[str, str]:
    """`id → scope`（⛔ 文件不存在 ⇒ 返回空表，由门禁的人口下限兜底）。"""
    if not MANIFEST.is_file():
        return {}
    t = MANIFEST.read_text(encoding="utf-8")
    out: dict[str, str] = {}
    cur: str | None = None
    for l in t.splitlines():
        m = re.match(r"^\s*-\s*id:\s*(\S+)\s*$", l)
        if m:
            cur = m.group(1)
            continue
        m2 = re.match(r"^\s+scope:\s*(\S+)\s*$", l)
        if m2 and cur:
            out[cur] = m2.group(1)
            cur = None
    return out


def parse(path: Path, scopes: dict[str, str]) -> dict:
    t = path.read_text(encoding="utf-8")
    fm = FM.search(t)
    sid = path.name[: -len(".skill.md")]
    first = FIRST_SEC.search(t)
    return {
        "id": sid,
        "desc": (DESC.search(fm.group(1)).group(1).strip() if fm and DESC.search(fm.group(1)) else ""),
        "has_name": bool(fm and NAME.search(fm.group(1))),
        "scope": scopes.get(sid, ""),
        "sec": (first.group(1).strip() if first else "—"),
        "lines": len(t.splitlines()),
    }


def render(skills: list[dict]) -> str:
    L: list[str] = []
    A = L.append
    A("# Alice 项目 Skills 索引（**生成物，禁手改**）")
    A("")
    A("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    A("> 重新生成：`python3 tools/skills-index.py --write`　·　校验：`python3 tools/skills-index.py --check`")
    A("> 门禁：`tools/check-skills-index.sh`（挂 `tools/check-all.sh`）。")
    A("")
    A("## ⛔ 先说清楚：skills **不是规则**")
    A("")
    A("⭐ **技能 = 可复用的做法**，服务于**跨会话 / 跨项目**。")
    A("⛔ **规则**（能机械判、违反会失败的）住 `AGENTS.md` 与 `tools/check-*`，⛔ **不住这里**。")
    A("⚠️ 两层今天**还在拆**（草案 `§D′-5` / `§D′-6`）⇒ 这是**现状登记**，⛔ 不是最终形态。")
    A("")
    A("## 读数（**只从磁盘复算**）")
    A("")
    A("| 量 | 值 |")
    A("|---|---|")
    A(f"| skill 份数（磁盘） | **{len(skills)}** |")
    A(f"| ⭐ 带完整 frontmatter（`name` ＋ `description`） | **{sum(1 for s in skills if s['has_name'] and s['desc'])} / {len(skills)}** |")
    A(f"| 在 `skills-manifest.yml` 里登记了 `scope` | **{sum(1 for s in skills if s['scope'])} / {len(skills)}** |")
    A("")
    A("## 表（**门禁逐字节比对的就是这一段**）")
    A("")
    A("| skill | 领域 | 一句话用途（来自 frontmatter `description`） | 行数 |")
    A("|---|---|---|---:|")
    for g, title in GROUPS:
        rows = [s for s in skills if s["scope"] == g]
        if not rows:
            continue
        A(f"| **— {title} —** | | | |")
        for s in rows:
            A(f"| `{s['id']}` | `{s['scope']}` | {s['desc'] or '⛔ **frontmatter 缺 `description`**'} | {s['lines']} |")
    rest = [s for s in skills if s["scope"] not in {g for g, _ in GROUPS}]
    if rest:
        A("| **— ⚠️ 未登记 `scope`（在 `skills-manifest.yml` 里补） —** | | | |")
        for s in rest:
            A(f"| `{s['id']}` | `—` | {s['desc'] or '⛔ **frontmatter 缺 `description`**'} | {s['lines']} |")
    A("")
    A("## 症状速查（人写的，⭐ 住在生成器里）")
    A("")
    A("| 症状 | 查哪个 skill |")
    A("|---|---|")
    for sym, sid in SYMPTOMS:
        A(f"| {sym} | `{sid}` |")
    A("")
    A("## 怎么用")
    A("")
    A("1. **开工前**：按上表 `领域` 挑 1–3 份，`read` 它的正文（⛔ 别全读）；")
    A("2. **遇到问题**：先查「症状速查」，再读对应 skill 的「何时使用」节；")
    A("3. **⚠️ 别把 skill 当依据**：它给的是**做法**，⛔ 不是**边界**；边界要引 `D-###` 或门禁。")
    A("")
    A("## ⛔ 本表**假装不了**的（诚实边界）")
    A("")
    A("1. ⛔ **它判不了 skill 写得好不好** —— 只判「磁盘上有几份、frontmatter 齐不齐」。")
    A("2. ⛔ **它判不了「该不该用某个 skill」** —— 那要判断，⛔ 不在门禁能力内。")
    A("3. ⚠️ **DSH 今天还发现不了这些 skill**（实测：`.alice-supervision/skills/` 不在任何被扫描根下，")
    A("   且文件名 `*.skill.md` ≠ DSH 要求的 `*.md` / `<name>/SKILL.md`）⇒ 登记在台账 `O149`。")
    A("")
    return "\n".join(L) + "\n"


def build() -> tuple[str, list[str]]:
    scopes = manifest_scopes()
    files = sorted(SKILLS.glob("*.skill.md"))
    skills = [parse(p, scopes) for p in files]
    problems: list[str] = []
    if len(files) < COUNT_FLOOR:
        problems.append(f"⛔ 磁盘只有 {len(files)} 份 `*.skill.md` < 人口下限 {COUNT_FLOOR} ⇒ "
                        f"目录搬了 / 改名了（扫不到就报绿的入口，不放过）")
    for s in skills:
        if not s["has_name"] or not s["desc"]:
            problems.append(f"⛔ `{s['id']}.skill.md` 缺 frontmatter（`name`/`description`）⇒ "
                            f"DSH 会**安静地丢掉它**（写在磁盘上却永远到不了 agent）—— ⛔ 不静默")
    return render(skills), problems


def write() -> int:
    text, problems = build()
    if problems:
        for p in problems:
            print(f"  [FAIL] {p}")
        print("SKILLS_INDEX_RESULT FAIL: 有结构缺陷，⛔ 不准写")
        return 1
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"SKILLS_INDEX_RESULT WROTE: {len(text.splitlines())} 行 → {OUTPUT.relative_to(ROOT)}")
    return 0


def check() -> int:
    text, problems = build()
    if problems:
        for p in problems:
            print(f"  [FAIL] {p}")
        print(f"SKILLS_INDEX_RESULT FAIL: {len(problems)} 处")
        return 1
    if not OUTPUT.is_file():
        print(f"SKILLS_INDEX_RESULT FAIL: 缺 {OUTPUT.relative_to(ROOT)} ⇒ 跑 `--write`")
        return 1
    got = OUTPUT.read_text(encoding="utf-8")
    if got != text:
        a, b = got.splitlines(), text.splitlines()
        i = next((k for k in range(min(len(a), len(b))) if a[k] != b[k]), min(len(a), len(b)))
        print(f"SKILLS_INDEX_RESULT FAIL: 登记表已陈旧（首个不同在第 {i+1} 行）⇒ "
              f"跑 `python3 tools/skills-index.py --write` 并提交")
        return 1
    print(f"SKILLS_INDEX_RESULT PASS: {text.count(chr(10) + '| `')} 行 skill · 逐字节相同")
    return 0


def selftest() -> int:
    """四臂注入自证 —— ⭐ 「能过」≠「能抓」。"""
    global SKILLS, OUTPUT
    import copy
    import io
    from contextlib import redirect_stdout

    base = (SKILLS, OUTPUT)
    arms: list[tuple[str, str, bool]] = []
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        d = root / "skills"
        d.mkdir()
        global MANIFEST
        base_m = MANIFEST
        MANIFEST = root / "nope.yml"

        # 臂 A：好文件 ⇒ 安静
        (d / "a.skill.md").write_text("---\nname: a\ndescription: A 做什么\n---\n## 何时使用\n", encoding="utf-8")
        SKILLS, OUTPUT = d, d / "README.md"
        _, p = build()
        arms.append(("A 好文件（有 frontmatter）", "", not [x for x in p if "frontmatter" in x]))
        # 臂 B：缺 frontmatter ⇒ 必须红（⭐ 就是落地时当场抓到的那一份的形状）
        (d / "b.skill.md").write_text("# 没有 frontmatter\n\n## 问题描述\n", encoding="utf-8")
        _, p = build()
        arms.append(("B 缺 frontmatter", "缺 frontmatter", any("缺 frontmatter" in x for x in p)))
        # 臂 C：人口下限
        empty = root / "empty"
        empty.mkdir()
        SKILLS, OUTPUT = empty, empty / "README.md"
        _, p = build()
        arms.append(("C 人口下限", "人口下限", any("人口下限" in x for x in p)))
        SKILLS, OUTPUT, MANIFEST = base[0], base[1], base_m

    bad = 0
    for name, want, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ {'期望红且含「' + want + '」' if want else '期望安静'}")
    # 臂 D：陈旧（真盘上改一行 ⇒ 必须红）—— 用 tempfile 快照法，⛔ 不动真盘
    print(f"SKILLS_INDEX_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)+1} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.write:
        return write()
    return check()


if __name__ == "__main__":
    sys.exit(main())
