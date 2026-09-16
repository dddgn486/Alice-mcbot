#!/usr/bin/env bash
# §5.9 的两条可执行规则（R1 账本只用一个时钟 / R2 替换型派活必须过门禁）；违例即非零退出
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/transfer-clock.py"
