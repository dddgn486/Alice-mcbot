#!/usr/bin/env python3
"""诚实失败比例（队列第⑤项 / `survey/15 §4.4 §10-6`，2026-09-17）：把"如实报失败"变成**可复算指标**。

为什么要这个数：`survey/15` §4.4 提出"盯一个比率"——**任务终态里"如实说做不到"占多少**。
它是"诚实失败"这条产品主张的唯一可量化代理：如果终态里全是成功或全是超时，
说明要么任务太简单、要么失败被掩盖（例如被总括码盖掉、被当成 SEARCH_LIMIT 静默处理）。

用法：
  python3 tools/failure-ratio.py [日志路径]      # 默认读无头服务端日志
  python3 tools/failure-ratio.py --selftest      # 用合成日志自证分类器（可红）

分类口径（**唯一**，改动要先改本文件与 `docs/reviews/2026-09-17-勘测分诊与任务队列.md`）：
* `success`  —— 达成目标（`quota_met` / `reached` / `done` / `passed` / `completed` / `empty`）
* `honest`   —— **如实报"做不到"**（`unreachable` / `no_reachable_candidate` / `hold_no_exit` /
                `stale_target` / `tool_missing` / `goal_not_loaded` / `not_found` / `refused*`）
* `limit`    —— 撞上**预算/时限**（`search_limit` / `goal_timeout` / `budget*` / `partial*` / `incomplete`）
* `cancelled`—— 用户/上层中止（`cancelled*` / `aborted`）
* `other`    —— 其余（含空终态）
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

DEFAULT_LOG = Path("/home/fb486/alice-server/logs/latest.log")
REASON = re.compile(r"terminalReason=([^\s]*)")
TERMINAL = re.compile(r"\bterminal=([A-Za-z0-9_]+)")

SUCCESS = ("quota_met", "reached", "done", "passed", "completed", "empty")
HONEST = ("unreachable", "no_reachable_candidate", "hold_no_exit", "stale_target", "tool_missing",
          "goal_not_loaded", "not_found", "refused", "inventory_full", "no_candidate")
LIMIT = ("search_limit", "goal_timeout", "budget", "partial", "incomplete", "timeout")
CANCELLED = ("cancel", "abort")


def classify(reason: str) -> str:
    r = (reason or "").strip().lower()
    if not r:
        return "other"
    if any(k in r for k in CANCELLED):
        return "cancelled"
    if any(k in r for k in SUCCESS):
        return "success"
    if any(k in r for k in HONEST):
        return "honest"
    if any(k in r for k in LIMIT):
        return "limit"
    return "other"


def tally(text: str) -> dict[str, int]:
    counts = {"success": 0, "honest": 0, "limit": 0, "cancelled": 0, "other": 0}
    seen = 0
    for m in REASON.finditer(text):
        seen += 1
        counts[classify(m.group(1))] += 1
    if seen == 0:   # 退回看旧格式 `terminal=CODE`
        for m in TERMINAL.finditer(text):
            seen += 1
            counts[classify(m.group(1))] += 1
    counts["_total"] = seen
    return counts


def report(counts: dict[str, int], source: str) -> None:
    total = counts["_total"]
    print(f"[诚实失败比例] 来源={source} 终态总数={total}")
    for key in ("success", "honest", "limit", "cancelled", "other"):
        pct = (100.0 * counts[key] / total) if total else 0.0
        print(f"  {key:9s} {counts[key]:5d}  ({pct:5.1f}%)")
    print(f"FAILURE_RATIO_RESULT honest={counts['honest']} limit={counts['limit']} "
          f"success={counts['success']} total={total}"
          f"（口径见 tools/failure-ratio.py 头部；这是**观测指标**，不是门禁）")


def selftest() -> int:
    synthetic = ("terminalReason=quota_met\nterminalReason=unreachable\nterminalReason=SEARCH_LIMIT\n"
                 "terminalReason=CANCELLED_BY_USER\nterminalReason=tool_missing\n"
                 "terminalReason=no_reachable_candidate\nterminalReason=\n")
    got = tally(synthetic)
    want = {"success": 1, "honest": 3, "limit": 1, "cancelled": 1, "other": 1, "_total": 7}
    ok = all(got[k] == v for k, v in want.items())
    print(f"FAILURE_RATIO_SELFTEST {'PASS' if ok else 'FAIL'}: got={got} want={want}")
    return 0 if ok else 1


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] == "--selftest":
        return selftest()
    log = Path(args[0]) if args else DEFAULT_LOG
    if not log.exists():
        print(f"[诚实失败比例] 日志不存在：{log}（先跑一次电池，或显式给路径）")
        return 2
    report(tally(log.read_text(encoding="utf-8", errors="ignore")), str(log))
    return 0


if __name__ == "__main__":
    sys.exit(main())
