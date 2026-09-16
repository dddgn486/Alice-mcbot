#!/usr/bin/env bash
# 终态执行记录接线（J-1/J-3）：taskKind 用 taskName()、terminalReason/botId 必须进快照；违例即非零退出
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/exec-record.py"
