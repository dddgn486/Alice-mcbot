#!/usr/bin/env python3
"""**设计索引**（`docs/DESIGN_INDEX.md`）：把「**每个顶层包的设计说明在哪**」压成一张能读的表。

## 为什么有它（用户 2026-10-02 逐字）
> 「项目也确实需要**一个设计总文档**，至少要带上**散落的设计索引**吧，**索引从下往上维护**」

⭐ **"从下往上"= 索引是 ⟵ 生成物，真相在 `package-info.java`**：
设计说明住在**它描述的那个包的** `package-info.java` 里（改代码的人顺手就能改），
本索引只把它们**汇总**起来 ⇒ ⛔ **它不新增任何事实**（与 `docs/DECISIONS_INDEX.md` 同一条纪律）。

## 它解决什么（实测人口，2026-10-02）
`src/main/java/com/dddgn/alice/` 下 **30 个顶层包**，其中只有 **9 个**有 `package-info.java`
⇒ ⛔ **21 个包"没有设计说明"这件事，今天在任何地方都看不见**。
本索引把这件事**摆到台面上**（`设计说明` 列显示 `—`）⇒ 它**不假装完整**。

## 它**不是**什么（防误用）
* ⚠️ **不是设计本身**：要读设计去那个包的 `package-info.java`（表里有路径）。
* ⚠️ **不是裁定**：`受哪些 D-### 约束` 一列是**从 `package-info.java` 里扫出来的引用**，
  不是"这些决策只管这个包"的判断 —— 扫不到就可能**真的没引**，也可能是**引了别的写法**。
* ⚠️ **它不判断设计好坏**：`—` 只表示"**这个包没有 `package-info.java`**"，⛔ 不代表这个包不重要。

## 门禁（`tools/check-design-index.sh`）
* **人口下限**：顶层包数 ≥ 25（⛔ 解析崩塌不许"少读一截还绿"）；
* **一致**：索引与**实际顶层包集合**逐一对应（少一个包 ⇒ 红 · 多一个包 ⇒ 红）；
* **不陈旧**：重新生成 ⇒ 逐字节相同。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"
PKG_ROOT = SRC / "com" / "dddgn" / "alice"
OUTPUT = ROOT / "docs" / "DESIGN_INDEX.md"

#: ⚠️ 人口下限：2026-10-02 实测 **30** 个顶层包。留余量但拦得住崩塌
#:（写坏路径 ⇒ 扫到 0 个包 ⇒ 必须**响亮失败**，⛔ 不许"索引里只剩一个包"也照样绿）
#:
#: ⭐⭐ **`A3`（2026-10-05 开发者裁「作为缺口可以马上批」）**：本表从"**只有下限**"扩成
#:   **两个方向**，形状 = `{量: (比较符, 阈值)}` —— ⭐ **两种判据共用同一个循环**（⛔ 不许写第二份，
#:   那正是本仓那族"同一口径两份 ⇒ 必然漂移"的错）。
#:   · `>=` = **人口下限**：防**静默删空**（表/包/设计件被删空也算通过 ⇒ 拦住）；
#:   · `<=` = ⭐ **只许变短**（= 用户裁的「人口下限 ＋ 只许变短」这条形态，见咨询回执 `001` 的 `T2`）。
#:
#: ⭐ 新立的两条**专治"某包没有设计"**（`A3` 的原话：**今天 23 个缺口不报红**）：
#:   `顶层包有设计说明 >= 7` 防**覆盖倒退**；`顶层包缺设计说明 <= 23` 防**缺口变大**。
#:   ⚠️ **只加下限是不够的**：顶层包数下限是 25，若有人加 5 个新包却不写设计说明，
#:   「有设计说明」可以恒 ≥ 7 **而缺口一路涨到 28** ⇒ ⭐ **必须同时有上界**，否则那条判据"看起来有、其实拦不住"。
#:   ⚠️ 上界的**代价是明说的**：此后**新增顶层包必须同时补它的 `package-info.java`**，否则本门禁红。
#:      ⭐ 那正是 `A3` 要的效果（缺口**只许还、不许涨**）；⚠️ 若日后包结构调整导致它假红，
#:      **改这条数要走裁定**，⛔ 不许就地放宽。
ASSERTIONS: dict[str, tuple[str, int]] = {
    "顶层包数": (">=", 25),
    "顶层设计件": (">=", 8),
    "顶层包有设计说明": (">=", 7),
    "顶层包缺设计说明": ("<=", 23),
}

#: ═══════════════════════════════════════════════════════════════════════════
#: ⭐⭐ 第二层：`docs/` 根下的**设计件**（2026-10-02 用户裁定后新增）
#: ═══════════════════════════════════════════════════════════════════════════
#: 用户 2026-10-02 逐字（纠正我的 `docs/designs/` 建议）：
#: 「`docs/designs/` **不太适合当「施工设计书」**，**会被误认为是项目的设计文档**，
#:  实际上**大型施工只有草案或者说定案，加上施工计划书**，这两个**不是"设计的载体"，是设计的结果**，
#:  是要**被反复修正**的。」
#: ⇒ ⭐ **不建 `docs/designs/`** —— **它已经有了，就是 `docs/` 根**：
#:   系统级／包级设计件（`*_DESIGN.md` / `*_ARCHITECTURE.md` / `*_CONTRACT.md` …）一直住在 `docs/` 根。
#: ⇒ ⛔ **但今天它们一份都没进索引**（本文件原来只收 `package-info.java`）⇒ 这就是本层的由来。
DESIGN_GLOBS = ("*_DESIGN.md", "*_DESIGN_DRAFT.md", "*_ARCHITECTURE.md", "*_CONTRACT.md",
                "*_FORM.md", "*_COMPARISON.md", "*_SELECTION_DESIGN.md",
                # ⭐ 族式命名（一份设计被拆成 R1/R2/… 时用）—— 2026-10-02 由覆盖自证当场抓出来
                "ALICE_PATHING_CORE_*.md",
                # ⚠️ 名字里没有 DESIGN 字样、但内容是设计（「仅记录后续接入时必须保留的身份边界」= 定案）
                "MULTI_BOT_INTERFACE_RESERVATION.md")

#: ⛔ **反向**：`docs/` 根里**不是设计件**的，必须逐类点名。
#: ⭐ 双向自证：**(a)** 磁盘上每个 `docs/*.md` 要么进表、要么在这里被点名；
#:            **(b)** 这里点名的每个模式**必须在磁盘上真能命中**（⛔ 拼错 = 静默失效 ⇒ 响亮失败）。
NON_DESIGN_PATTERNS: list[tuple[str, str]] = [
    ("CLOUD_MIGRATION.md", "基础设施手册（⛔ 不是设计件；自述「不改项目契约、不进开发排期、不构成实现授权」）"),
    ("DECISIONS_INDEX.md", "③ 类生成物（裁定索引）"),
    ("DESIGN_INDEX.md", "④ 类生成物（本文件自己）"),
    ("DOC_REFACTOR_PLAN.md", "施工计划书（**施工期唯一执行入口**）"),
    ("DOC_REFACTOR_DRAFT.md", "文档改革**草案**（⭐ 依据 —— 与计划书同类，⛔ 不是 mod 设计件）"),
    ("CLEANUP_CLASSIFY.md", "清旧家判据表（**生成物**；由 `tools/cleanup-classify.py` 生成 ＋ 门禁盯着）"),
    ("ACCEPTANCE_GUIDE.md", "验收指引"),
    #: ⭐ **2026-10-05 加**（咨询回执 `003` 落地）：它是**提取册**（容器），⛔ 不是设计件本身 ——
    #:   内容 = 从旧件里**提取**出来的设计／经验／判据；⭐ 它的作用是**落点**，判定它自己"是不是设计"没有意义。
    ("EXTRACTED_KNOWLEDGE.md", "提取册（容器 · 装从旧件提取的设计/经验/判据；由 `W7′-8` 工序产出）"),
    ("AI_*.md", "常驻件 ／ 旧态文档（`AI_DECISIONS` `AI_PROJECT_STATE` `AI_DEVELOPMENT_PLAYBOOK` `AI_CHANGELOG` `AI_TEST_MATRIX`）"),
    ("OPEN_ITEMS_LEDGER.md", "⑥ 类（进行中状态 · 待办总账）"),
    ("QUESTIONS_LEDGER.md", "⑥ 类（进行中状态 · 问题账）"),
    ("HANDOVER.md", "⑥ 类（进行中状态 · 断点历史）"),
    ("README.md", "文档地图（⛔ 不自称入口）"),
    ("EXPECTED_REDS.md", "预期红清单（门禁的基线数据）"),
    ("GLOSSARY.md", "术语表"),
    ("BARITONE_*.md", "Baritone 对照 ／ 锚点数据"),
    ("TESTING_GUIDE.md", "操作指南"),
    ("CAPABILITY_LIST.md", "生成物（能力清单）"),
    ("RISK_*.md", "风险系统件族（含 1 份 `RISK_SYSTEM_DESIGN_DRAFT.md` ⇒ ⚠️ 它**像**设计件但是**草案**，见下注）"),
    ("ALIGNMENT_OPEN_QUESTIONS.md", "未裁清单"),
    ("BARITONE_CONTRAST_TESTING.md", "对照测试说明"),
    ("BATTERY_CURATION.md", "电池策展说明"),
    ("CLIENT_AGENT_*.md", "客户端 agent 通道说明"),
    ("MEKANISM_FACTS.md", "上游事实（读数）"),
    ("THERMAL_*.md", "上游事实（读数）"),
    ("KNOWLEDGE_RECIPE_GRAPH_NOTES.md", "笔记（构想类）"),
    ("RISK_MODES_DISCUSSION.md", "讨论记录（构想类）"),
    ("MOD_ADAPTER_PROTOCOL.md", "协议说明"),
    ("MOD_COMPAT_CRAFT_STATION_PLAN.md", "计划（刀级 · ⚠️ 像设计件但按用户裁定属「施工域」）"),
    ("STAGE3A_CRAFT_PLAN.md", "计划（刀级）"),
    ("STAGE2_MODS_READABILITY.md", "审查件"),
    ("REVIEW_*.md", "审查件"),
    ("R4_BARITONE_ALIGNMENT_AUDIT.md", "审计件"),
    ("TRANSFER_MODULE_AUDIT.md", "审计件"),
    ("MINE_SURVEY_PROTOCOL.md", "勘测协议（方法，⛔ 不是某一次勘测的结果）"),
    ("WORLD_WRITE_AUTHORIZATION.md", "授权底账"),
]
DESIGN_DIRS = [("plans/", "刀级设计产出 ＋ 参考（**自述：讨论记录/草案/设计单**）⇒ 见 `docs/plans/README.md`"),
               ("reviews/", "报告 ／ 复核 ／ 审查（⑤ 类，**可引不可依据**）⇒ 见 `docs/reviews/README.md`")]


DNUM = re.compile(r"\bD-\d+\b")
HEADING = re.compile(r"^\s*\*\s*<h2>(.*?)</h2>\s*$")
JAVADOC_OPEN = re.compile(r"^\s*/\*\*")

#: 门禁比对到此行为止（本行以下是**易变列**：类数会随每次改动变 ⇒ 不进比对，只生成）
APPENDIX_MARKER = "## 附录：类数明细（**不计入门禁比对**）"


def strip_md(s: str) -> str:
    """把 JavaDoc 里混的 Markdown / HTML 标记压成纯文本（只用于**摘要显示**，⛔ 不改源文件）。"""
    s = re.sub(r"<[^>]+>", "", s)
    s = s.replace("{@code ", "").replace("}", "")
    s = re.sub(r"[*`]", "", s)
    return re.sub(r"\s+", " ", s).strip()


def package_dirs() -> list[Path]:
    """→ **所有含 `package-info.java` 的包**（任意深度），保证与 `find src -name package-info.java` 逐一对上。

    ⚠️ 2026-10-02 实测教训：第一版只返回 `PKG_ROOT` 的**直接子目录** ⇒
    含 `package-info.java` 的**子包**（`action/craft/` · `region/authz/`）**不在表里**，
    于是读数写成"缺 23"而实际是"缺 21" —— ⭐ **索引自己少列了两个包，却没有任何东西会红**。
    ⇒ 现在按"谁有 `package-info.java`"取，⛔ 不按目录深度取。
    """
    if not PKG_ROOT.is_dir():
        return []
    return sorted((f.parent for f in PKG_ROOT.rglob("package-info.java")), key=lambda p: p.as_posix())


def top_level_names() -> list[str]:
    """顶层包名（只用于**完整性自证**：`package-info` 的**拥有者数**不得少于顶层包数）。"""
    return sorted(d.name for d in PKG_ROOT.iterdir() if d.is_dir())


def all_package_names() -> list[str]:
    """全树所有包名（含子包）—— 与 `find src/main/java -name package-info.java` 的分母对齐。"""
    return sorted({str(f.parent.relative_to(SRC)).replace("/", ".")
                   for f in SRC.rglob("package-info.java")})


#: `package x.y.z;` 声明（⭐ 只认**行首**的 `package`，⛔ 不认注释里提到的）
PACKAGE_DECL = re.compile(r"^\s*package\s+([\w.]+)\s*;")


def package_declaration_check(src: Path = SRC) -> list[str]:
    """⭐⭐ **`A4` 的载体**（2026-10-05 开发者裁「**加一条静态判据**」）。

    **判据**：每个 `package-info.java` 的 `package` 声明**必须**与它**所在目录**一致。

    ⭐ **为什么需要它**（这是 `A4` 选的「可门禁」那半唯一的落地）：
      `A4` 定的**失效条件** = 「**被管的符号被删 / 改名**」。对 `package-info.java` 来说，
      被管的符号就是**它那个包**，于是拆成两种情形：

      · **包被删** ⇒ 文件随目录**一起消失** ⇒ ⛔ **无需判据**（自证）；
      · **包改名而 `package` 声明没跟着改** ⇒ ⚠️ 今天**只有编译器会拦**，
        而 ⛔ **`check-all` 不含编译**（`./gradlew build` 在 CI 层跑）
        ⇒ ⭐ **静态门禁层原本没有这一条判据**。本函数**把编译期才发现的事提到静态层**。

    ⚠️ ⭐ **它判不了**（如实记）：注释**内容**是否还描述着那个包的真实行为 ——
      那是「**行为变了、注释没改**」，⛔ **没有可靠机械判据**（`A4` 那半已明说是「靠人」）。

    ⭐ 参数 `src` **可换** ⇒ 本函数**能注入自证**（喂一棵临时树，⛔ 不必动 `src/`）。
    ⛔ 两个"扫不到就报绿"的口子都堵上：**路径写坏** 与 **一个都没扫到** 都**响亮失败**。
    """
    problems: list[str] = []
    if not src.is_dir():
        return [f"`{src}` **不存在** ⇒ 这一条判据**扫不到任何东西**（⛔ 不许「扫不到就报绿」）"]
    n = 0
    for f in sorted(src.rglob("package-info.java")):
        n += 1
        expected = f.parent.relative_to(src).as_posix().replace("/", ".")
        decl = next((m.group(1) for line in f.read_text(encoding="utf-8").splitlines()
                     if (m := PACKAGE_DECL.match(line))), None)
        rel = f.relative_to(ROOT).as_posix() if f.is_relative_to(ROOT) else f.relative_to(src).as_posix()
        if decl is None:
            problems.append(f"`{rel}` **没有 `package` 声明** ⇒ 认不出它属于哪个包"
                            f"（⛔ 这也会让「包被改名」那一路**永远发现不了**）")
        elif decl != expected:
            problems.append(f"`{rel}` 声明的是 `package {decl};`，而它住在 `{expected}` "
                            f"⇒ **不一致**（⭐ 编译期会报，但 `check-all` 不含编译 ⇒ 本判据补的就是那一条）")
    if n == 0:
        problems.append("全树**一个 `package-info.java` 都没扫到** ⇒ 路径写坏？"
                        "（⛔ 「扫不到」与「都没问题」必须能分辨）")
    return problems


def read_package_info(pkg: Path) -> dict:
    """→ {has, summary, sections, cites}。`has=False` ⇒ 其余为空。"""
    f = pkg / "package-info.java"
    if not f.is_file():
        return {"has": False, "summary": "", "sections": [], "cites": [], "lines": 0}
    text = f.read_text(encoding="utf-8")
    lines = text.splitlines()
    body: list[str] = []
    for line in lines:
        if JAVADOC_OPEN.match(line):
            continue
        if HEADING.match(line):
            break
        body.append(line)
    summary = ""
    for raw in body:
        s = raw.strip().lstrip("*").strip()
        if not s or s.startswith("<p>") is False and s.startswith(("<", "@")):
            if not s or s.startswith("@"):
                break
            continue
        s = re.sub(r"^<p>\s*", "", s)
        if not s or s.startswith(("<", "@")):
            continue
        summary = strip_md(s)
        if summary:
            break
    sections = [strip_md(m.group(1)) for line in lines if (m := HEADING.match(line))]
    return {
        "has": True,
        "summary": summary,
        "sections": sections,
        "cites": sorted(set(DNUM.findall(text)), key=lambda s: int(s[2:])),
        "lines": len(lines),
    }


def class_counts(pkg: Path) -> tuple[int, int]:
    return (len(list(pkg.glob("*.java"))), len(list(pkg.rglob("*.java"))))


def collect() -> list[dict]:
    """→ **每个"有 `package-info.java` 的包"** ＋ **每个顶层包**（两者取并集，⛔ 一个都不许漏）。

    ⚠️ 并集是必须的：只取前者 ⇒ 21 个没设计的顶层包**看不见**；只取后者 ⇒ `action/craft/` 这类**子包看不见**
    （2026-10-02 实测：第一版只取后者 ⇒ 读数写成"缺 23"而实际是"缺 21"）。
    """
    seen: dict[str, dict] = {}
    for pkg in package_dirs():                     # 有 package-info 的（含子包）
        pi = read_package_info(pkg)
        top, allc = class_counts(pkg)
        depth = len(pkg.relative_to(PKG_ROOT).parts)
        seen[pkg.as_posix()] = {
            "name": f"{pkg.name}/",
            "path": str(pkg.relative_to(PKG_ROOT)).replace("/", "/") + "/",
            "rel": pkg.relative_to(ROOT).as_posix(), "depth": depth, **pi,
            "top_classes": top - (1 if pi["has"] else 0),
            "all_classes": allc - (1 if pi["has"] else 0),
        }
    for name in top_level_names():                 # 顶层包（不管有没有设计说明）
        pkg = PKG_ROOT / name
        if pkg.as_posix() in seen:
            continue
        top, allc = class_counts(pkg)
        seen[pkg.as_posix()] = {
            "name": f"{name}/", "path": f"{name}/", "rel": pkg.relative_to(ROOT).as_posix(), "depth": 1,
            "has": False, "summary": "", "sections": [], "cites": [], "lines": 0,
            "top_classes": top, "all_classes": allc,
        }
    return [seen[k] for k in sorted(seen)]


def read_design_doc(f: Path) -> dict:
    """→ 一份 `docs/` 根设计件的摘要。⛔ 只读，不改任何文件。"""
    lines = f.read_text(encoding="utf-8").splitlines()
    title = next((re.sub(r"^#\s*", "", l).strip() for l in lines if l.startswith("# ")), f.stem)
    cites = sorted(set(DNUM.findall("\n".join(lines))), key=lambda s: int(s[2:]))
    return {"file": f.name, "rel": f.relative_to(ROOT).as_posix(), "title": strip_md(title),
            "lines": len(lines), "cites": cites}


def design_docs() -> list[dict]:
    """`docs/` 根下命中 `DESIGN_GLOBS` 的设计件（⭐ 显式清单，⛔ 不用模糊匹配）。"""
    out, seen = [], set()
    for pat in DESIGN_GLOBS:
        for f in sorted(ROOT.joinpath("docs").glob(pat)):
            if f.name in seen or not f.is_file():
                continue
            seen.add(f.name)
            out.append(read_design_doc(f))
    return sorted(out, key=lambda d: d["file"])


def design_coverage_check() -> list[str]:
    """⭐⭐ **双向自证**（这是本层最重要的东西，⛔ 不是那张表）：

    **(a) 磁盘 → 名单**：`docs/*.md` 每份都必须**要么**是设计件、**要么**被 `NON_DESIGN_PATTERNS` 点名；
    **(b) 名单 → 磁盘**：点名的每个模式**必须在磁盘上真能命中**（⛔ 拼错一个字母 = 静默失效）。

    ⚠️ 为什么必须有 (a)：本项目最贵的那族错是「**索引少列了东西，而它读起来完全正常**」
    （2026-10-02 实测过两次：`package-info` 子包漏出表外 · 设计件一份都没进索引）。
    ⚠️ 为什么必须有 (b)：名单里写错一个字 ⇒ 那一类**静默不被检查**，而**没有任何东西会红**。
    """
    problems: list[str] = []
    disk = {f.name for f in ROOT.joinpath("docs").glob("*.md")}
    designed = {d["file"] for d in design_docs()}
    covered: set[str] = set()
    for pat, _why in NON_DESIGN_PATTERNS:
        hit = {f.name for f in ROOT.joinpath("docs").glob(pat)}      # ⛔ 目录要排除
        if not hit:
            problems.append(f"名单里的模式 **一条都没命中**：`docs/{pat}` ⇒ "
                            f"拼错？文件被删/被改名？⛔ 这类**不会自己报错**，所以在这里响亮失败")
        covered |= hit
    unclassified = sorted(disk - designed - covered)
    if unclassified:
        problems.append(f"**{len(unclassified)} 份 `docs/*.md` 既不是设计件、也没在名单里被点名**："
                        f"{', '.join(unclassified[:8])}{' …' if len(unclassified) > 8 else ''} ⇒ "
                        f"加进 `DESIGN_GLOBS`（它是设计件）或 `NON_DESIGN_PATTERNS`（它不是）")
    return problems


def esc(cell: str) -> str:
    return cell.replace("|", "\\|").replace("\n", " ").strip()


def render(pkgs: list[dict], docs: list[dict]) -> str:
    tops = [p for p in pkgs if p["depth"] == 1]
    subs = [p for p in pkgs if p["depth"] > 1]
    have = [p for p in tops if p["has"]]
    miss = [p for p in tops if not p["has"]]
    lines: list[str] = []
    lines.append("# 设计索引（**生成物，禁手改**）")
    lines.append("")
    lines.append("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    lines.append("> 重新生成：`python3 tools/design-index.py --write`　·　校验：`python3 tools/design-index.py --check`")
    lines.append("> 门禁：`tools/check-design-index.sh`（挂 `tools/check-all.sh`）。")
    lines.append("")
    lines.append("## 为什么有这张表")
    lines.append("")
    lines.append("> 用户 2026-10-02 逐字：「项目也确实需要**一个设计总文档**，至少要带上**散落的设计索引**吧，**索引从下往上维护**」")
    lines.append("")
    lines.append("⭐ **设计说明住在它描述的那个包里**（`package-info.java`）⇒ 改代码的人顺手就能改。")
    lines.append("本索引只是把它们**汇总**起来，⛔ **不新增任何事实**（与 `docs/DECISIONS_INDEX.md` 同一条纪律）。")
    lines.append("")
    lines.append("## 怎么用（三步）")
    lines.append("")
    lines.append("1. 想知道**某个包是干什么的** ⇒ 在这张表里找它，看 `首句`；")
    lines.append("2. 想知道**为什么这么切** ⇒ 读它的 `package-info.java`（`设计位置` 列有路径）；")
    lines.append("3. 想知道**它受哪些裁定约束** ⇒ 看 `受约束` 列（⚠️ 这是从 `package-info.java` **扫出来的引用**，不是判断）。")
    lines.append("")
    lines.append("## ⭐⭐ 第 ① 层：`docs/` 根下的**设计件**（2026-10-02 新增）")
    lines.append("")
    lines.append("⚠️ 用户 2026-10-02 逐字（**纠正**我建议的 `docs/designs/`）：")
    lines.append("> 「`docs/designs/` **不太适合当「施工设计书」**，**会被误认为是项目的设计文档**，")
    lines.append("> 实际上**大型施工只有草案或者说定案，加上施工计划书**，这两个**不是「设计的载体」，是设计的结果**，")
    lines.append("> 是要**被反复修正**的。」")
    lines.append("")
    lines.append("⇒ ⭐ **不建 `docs/designs/`** —— **它已经有了，就是 `docs/` 根**。")
    lines.append("⛔ 而本索引**原来一份都没收它们**（只收 `package-info.java`）⇒ 这一层就是那个缺口的补丁。")
    lines.append("")
    lines.append(f"| 设计件（`docs/` 根） | 行数 | 受约束（**扫出来的** `D-###`） |")
    lines.append("|---|---:|---|")
    for d in docs:
        cites = " ".join(f"`{c}`" for c in d["cites"]) if d["cites"] else "—"
        lines.append(f"| **`{d['file']}`** —— {esc(d['title'])[:70]} | {d['lines']} | {esc(cites)} |")
    lines.append("")
    lines.append(f"**入选口径**（⛔ 显式清单，不是模糊匹配）：文件名命中 "
                 + " / ".join(f"`{g}`" for g in DESIGN_GLOBS) + "。")
    lines.append("")
    lines.append("**⛔ 与「施工域」的分界（用户 2026-10-02 划定）**：")
    lines.append("")
    lines.append("| 层 | 住哪 | 是什么 |")
    lines.append("|---|---|---|")
    lines.append("| **设计件** | `docs/` 根（本表） | 系统级 / 包级设计的**结果**（⛔ 不是「载体」）|")
    lines.append("| **刀级设计产出 ＋ 参考** | `docs/plans/` | 草案 / 讨论记录 / 设计单（用户：「**只适合当记录，施工时不适合拿来看**」）|")
    lines.append("| ⭐ **施工依据** | `docs/DOC_REFACTOR_PLAN.md` | **施工计划书**（施工期唯一执行入口）|")
    lines.append("| **报告 / 复核** | `docs/reviews/` · `survey/` | ⑤ 类：**可引、⛔ 不可当依据** |")
    lines.append("")
    lines.append("## 读数（**只从 `package-info.java` 与目录结构算**）")
    lines.append("")
    lines.append("| 量 | 值 |")
    lines.append("|---|---|")
    lines.append(f"| 顶层设计件（`docs/` 根） | **{len(docs)}** |")
    lines.append(f"| 顶层包数 | **{len(tops)}** |")
    lines.append(f"| ⭐ **顶层包有设计说明**（`package-info.java`） | **{len(have)} / {len(tops)}**（{len(have) / len(tops) * 100:.0f}%） |")
    lines.append(f"| ⛔ **顶层包没有设计说明** | **{len(miss)} / {len(tops)}** ⇒ 见下表 `设计位置 = —` 的行 |")
    lines.append(f"| 子包有设计说明 | **{len(subs)}**（{', '.join('`' + s['name'] + '`' for s in subs) or '无'}） |")
    secs = sum(len(p["sections"]) for p in pkgs if p["has"])
    lines.append(f"| 首节（`<h2>`）总数 | **{secs}**（⚠️ 编号风格**不统一**：`一 ·` / `零 ` / 无编号 ⇒ 尚未统一，见 `D1`） |")
    lines.append("")
    lines.append("## 表（**门禁逐字节比对的就是这一段**：包 / 首句 / 设计位置 / 受约束）")
    lines.append("")
    lines.append("| 包 | 首句（`package-info` 第一段） | 设计位置 | 受约束 |")
    lines.append("|---|---|---|---|")
    for p in pkgs:
        loc = f"`{p['rel']}/package-info.java`" if p["has"] else "—"
        cites = " ".join(f"`{c}`" for c in p["cites"]) if p["cites"] else "—"
        label = p["name"] if p["depth"] == 1 else f"↳ {p['path']}"
        lines.append(f"| **`{label}`** | {esc(p['summary']) or '—'} | {loc} | {esc(cites)} |")
    lines.append("")
    lines.append("> **列义**：`首句` = `package-info.java` 里**第一个 `<h2>` 之前**的第一段文字"
                 "（⛔ 摘要，不是设计）·")
    lines.append("> `受约束` = 该文件里**扫到的** `D-###` 引用（⚠️ 扫不到 ≠ 不受约束）·")
    lines.append("> `↳` 打头的行 = **子包**（列在它自己的完整路径下）。")
    lines.append("")
    lines.append("## ⛔ 本索引**假装不了**的两件事（诚实边界）")
    lines.append("")
    lines.append(f"1. ⛔ **{len(miss)} 个顶层包没有设计说明** —— 表里显示 `—`。本索引**只暴露**这件事，"
                 "⛔ 不代替你去补；补一份 = 在那个包里加一个 `package-info.java`。")
    lines.append("2. ⛔ **首节的编号风格不统一** —— ⚠️ **本条已在前一版之后被施工修掉**："
                 "9 份 `package-info.java` 的节名与次序已按 8 角色骨架统一（2026-10-02 `W4-2`），"
                 "节号**按角色固定** ⇒ 某份文件里**会跳**（那不表示缺内容）。")
    lines.append("3. ⛔ **`docs/` 根的设计件只有 14 份进表** —— 本索引**不判断它们是不是好的设计**，"
                 "⛔ 也不判断「这个包的设计该不该写」；它只回答「**在哪**」。")
    lines.append("")
    lines.append(APPENDIX_MARKER)
    lines.append("")
    lines.append("> ⚠️ **下面这张表不在门禁比对范围内** —— 类数**每次改动都变**，"
                 "算进比对会让门禁退化成「例行敲一下」（`docs/DECISIONS_INDEX.md` 的同一教训）。")
    lines.append("")
    lines.append("| 包 | 顶层 `.java` | 全树 `.java` |")
    lines.append("|---|---:|---:|")
    for p in pkgs:
        lines.append(f"| **`{p['name']}/`** | {p['top_classes']} | {p['all_classes']} |")
    lines.append("")
    return "\n".join(lines) + "\n"


def assertion_problems(stats: dict, assertions: dict | None = None) -> list[str]:
    """→ **不成立的那几条判据**（空列表 = 全绿）。

    ⭐⭐ **抽成纯函数是为了 `A3` 能注入自证**（2026-10-05 开发者裁「加 `--selftest`」）：
    ⛔ 原来这段**内联在 `build()` 里** ⇒ 它只读**真树** ⇒ ⛔ **测不了**
    （「上界收到 22 就红吗」只能手跑、⛔ 不能固化成常驻臂）。
    ⚠️ **本次是纯抽离**：判据、文案、顺序**逐字未改**（⛔ 不是借机改行为）。
    """
    problems: list[str] = []
    for key, (op, limit) in (assertions or ASSERTIONS).items():
        if key not in stats:
            #: ⛔ **响亮失败**：`统计量` 与 `判据` 对不上名 ⇒ 那条判据会**静默失效**
            #:（这正是本仓"名单里写错一个字 ⇒ 那一类静默不被检查"那一族的同族形状）。
            problems.append(f"判据 `{key}` **没有对应的统计量** ⇒ "
                            f"它会静默失效（可用：{', '.join(sorted(stats))}）")
            continue
        bad = stats[key] < limit if op == ">=" else stats[key] > limit
        if bad:
            why = ("解析崩塌 / 覆盖倒退" if op == ">=" else "⭐ **缺口变大**（只许变短）")
            problems.append(f"{why} —— {key}={stats[key]} {op} {limit} **不成立**"
                            f"（⛔ 少读一截、或缺口涨了，都不许悄悄过）")
    return problems


def build() -> tuple[str, dict, list[dict]]:
    pkgs = collect()
    docs = design_docs()
    stats = {
        "顶层包数": len(pkgs),
        "顶层设计件": len(docs),
        #: ⭐ `A3`（2026-10-05）：**顶层包**里"有设计说明 / 没有设计说明"各几个。
        #: ⚠️ 只数 `depth == 1` —— 子包（`action/craft` · `region/authz`）不进这两个量，
        #:   否则"缺口"会因为**子包补了设计**而假降，⭐ 而缺口说的就是**顶层包**。
        "顶层包有设计说明": sum(1 for p in pkgs if p["has"] and p["depth"] == 1),
        "顶层包缺设计说明": sum(1 for p in pkgs if not p["has"] and p["depth"] == 1),
    }
    #: ⭐ 判据交 `assertion_problems()`（纯函数 ⇒ **可注入自证**）；⛔ 行为与抽离前逐字相同。
    for p in assertion_problems(stats):
        print(f"DESIGN_INDEX_RESULT FAIL: {p}", file=sys.stderr)
        raise SystemExit(1)
    #: ⭐⭐ **双向覆盖自证**（第二层）：`docs/*.md` 每份都被分类 ÷ 名单里每个模式都真能命中。
    cov = design_coverage_check()
    if cov:
        print("DESIGN_INDEX_RESULT FAIL: `docs/` 根的设计件**覆盖自证失败**：", file=sys.stderr)
        for c in cov:
            print(f"  · {c}", file=sys.stderr)
        raise SystemExit(1)

    #: ⭐⭐ **`A4` 的载体**（2026-10-05）：`package-info.java` 的 `package` 声明必须与目录一致。
    #: ⛔ 与 `check-all` 的其余部分**不重叠** —— 那一条原本**只有编译器拦**。
    decl = package_declaration_check()
    if decl:
        print("DESIGN_INDEX_RESULT FAIL: `package-info.java` 的 `package` 声明与**所在目录对不上**：",
              file=sys.stderr)
        for c in decl:
            print(f"  · {c}", file=sys.stderr)
        raise SystemExit(1)

    #: ⭐ **完整性自证**：两张人口必须对上 —— ⛔ 只要有一个包被漏出表外，就**响亮失败**。
    #: （2026-10-02 实测：第一版只取顶层目录 ⇒ `action/craft/` 与 `region/authz/` 两个**子包漏出表外**，
    #:  而**没有任何东西会红** —— 索引自己少列了包，读起来却完全正常。这正是「扫不到就报绿」那一族。）
    in_table = {p["rel"] for p in pkgs}
    dropped = [d.relative_to(ROOT).as_posix() for d in package_dirs() if d.relative_to(ROOT).as_posix() not in in_table]
    if dropped:
        print(f"DESIGN_INDEX_RESULT FAIL: **包漏出表外** —— 磁盘上有 `package-info.java` 的包 "
              f"{len(dropped)} 个不在索引里：{', '.join(dropped)}"
              f"（索引读起来正常，但它**没列出全部包** —— 这正是本门禁要拦的那族错）", file=sys.stderr)
        raise SystemExit(1)
    n_top = len(top_level_names())
    if n_top != stats["顶层包数"] - sum(1 for p in pkgs if p["depth"] > 1):
        print(f"DESIGN_INDEX_RESULT FAIL: **顶层包漏出表外** —— 磁盘上顶层包 {n_top} 个，"
              f"而索引里顶层行只有 {stats['顶层包数'] - sum(1 for p in pkgs if p['depth'] > 1)} 个", file=sys.stderr)
        raise SystemExit(1)
    return render(pkgs, docs), stats, pkgs


def selftest() -> int:
    """**十臂**注入自证（`A3` 四条 ＋ `A4` 六条）—— ⭐「能过」≠「能抓」。

    ⭐ 为什么必须固化（2026-10-05 开发者裁「加」）：这些注入**原先全是手跑的**
    （记在 `check-design-index.sh` 头部）⇒ ⛔ **以后谁把它改坏了，没有任何东西会红**。
    ⚠️ `A4` 那六条**喂临时树**（⛔ 不动 `src/`）—— `package_declaration_check()` 收 `src` 参数就是为了这个。
    """
    import tempfile
    arms: list[tuple[str, bool]] = []

    # ── A3：判据循环（⛔ 不是看"跑得过"，是看"该红时红不红"）─────────────────
    base = {"顶层包数": 30, "顶层设计件": 14, "顶层包有设计说明": 7, "顶层包缺设计说明": 23}
    arms.append(("A3-1 现算值 ⇒ 全绿", not assertion_problems(base)))
    arms.append(("A3-2 上界收到 22（缺口 23 > 22）⇒ 红",
                 any("缺口变大" in p for p in
                     assertion_problems(base, {"顶层包缺设计说明": ("<=", 22)}))))
    arms.append(("A3-3 下限抬到 8（有设计 7 < 8）⇒ 红",
                 any("覆盖倒退" in p for p in
                     assertion_problems(base, {"顶层包有设计说明": (">=", 8)}))))
    arms.append(("A3-4 判据名拼错 ⇒ **响亮失败**（⛔ 不许静默失效）",
                 any("没有对应的统计量" in p for p in
                     assertion_problems(base, {"顶层包有设计说明（拼错）": (">=", 1)}))))

    # ── A4：`package` 声明必须与目录一致（⭐ 六条，喂临时树）────────────────
    def a4(name: str, files: dict[str, str], want_red: bool) -> None:
        with tempfile.TemporaryDirectory() as td:
            r = Path(td)
            for rel, body in files.items():
                q = r / rel
                q.parent.mkdir(parents=True, exist_ok=True)
                q.write_text(body, encoding="utf-8")
            got = package_declaration_check(r)
            ok = bool(got) == want_red
            arms.append((f"A4 {name} ⇒ {'期望红' if want_red else '期望安静'}", ok))
            if not ok:
                print(f"      ↳ 实得 {got!r}")

    a4("声明与目录一致", {"a/b/package-info.java": "/** d */\npackage a.b;\n"}, False)
    a4("声明与目录**不一致**（包改名）", {"a/b/package-info.java": "/** d */\npackage a.OLD;\n"}, True)
    a4("**没有** `package` 声明", {"a/b/package-info.java": "/** d */\n"}, True)
    a4("全树一个都没扫到 ⇒ ⛔ 不许报绿", {}, True)
    #: ⭐ 这条**当初手跑时抓出过一个真 bug**（`rel` 对临时树算不出来）⇒ ⭐ 注入自证不是形式。
    a4("注释里提到 `package` 但没真声明 ⇒ ⛔ 不许误认",
       {"a/b/package-info.java": "/** package a.OLD; */\npackage a.b;\n"}, False)
    a4("好坏混住 ⇒ 只报坏的那一个",
       {"a/b/package-info.java": "/**d*/\npackage a.b;\n",
        "a/c/package-info.java": "/**d*/\npackage WRONG;\n"}, True)

    bad = 0
    for name, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"DESIGN_INDEX_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验 docs/DESIGN_INDEX.md")
    ap.add_argument("--selftest", action="store_true", help="十臂注入自证（⛔ 不读真树）")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--write", action="store_true", help="写入 docs/DESIGN_INDEX.md")
    g.add_argument("--check", action="store_true", help="校验是否陈旧（陈旧 ⇒ 非零退出）")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not (args.write or args.check):
        ap.error("要 --write 或 --check（或 --selftest）")

    rendered, stats, pkgs = build()
    n_top = sum(1 for p in pkgs if p["depth"] == 1)
    have = sum(1 for p in pkgs if p["has"] and p["depth"] == 1)
    subs = sum(1 for p in pkgs if p["has"] and p["depth"] > 1)
    summary = (f"顶层设计件 {stats['顶层设计件']} · {n_top} 顶层包 · 有设计说明 {have} · 缺 {n_top - have}"
               f" · 子包有 {subs}（`package-info.java` 共 {have + subs} 份）")

    if args.write:
        OUTPUT.write_text(rendered, encoding="utf-8")
        print(f"DESIGN_INDEX_RESULT WROTE: {summary} → {OUTPUT.relative_to(ROOT)}")
        return 0

    if not OUTPUT.exists():
        print(f"DESIGN_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 不存在"
              f"（跑 `python3 tools/design-index.py --write`）", file=sys.stderr)
        return 1

    def gated(text: str) -> list[str] | None:
        if APPENDIX_MARKER not in text:
            return None
        return text.split(APPENDIX_MARKER, 1)[0].splitlines()

    on_disk = OUTPUT.read_text(encoding="utf-8")
    disk_gated, gen_gated = gated(on_disk), gated(rendered)
    if disk_gated is None:
        print(f"DESIGN_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 缺 `{APPENDIX_MARKER}` 标记"
              f"（文件被手改过？）⇒ 跑 `--write` 重生", file=sys.stderr)
        return 1
    assert gen_gated is not None
    if disk_gated != gen_gated:
        detail = "行数不同" if len(disk_gated) != len(gen_gated) else "行数相同、内容不同"
        first = next((i for i, (a, b) in enumerate(zip(disk_gated, gen_gated), 1) if a != b), None)
        print(f"DESIGN_INDEX_RESULT FAIL: 设计索引已陈旧（{detail}"
              + (f"，首个不同在第 {first} 行" if first else "") + "）"
              f" ⇒ 跑 `python3 tools/design-index.py --write` 并提交", file=sys.stderr)
        return 1
    print(f"DESIGN_INDEX_RESULT PASS: {summary} / 与 docs/DESIGN_INDEX.md 逐字节相同"
          f"（⚠️ 附录「类数明细」**不在**比对范围）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
