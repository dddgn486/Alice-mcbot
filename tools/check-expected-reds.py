#!/usr/bin/env python3
"""⭐ **预期红清单门禁**（`D-532` §三 横切闸门② 的检查；载体 = `docs/EXPECTED_REDS.md`）。

## 为什么这条规则该存在

`D-532` §三 允许**计划内红**（"摘掉的功能 ⇒ 对应的步红了"），但要求**红的步与摘掉的功能一一对应**。
在此之前这句话**没有任何载体** —— 只能靠人记得"这个红是老朋友"（`survey/48` §6.4 实测：
`预期红` 三处命中**全是同词异义**，真载体 **0**）。

⚠️ 没有这条检查的两个具体下场（都在本项目发生过形态）：
① **好消息被盖掉**：一个已知红坐在 44 步的长行中间 ⇒ 新红被读成"还是老样子"
   （`RegressionBatteryTask.reportNonPassSteps()` 的 javadoc 逐字记过这种误读）；
② **坏消息被洗白**：任何红都可以口头说成"计划内"，因为**没有人能核对**计划是什么。

## 判据（三态，与 `tools/check-all.sh` 对齐）

- **PASS（exit 0）**：拿到了**新鲜**的电池判决，且 `实际红 ⊆ 预期红清单`，且清单每行齐全、无陈旧行。
- **WARN（exit 2）**：**断言本轮没有执行** —— 没有电池日志 / 日志里没有判决行（整轮中止）/
  日志比 `src/main/java` 的最近改动**旧**（源码变了 ⇒ 那一轮判决不再是本轮的判决）。
- **FAIL（exit 1）**：断言执行了但不成立 —— 出现**未登记的红** / 清单行缺列 / 清单里的步不在本轮汇总里 /
  清单里的步本轮 **PASS**（陈旧行：清单必须跟着现实走）。

## 反空转（⛔ 不许"没扫到"被读成"通过"）

- ① 汇总行必须解析出 **≥ `MIN_STEPS` 步**（解析器崩了与"真的全绿"会印同一个 0 步）；
- ② `--selftest` 的 **8 条合成臂**每次都跑（本文件末尾）；
- ③ WARN 与 PASS **必须印不同的串**（`EXPECTED_REDS_RESULT`），且 WARN 时**不说**"通过"。

## ⚠️ 本门禁不判什么

⛔ 不判"这个红是不是**应该**存在"（那是人的判断）—— 它只判"**红的集合是否等于你声明过的那个集合**"。
⛔ 不判 `SUMMARY` 里 `key=VALUE` 这些子读数的对错（那是各夹具自己的判据）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
REGISTRY = REPO / "docs" / "EXPECTED_REDS.md"
LOG_DIR = REPO / "run" / "headless-logs"
SRC_DIR = REPO / "src" / "main" / "java"

# 反空转 ①：一轮 CORE 今天 44 步（`D-309` 后只增不减）；下限取远小于它的数，⛔ 不写死精确值（会腐烂）。
MIN_STEPS = 20

VERDICT_RE = re.compile(r"(?:^|\s)([a-z][a-z0-9_]*)=(PASS|FAIL|SKIP|SKIPPED)(?=\s|$)")
SUMMARY_RE = re.compile(r"\[Regression\] SUMMARY ")
NONPASS_ROW_RE = re.compile(r"\[Regression\]\s+·\s+([a-z][a-z0-9_]*)=(\w+)（(.*?)）\s*$")
# 清单行：| `step` | 读数 | 归属刀 | 理由 ＋ 复核触发 |
ROW_RE = re.compile(r"^\|\s*`([a-z][a-z0-9_]*)`\s*\|(.+?)\|(.+?)\|(.+?)\|\s*$")


# ----------------------------------------------------------------- 解析
def parse_summary(log_text: str) -> tuple[dict[str, str], str]:
    """⇒ ({步: 判决}, 那一行原文)。取**最后一次**出现（一轮日志里可能有多次汇总）。"""
    hits = [m for m in SUMMARY_RE.finditer(log_text)]
    if not hits:
        return {}, ""
    # 从最后一次 SUMMARY 起，取到该行行尾
    start = hits[-1].end()
    end = log_text.find("\n", start)
    line = log_text[start:end if end > 0 else len(log_text)]
    return dict(VERDICT_RE.findall(line)), line


def parse_nonpass(log_text: str) -> dict[str, str]:
    """⇒ {步: "判决（明细）"}（`RegressionBatteryTask.reportNonPassSteps()` 打的那几行）。"""
    out: dict[str, str] = {}
    for m in NONPASS_ROW_RE.finditer(log_text):
        out[m.group(1)] = f"{m.group(2)}（{m.group(3)}）"
    return out


def parse_registry(text: str) -> tuple[list[dict[str, str]], list[str]]:
    """⇒ (行列表, 结构问题)。行 = {step,reading,knife,why}。"""
    rows: list[dict[str, str]] = []
    problems: list[str] = []
    for raw in text.splitlines():
        if not raw.lstrip().startswith("|"):
            continue
        if "`" not in raw:
            continue                      # 表头/分隔行
        m = ROW_RE.match(raw.rstrip())
        if not m:
            # 形如 `` | `x` | a | b | `` 但列数不对 ⇒ 显式报，⛔ 不静默跳过
            if raw.count("|") >= 3:
                problems.append(f"清单行**列数不对**（要 4 列：步/读数/归属刀/理由）：{raw.strip()[:100]}")
            continue
        step, reading, knife, why = (g.strip() for g in m.groups())
        rows.append({"step": step, "reading": reading, "knife": knife, "why": why})
    for r in rows:
        for key in ("reading", "knife", "why"):
            if not r[key]:
                problems.append(f"清单行 `{r['step']}` 的 **{key}** 列为空（⛔ 不许省略：红的步要能追到**哪一刀**）")
    dup = {r["step"] for r in rows if [x["step"] for x in rows].count(r["step"]) > 1}
    for step in sorted(dup):
        problems.append(f"清单里 **`{step}` 重复登记**（同一处红只许一行）")
    return rows, problems


# ----------------------------------------------------------------- 判定
def evaluate(actual: dict[str, str], rows: list[dict[str, str]],
             structural: list[str], min_steps: int = MIN_STEPS) -> tuple[str, list[str], list[str]]:
    """纯函数（合成臂测它）。⇒ (状态, 失败项, 提示项)。状态 ∈ {'pass','warn','fail'}。"""
    fails = list(structural)
    notes: list[str] = []
    if len(actual) < min_steps:
        return "warn", fails, [f"汇总只解析出 {len(actual)} 步（下限 {min_steps}）⇒ 判据的主要对象不在，本断言**未执行**"]
    registered = {r["step"] for r in rows}
    # R2：实际红 ⊆ 清单
    for step, verdict in sorted(actual.items()):
        if verdict == "FAIL" and step not in registered:
            fails.append(f"**未登记的红**：`{step}=FAIL` 不在 `docs/EXPECTED_REDS.md` 里 "
                         f"⇒ 要么修它，要么写明「哪一刀 ＋ 为什么是计划内」")
    # R3/R4：清单里的每一行都必须在本轮汇总里，且本轮必须是 FAIL
    skipped = []
    for r in rows:
        step = r["step"]
        if step not in actual:
            fails.append(f"清单里的 **`{step}`** 不在本轮汇总里 ⇒ 步名写错或清单陈旧（本清单只许写**电池真有的步**）")
            continue
        verdict = actual[step]
        if verdict == "PASS":
            fails.append(f"**陈旧行**：清单说 `{step}` 是预期红，本轮却是 **PASS** "
                         f"⇒ 红消失 ⇒ 删掉这一行（否则清单会长期谎报状态）")
        elif verdict in ("SKIP", "SKIPPED"):
            skipped.append(step)
    if skipped:
        notes.append("清单里的 " + ", ".join(f"`{s}`" for s in skipped)
                     + " 本轮 **SKIP**（没跑）⇒ 那几行本轮**没有被验证**："
                       "既不能读成「红还在」，也不能读成「红没了」")
    if fails:
        return "fail", fails, notes
    if skipped:
        return "warn", fails, notes
    return "pass", fails, notes


# ----------------------------------------------------------------- 取数
def newest_core_log() -> Path | None:
    if not LOG_DIR.is_dir():
        return None
    logs = sorted(LOG_DIR.glob("*-core.log"), key=lambda p: p.stat().st_mtime)
    return logs[-1] if logs else None


def newest_source_mtime() -> float:
    if not SRC_DIR.is_dir():
        return 0.0
    return max((p.stat().st_mtime for p in SRC_DIR.rglob("*.java")), default=0.0)


# ----------------------------------------------------------------- 合成臂
def selftest() -> int:
    rows = [{"step": "mine_regression", "reading": "blocked=FAIL", "knife": "1-1b₂", "why": "x"}]
    base = {"mine_regression": "FAIL", "clear_retry": "PASS"}
    full = dict(base)
    for i in range(MIN_STEPS):
        full.setdefault(f"filler_{i}", "PASS")
    full["mine_regression"] = "FAIL"
    cases: list[tuple[str, dict, list, list, str]] = [
        ("① 红已登记 ⇒ 通过", full, rows, [], "pass"),
        ("② 未登记的红 ⇒ 红", {**full, "clear_retry": "FAIL"}, rows, [], "fail"),
        ("③ 清单行缺列 ⇒ 红", full, rows, ["清单行 `mine_regression` 的 **knife** 列为空"], "fail"),
        ("④ 清单写了电池没有的步 ⇒ 红", full, rows + [{"step": "not_a_step", "reading": "r", "knife": "k", "why": "w"}], [], "fail"),
        ("⑤ 清单里的红已变 PASS ⇒ 红（陈旧行）", {**full, "mine_regression": "PASS"}, rows, [], "fail"),
        ("⑥ 清单里的步本轮 SKIP ⇒ 未执行（warn）", {**full, "mine_regression": "SKIP"}, rows, [], "warn"),
        ("⑦ 汇总解析出的步数不足 ⇒ 未执行（warn）", {"mine_regression": "FAIL"}, rows, [], "warn"),
        ("⑧ 清单空 ＋ 实际无红 ⇒ 通过（空清单合法）", {k: "PASS" for k in full}, [], [], "pass"),
    ]
    bad = 0
    for title, actual, rws, structural, want in cases:
        got, _fails, _notes = evaluate(actual, rws, structural)
        ok = got == want
        bad += 0 if ok else 1
        print(f"  [{'ok' if ok else 'BAD'}] {title} ⇒ 期望 {want} / 实际 {got}")
    print(f"EXPECTED_REDS_SELFTEST {'PASS' if bad == 0 else 'FAIL'}（{len(cases) - bad}/{len(cases)}）")
    return 0 if bad == 0 else 1


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()

    if not REGISTRY.exists():
        print(f"EXPECTED_REDS_RESULT WARN: 载体缺失 {REGISTRY.relative_to(REPO)} ⇒ 本断言**未执行**")
        return 2

    rows, structural = parse_registry(REGISTRY.read_text(encoding="utf-8"))

    log = newest_core_log()
    if log is None:
        print("EXPECTED_REDS_RESULT WARN: 没有 `run/headless-logs/*-core.log` ⇒ 没有「实际红」可比 "
              "⇒ 本断言**未执行**（跑 `ALICE_HEADLESS=1 bash tools/check-all.sh` 产生）")
        return 2

    src_mtime = newest_source_mtime()
    log_mtime = log.stat().st_mtime
    if src_mtime > log_mtime:
        print(f"EXPECTED_REDS_RESULT WARN: 最新电池轮 `{log.name}` **早于**最近的 `src/main/java` 改动 "
              f"⇒ 那一轮判决不是本轮的判决 ⇒ 本断言**未执行**（重跑电池；⚠️ 本门禁只看 `src/main/java`，"
              f"不看 `tools/`／世界母本 —— 那两面的新鲜度由电池自己的指纹缓存管）")
        return 2

    text = log.read_text(encoding="utf-8", errors="replace")
    actual, line = parse_summary(text)
    if not actual:
        print(f"EXPECTED_REDS_RESULT WARN: `{log.name}` 里**没有 `[Regression] SUMMARY` 行** ⇒ "
              f"整轮中止/无判决 ⇒ 本断言**未执行**（这正是横切闸门①要挡的形态）")
        return 2

    state, fails, notes = evaluate(actual, rows, structural)
    reds = sorted(s for s, v in actual.items() if v == "FAIL")
    print(f"  电池轮：{log.name} · 步数 {len(actual)} · 实际红 {len(reds)}"
          f"（{', '.join(reds) if reds else '无'}）· 清单 {len(rows)} 行")
    if notes:
        for n in notes:
            print(f"  ⚠️  {n}")
    if state == "fail":
        print(f"EXPECTED_REDS_RESULT FAIL: {len(fails)} 项")
        for f in fails:
            print(f"  ✗ {f}")
        return 1
    if state == "warn":
        print("EXPECTED_REDS_RESULT WARN: 清单里有步本轮没跑（SKIP）⇒ 那几行**未被验证**（⚠️ 不是通过）")
        return 2
    print(f"EXPECTED_REDS_RESULT PASS: 实际红 ⊆ 预期红清单（{len(rows)} 行，全部在本轮命中）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
