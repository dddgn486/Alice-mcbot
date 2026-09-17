#!/usr/bin/env python3
"""内核**规划/执行谓词与状态**的可执行规则（挂在 `tools/check-all.sh` 上；失败即构建红）。

出处：内核审计 §2（K-4 规划/执行谓词不统一、K-5 死状态）在 2026-09-16 的复核。

K4-P1 **执行工厂不许自己手搓一份"看起来一样"的准入判据**：TRAVERSE/DIAGONAL/ASCEND/DESCEND 四个
   `*ExecutionFactory.validate` 必须引用规划侧同一谓词（`MovementHelper.canTraverse` / `canAscend` /
   `canDescend`）。为什么：两份判据会漂移，而方向危险的是"**执行比内核宽松**"（接受内核永不会生成的边）。
  注意**不覆盖** DOWNWARD / BREAK_* / PLACE_STEP 等家族：它们的准入**有意**比 `canStandCentered` 宽
  （provider 里写了原因），把它们并进来等于禁用这些移动。

K5-P1 **声明了的状态必须有生产者或明确消费者**：`PathSessionStatus.POSTCONDITION_FAILED` 必须出现在
   `PathSessionStatus` 的映射表里（`RULES.put(..., POSTCONDITION_FAILED)`），否则就是死状态（观测盲区）。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"

FACTORY_PREDICATES = {
    "TraverseExecutionFactory.java": "canTraverse",
    "DiagonalExecutionFactory.java": "canTraverse",
    "AscendExecutionFactory.java": "canAscend",
    "DescendExecutionFactory.java": "canDescend",
}


def method_body(text: str, signature: str) -> str:
    idx = text.find(signature)
    if idx < 0:
        return ""
    line_start = text.rfind("\n", 0, idx) + 1
    indent = len(text[line_start:idx]) - len(text[line_start:idx].lstrip())
    lines = text[idx:].split("\n")
    body = [lines[0]]
    for line in lines[1:]:
        stripped = line.strip()
        if stripped.startswith("}") and (len(line) - len(line.lstrip())) <= indent:
            break
        body.append(line)
    return "\n".join(body[1:])   # 去掉签名行（签名里可能含同名符号）


def rule_k4():
    violations = []
    for file, predicate in FACTORY_PREDICATES.items():
        path = CORE / file
        if not path.exists():
            violations.append(f"{file} 不存在（改名？同步本规则）")
            continue
        body = method_body(path.read_text(encoding="utf-8"), "ValidationResult validate(")
        if not body:
            violations.append(f"{file} 找不到 validate(...)（改名？同步本规则）")
            continue
        if f"MovementHelper.{predicate}(" not in body:
            violations.append(f"{file} 的 validate 没有引用规划侧谓词 MovementHelper.{predicate}()"
                              "（执行可能比内核宽松）")
    return violations


def rule_k5():
    path = CORE / "session" / "PathSessionStatus.java"
    if not path.exists():
        return ["PathSessionStatus.java 不存在（改名？同步本规则）"]
    text = path.read_text(encoding="utf-8")
    if "POSTCONDITION_FAILED" not in text:
        return ["POSTCONDITION_FAILED 已删除 ⇒ 请同时删掉本规则 K5-P1"]
    if not re.search(r"RULES\.put\([^)]*POSTCONDITION_FAILED\s*\)", text):
        return ["POSTCONDITION_FAILED 没有映射表生产者（死状态：永远不会被观测到）"]
    return []


def rule_s8():
    """S8-P1（D-264）：`policyVersion` 已按 S-8 裁定**删除**（恒 0 + 全仓零读者 ⇒ 纯装饰）。

    ⇒ 它不得无声复活：要重新引入，**先得有一个读取者**（并在 `AI_DECISIONS` 里说明它决定什么）。
    """
    hits = []
    for path in sorted((ROOT / "src" / "main" / "java").rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        if "policyVersion" in text:
            rel = path.relative_to(ROOT / "src" / "main" / "java")
            hits.append(f"{rel} 又出现 policyVersion（S-8 已删：先给读取者，再谈重新引入）")
    return hits


def rule_walk_budget():
    """W-P1（D-268④，2026-09-17 用户裁定）：**行走类请求必须有界**。

    现状问题：`PathRequest` 原先把五个 `of(...)` 调用点全传 `SearchBudget.UNLIMITED`
    ⇒ 规划器自己的天花板（`CorePathPlanner.DEFAULT_MAX_NODES/MILLIS`）被绕过，行走可以烧满整个搜索空间。
    裁定 = 有界 + 撞限如实报 `SEARCH_LIMIT`。本规则防止"无声改回无界"。
    """
    path = CORE / "search" / "PathRequest.java"
    if not path.exists():
        return ["PathRequest.java 不存在（改名？同步本规则）"]
    text = path.read_text(encoding="utf-8")
    problems = []
    if "SearchBudget.UNLIMITED" in text:
        problems.append("PathRequest 又出现 SearchBudget.UNLIMITED（行走类必须走 WALK_BUDGET：撞限要报 SEARCH_LIMIT）")
    if "WALK_BUDGET" not in text:
        problems.append("PathRequest 里找不到 WALK_BUDGET（有界预算被移除？）")
    return problems


def rule_progress_signal():
    """NP-P1（survey/17 §4.2(c)，2026-09-17）：**失败计数不得被当成进度**。

    背景：`NO_PROGRESS` 的进度指纹里若整串塞进 Job 的 `progressSummary()`（形如 `mined 3/16 failed=2`），
    那么"**一直在失败**"（`failed` 涨、`mined` 不动 —— 外部 141 小时实测里的"种土豆"失效模式）
    会被当作"有进度"从而**反武装**判据 ⇒ 越失败越显得在动。本规则要求那串先剔除 `failed=`。
    """
    path = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision" / "EventThresholds.java"
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    # ⚠️ 必须断言**调用**本身，不能只搜 `failed=` —— 注释里也写了 `failed=`，
    # 第一版就这么被**顶绿**过一次（2026-09-17 反向对照实测）。
    strip_call = 'replaceAll("\\\\s*failed=\\\\d+"'
    if strip_call not in text:
        return ["EventThresholds 里找不到剔除 `failed=` 的**调用**（进度信号又把失败当进度了？）"]
    return []


def main() -> int:
    k4 = rule_k4()
    k5 = rule_k5()
    s8 = rule_s8()
    walk = rule_walk_budget()
    prog = rule_progress_signal()
    for line in k4:
        print(f"[K4·谓词统一] {line}")
    for line in k5:
        print(f"[K5·状态生产] {line}")
    for line in s8:
        print(f"[S8·死字段] {line}")
    for line in walk:
        print(f"[W·行走预算] {line}")
    for line in prog:
        print(f"[NP·进度信号] {line}")
    ok = not k4 and not k5 and not s8 and not walk and not prog
    print(f"KERNEL_PREDICATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"工厂谓词漂移={len(k4)} / 死状态={len(k5)} / 死字段复活={len(s8)} / 行走无界={len(walk)} / 失败当进度={len(prog)}"
          f"（K4-P1 谓词统一；K5-P1 状态有生产者；S8-P1 policyVersion 不得复活；W-P1 行走必须有界；NP-P1 失败计数不得当进度）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
