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


def main() -> int:
    k4 = rule_k4()
    k5 = rule_k5()
    for line in k4:
        print(f"[K4·谓词统一] {line}")
    for line in k5:
        print(f"[K5·状态生产] {line}")
    ok = not k4 and not k5
    print(f"KERNEL_PREDICATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"工厂谓词漂移={len(k4)} / 死状态={len(k5)}"
          f"（K4-P1 = 执行工厂必须用规划侧同一谓词；K5-P1 = 声明了的状态必须有生产者）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
