#!/usr/bin/env bash
# 防"静默绿"断言：自检夹具必须把 failures 传播到终态（违例即非零退出）
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/fixture-hygiene.py"
