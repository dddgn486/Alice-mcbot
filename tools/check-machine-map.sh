#!/usr/bin/env bash
# 防漂移断言：机器映射表 vs 上游 jar（不一致即非零退出）
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/machine-map.py" --check
