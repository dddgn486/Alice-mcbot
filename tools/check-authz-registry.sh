#!/usr/bin/env bash
# 防过期断言：注册表 vs 代码（不一致即非零退出）
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/authz-map.py" --check
