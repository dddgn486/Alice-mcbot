#!/usr/bin/env bash
# 门禁：**`生效` 的裁定必须带批准痕迹**（`docs/AI_DECISIONS.md`）。
#
# 为什么是**独立门禁**（用户 2026-10-02 裁定）：`C4` 属「**新增**门禁」（🟢 自决档），
# 而「改**已有**门禁的判据」属 🔴 必须审批 ⇒ ⛔ 不能塞进 `check-decisions-index`。
#
# 真正的强制力在 `tools/check-effective-trace.py`：
#   · **旧账基线 33 条只许变短**（按字面挂会立刻红 33 处，而那些裁定当年靠会话记录批准 ⇒
#     直接挂会逼出"为了消红而给历史编批注"）；· **人口下限 30**（扫不到 ⇒ 响亮失败，⛔ 不许静默报绿）。
#
# ⚠️ 它**不**声称基线里那 33 条是假的：它只说"**这些条目的批准行为不在条目里**"。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/check-effective-trace.py
