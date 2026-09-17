#!/usr/bin/env bash
# **R-2 验收工具（用户硬要求）**：逐个模块**单独跑**，全 PASS 才算过 ✓。
# 用户原话：「每个电池步按分类模块化，**一个模块保证可以单独测**」。
#
# 用法：
#   tools/module-selftest.sh                # 跑全部模块
#   tools/module-selftest.sh ledger         # 只跑指定模块
#   tools/module-selftest.sh --no-build     # 复用现有工件（默认每轮重建）
#
# 退出码：0 = 全部 PASS；1 = 有模块 FAIL；2 = 连模块清单都拿不到（基础设施坏了 ✗ 不许静默通过）
set -o pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EXTRA_ARGS=()
WANTED=()
for arg in "$@"; do
    case "$arg" in
        --no-build) EXTRA_ARGS+=(--no-build) ;;
        -*) echo "未知选项：$arg" >&2; exit 5 ;;
        *) WANTED+=("$arg") ;;
    esac
done

# ① 模块清单：从模组自己报（唯一出处 ✓），不手抄
LIST_LOG="$(mktemp)"
ALICE_HEADLESS=1 timeout 300 tools/headless-battery.sh list-modules "${EXTRA_ARGS[@]}" > "$LIST_LOG" 2>&1
IDS="$(grep -aoE 'MODULES ids=[a-z0-9_,]*' "$LIST_LOG" | tail -1 | cut -d= -f2)"
# 期望判决（模块自己声明 ✓：默认 PASS；自检/反例模块声明 FAIL ⇒ 不把"故意失败"当回归 ✗）
EXPECTED_RAW="$(grep -aoE 'MODULES expected=[a-zA-Z0-9_,:]*' "$LIST_LOG" | tail -1 | cut -d= -f2)"
declare -A EXPECTED
if [ -n "$EXPECTED_RAW" ]; then
    IFS=',' read -r -a PAIRS <<< "$EXPECTED_RAW"
    for pair in "${PAIRS[@]}"; do
        EXPECTED["${pair%%:*}"]="${pair##*:}"
    done
fi
if [ -z "$IDS" ]; then
    echo "[module-selftest] 拿不到模块清单 ✗（list-modules 没输出）—— 基础设施坏了，不许当通过" >&2
    tail -5 "$LIST_LOG" >&2
    exit 2
fi
IFS=',' read -r -a ALL_IDS <<< "$IDS"
if [ "${#WANTED[@]}" -gt 0 ]; then ALL_IDS=("${WANTED[@]}"); fi

echo "[module-selftest] 模块清单：${ALL_IDS[*]}（共 ${#ALL_IDS[@]} 个）"
FAILED=()
PASSED=()
for id in "${ALL_IDS[@]}"; do
    echo "──────── 模块 $id ────────"
    OUT="$(ALICE_HEADLESS=1 timeout 1800 tools/headless-battery.sh "module:$id" "${EXTRA_ARGS[@]}" 2>&1)"
    VERDICT="$(printf '%s\n' "$OUT" | grep -aoE 'verdict=[A-Za-z_]*' | tail -1 | cut -d= -f2)"
    # 每步明细在服务端 stdout 文件里（harness 脚本只把判决行回显到 stdout ✓）
    grep -a '\[Harness\]' /tmp/alice-headless-server.log 2>/dev/null | sed 's/^.*\[Harness\]/  [Harness]/' | tail -12
    WANT="${EXPECTED[$id]:-PASS}"
    echo "  ⇒ module:$id verdict=${VERDICT:-<无>}（期望 $WANT）"
    if [ "$VERDICT" = "$WANT" ]; then PASSED+=("$id"); else FAILED+=("$id(期望 $WANT 实得 ${VERDICT:-<无>})"); fi
done

echo "════════════════════════════════"
echo "[module-selftest] PASS ${#PASSED[@]}/${#ALL_IDS[@]}：${PASSED[*]}"
if [ "${#FAILED[@]}" -gt 0 ]; then
    echo "[module-selftest] FAIL：${FAILED[*]}"
    exit 1
fi
echo "[module-selftest] 全部模块可单独跑通 ✓（这就是"一个模块可以单独测"的判据）"
exit 0
