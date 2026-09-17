#!/usr/bin/env bash
# G-P1（D-267 F2）：动作词汇（prompt）与白名单（GoalAction.parse）必须一致。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/goal-vocabulary.py
