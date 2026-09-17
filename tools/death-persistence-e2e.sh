#!/usr/bin/env bash
# D-276 端到端（第 2 半）：**死亡 ⇒ 数据落盘 ⇒ 重启读回"倒下态"**（两轮无头跑完，不用客户端）。
#
# 为什么需要它：夹具 `death_persistence` **不杀 bot**（只验证恢复决策与倒下态内容）；
# 这一步把"真的死一次 + 存档 + 重启"补齐 —— 它是 D-276 登记的唯一诚实缺口。
#
# 用法：tools/death-persistence-e2e.sh [--no-build]
set -uo pipefail
cd "$(dirname "$0")/.."

NO_BUILD_FLAG=""
[ "${1:-}" = "--no-build" ] && NO_BUILD_FLAG="--no-build"

SERVER_DIR="${ALICE_SERVER_DIR:-/home/fb486/alice-server}"
WORLD="$SERVER_DIR/world"
LOG="$SERVER_DIR/logs/latest.log"
FAIL=0
note() { printf '\n\033[1m── %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=1; }

note "第 1 轮：弄死探针 bot（存档打开：saveOnHalt）"
ALICE_HEADLESS=1 \
ALICE_KEEP_ALICE_DATA=1 \
ALICE_EXTRA_JVM_ARGS="-Dalice.headless.saveOnHalt=true" \
    tools/headless-battery.sh single:death_kill_bot --keep-world $NO_BUILD_FLAG 2>&1 | tail -3
grep -aq "verdict=PASS" "$LOG" && ok "第 1 轮判决 PASS（探针已死且存档带倒下标记）" \
    || bad "第 1 轮没有 PASS（见 $LOG）"
grep -a "\[DeathE2E\]" "$LOG" | tail -2 | sed 's/.*\[DeathE2E\]/  [DeathE2E]/' | cut -c1-160

note "落盘检查：world/data/alice_*.dat 里应当有 AliceFallen"
DAT="$(ls "$WORLD"/data/alice_*.dat 2>/dev/null | head -1)"
if [ -z "$DAT" ]; then
    bad "找不到 $WORLD/data/alice_*.dat（存档没落盘？）"
else
    ok "找到存档文件：$(basename "$DAT")"
    if python3 - "$DAT" <<'PY'
import gzip, sys
raw = open(sys.argv[1], "rb").read()
try:
    raw = gzip.decompress(raw)
except OSError:
    pass
text = raw.decode("utf-8", "ignore")
need = ["AliceFallen", "AliceFallenCause", "AliceE2E"]
missing = [n for n in need if n not in text]
if missing:
    print("  缺少标记：", missing)
    sys.exit(1)
print("  找到标记：", ", ".join(need))
PY
    then ok "存档里带 AliceFallen / 死因 / 探针名"; else bad "存档里缺倒下标记（旧行为就会是这样）"; fi
fi

note "第 2 轮：不重置世界重启，应当打出「存档假人处于倒下态」"
ALICE_HEADLESS=1 \
ALICE_KEEP_ALICE_DATA=1 \
    tools/headless-battery.sh single:speech_channel --reuse-world $NO_BUILD_FLAG 2>&1 | tail -2
if grep -aq "存档假人处于\*\*倒下态\*\*" "$LOG"; then
    ok "重启读到倒下态（数据保留）：$(grep -a '存档假人处于' "$LOG" | tail -1 | sed 's/.*存档假人/存档假人/' | cut -c1-120)"
else
    bad "重启没打出「倒下态」（数据被清了，或恢复路径被改回旧行为）"
fi
grep -aq "verdict=PASS" "$LOG" && ok "第 2 轮判决 PASS（服务端带着倒下态记录正常起跑）" \
    || bad "第 2 轮没有 PASS"

note "结论"
if [ "$FAIL" = "0" ]; then
    echo "  DEATH_E2E_RESULT PASS：死亡 ⇒ 存档带倒下标记 ⇒ 重启读回倒下态（D-276 端到端成立）"
else
    echo "  DEATH_E2E_RESULT FAIL：见上面 ✗ 项"
fi
exit "$FAIL"
