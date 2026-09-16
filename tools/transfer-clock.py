#!/usr/bin/env python3
"""传输账本的两条**可执行**规则（挂在 `tools/check-all.sh` 上；失败即构建红）。

出处：台账 §5.9（2026-09-15 客户端实测的"静默永久阻塞"）与 2026-09-16 的代码级复核。

R1 **账本只有一个时钟**：挂起落章用世界时间（`ServerLevel.getGameTime()` / `TransferLedgerData.clockNow`），
   而超时判定一度用 `server.getTickCount()`（**进程内**计数、重启归零）⇒ 差值恒为负 ⇒ 运行中产生的挂起
   **永远不过期**（`manual_takeover_required` 降级成了死代码）。这条规则禁止生产路径把进程内计数喂给
   `expireSuspensions` / `suspendUnfinished`。

R2 **替换型派活必须过门禁**：`replaceTaskIfRunning()` 是"有未结清传输就不许换任务"的唯一入口。
   为什么只锁这一族（而不是所有 `assign*`）：电池 / Jobs / 夹具诊断那族**有意**走 `beginTask` 直连
   （台账 §5.9 事实 6：用户必须始终能跑测试），锁上会把有意设计判成违例。

注意：规则只读源码文本（不编译），所以它对"新增一处混用时钟"立刻生效，不需要跑电池。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

# R1：这些方法的时间参数必须来自同一个世界时钟；禁止进程内计数。
CLOCK_METHODS = ("expireSuspensions", "suspendUnfinished")
# R2：必须经过 `replaceTaskIfRunning()`（或经由已过门禁的 `assignWalkTo`）的派活方法。
GATED_METHODS = (
    ("bot/BotManager.java", "public boolean assignTransfer"),
    ("bot/BotManager.java", "public boolean assignFollow"),
    ("bot/BotManager.java", "public boolean assignPlace"),
    ("bot/BotManager.java", "public boolean assignFixtureTask"),
    ("bot/BotManager.java", "public boolean assignWalkTo"),
    ("bot/BotManager.java", "public static boolean assignWalkTo"),
    ("bot/BotManager.java", "public static boolean assignFollow"),
    ("bot/BotManager.java", "public static boolean assignPlace"),
)
# R2 例外（写明理由，避免"静默白名单"）：走 `assignWalkTo` 这条**已过门禁**的路径。
GATED_VIA_ASSIGN_WALKTO = ("assignSurvivalExitCheck",)


def java_files():
    return sorted(SRC.rglob("*.java"))


def method_body(text: str, signature: str) -> str:
    """取该方法的**方法体**：从签名行到"缩进不大于签名行、且以 } 开头"的第一行为止。

    ⚠️ 2026-09-16 实测教训（反向对照抓出来的）：早先版本用 `body.split("\n    }")[0]` 截断 ⇒ 对
    **嵌套类里 8 空格缩进**的方法，`head` 会一直吃到下一个 4 空格缩进的方法（里面往往正好有
    `replaceTaskIfRunning(`）⇒ **规则永远绿**。判据必须先做反向对照（把门禁调用删掉 ⇒ 必须变红）。
    """
    idx = text.find(signature)
    if idx < 0:
        return ""
    line_start = text.rfind("\n", 0, idx) + 1
    indent = len(text[line_start:idx]) - len(text[line_start:idx].lstrip())
    lines = text[idx:].split("\n")
    body = [lines[0]]
    for line in lines[1:]:
        stripped = line.strip()
        cur_indent = len(line) - len(line.lstrip())
        if stripped.startswith("}") and cur_indent <= indent:
            break
        body.append(line)
    return "\n".join(body)


def balanced_args(text: str, open_paren: int) -> str:
    """取配对括号内的完整实参文本（按嵌套计数，别被 `getServer()` 的第一个 ')' 截断）。"""
    depth = 0
    end = min(len(text), open_paren + 400)
    for i in range(open_paren, end):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:i]
    return text[open_paren + 1:end]


def rule_clock():
    violations = []
    for path in java_files():
        text = path.read_text(encoding="utf-8")
        for method in CLOCK_METHODS:
            for match in re.finditer(re.escape(method) + r"\(", text):
                args = balanced_args(text, match.end() - 1)
                if "getTickCount" in args:
                    line = text[:match.start()].count("\n") + 1
                    violations.append(f"{path.relative_to(ROOT)}:{line} {method}(…) 传了 getTickCount()")
    return violations


def rule_gate():
    violations = []
    path = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot" / "BotManager.java"
    text = path.read_text(encoding="utf-8")
    for _, signature in GATED_METHODS:
        body = method_body(text, signature)
        if not body:
            violations.append(f"BotManager.java 找不到派活方法 `{signature}`（方法被改名？同步本规则）")
            continue
        # ⚠️ 必须**去掉签名行本身**：签名里就含 `assignWalkTo(` ⇒ 拿它当证据会永远绿
        #（2026-09-16 反向对照抓出的第二个假绿）。
        head = "\n".join(body.split("\n")[1:])
        # 三种合规形状：① 自己过门禁；② 走已过门禁的 `assignWalkTo`；
        # ③ **静态透传**给会话层（那些会话方法由本表前几条直接检查 ⇒ 透传不算断线）。
        direct = "replaceTaskIfRunning(" in head
        via_walk = "assignWalkTo(" in head
        passthrough = "session.assign" in head
        if not (direct or via_walk or passthrough):
            violations.append(f"BotManager.java `{signature}` 没过 `replaceTaskIfRunning()`（§5.9 门禁接线断了）")
    for name in GATED_VIA_ASSIGN_WALKTO:
        body = method_body(text, name)
        if body and "assignWalkTo(" not in body:
            violations.append(f"BotManager.java `{name}` 既没过 replaceTaskIfRunning 也没走 assignWalkTo")
    return violations


def main() -> int:
    clock = rule_clock()
    gate = rule_gate()
    for line in clock:
        print(f"[R1·时钟] {line}")
    for line in gate:
        print(f"[R2·门禁] {line}")
    ok = not clock and not gate
    print(f"TRANSFER_CLOCK_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"时钟混用={len(clock)} / 门禁断线={len(gate)}"
          f"（R1 = 账本只用一个时钟；R2 = 替换型派活必须过门禁）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
