#!/usr/bin/env bash
# 防漂移断言：机器映射表 vs 上游 jar（不一致即非零退出）
set -euo pipefail
# ⭐ 2026-09-24（云端接管）：默认 mods 目录写死在本地 Windows 路径 ⇒ 云端/其它机器跑这一项必然 INCOMPLETE。
# 允许用 `ALICE_MODS_DIR` 指路（与无头电池同一个变量名，见 `docs/CLOUD_MIGRATION.md §14`）。
# ⚠️ 指路只是"能查"，**不改变判据**：目标目录里缺的 jar 仍会如实报 `未复核`（不许把 INCOMPLETE 说成 PASS）。
if [ -n "${ALICE_MODS_DIR:-}" ] && [ -d "${ALICE_MODS_DIR}" ]; then
    exec python3 "$(cd "$(dirname "$0")" && pwd)/machine-map.py" --mods-dir "$ALICE_MODS_DIR" --check
fi
exec python3 "$(cd "$(dirname "$0")" && pwd)/machine-map.py" --check
