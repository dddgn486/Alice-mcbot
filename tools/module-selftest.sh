#!/usr/bin/env bash
# **R-2 验收工具（用户硬要求）**：逐个模块**单独跑**，全 PASS 才算过 ✓。
# 用户原话：「每个电池步按分类模块化，**一个模块保证可以单独测**」。
#
# 用法：
#   tools/module-selftest.sh                # 跑全部模块
#   tools/module-selftest.sh ledger         # 只跑指定模块
#   tools/module-selftest.sh --changed      # **只跑本轮碰过的模块**（A+B 批量验收口径，见 D-304）
#   tools/module-selftest.sh --changed --list   # 只打印选中的模块，不起服务端（自查用）
#   tools/module-selftest.sh --no-build     # 复用现有工件（默认每轮重建）
#
# `--changed` 的选法（**失败安全**：判不出来就跑全部，绝不静默跑 0 个 ✗）：
#   · 改动落在**框架文件**（CheckHarness / CheckStep / CheckModule / CheckContext / BotManager /
#     FixturePremise）⇒ 跑全部（框架语义变了，每个模块都可能受影响 ✓）
#   · 改动落在 `modules/XxxModule.java` ⇒ 由文件里 `return "id";` 读出该模块 id（**唯一出处** ✓）
#   · 改动落在 `CheckModules.java` ⇒ 看 diff 里**新增**了哪些 `new XxxModule()`，只把那些算进来
#     （否则"每次注册新模块都跑全量"，批量验收就没意义了）
#   · 有模块被**移除**或 id 解析不出来 ⇒ 跑全部并出声 ✓
#
# 退出码：0 = 全部 PASS；1 = 有模块 FAIL；2 = 连模块清单都拿不到（基础设施坏了 ✗ 不许静默通过）
set -o pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# **自完整性守卫（2026-09-17 实测教训）**：bash **按需读脚本文件** —— 我在这脚本跑着（8 个模块、约 15 分钟）
# 的时候改了它（加 `--changed`）⇒ 那一轮在收尾处炸出 `syntax error near unexpected token 'fi'`：
# **逐模块判决都印出来了，但整轮退出码作废**（"判决行可见"≠"这一轮是绿的" ✗）。
# ⇒ 纪律不写散文，做成脚本行为：跑前记下自己的摘要，跑完再比一次，**被改过就把结论标成不可信并返回非 0** ✓。
SELF_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SELF_SUM_BEFORE="$(sha256sum "$SELF_PATH" 2>/dev/null | cut -d' ' -f1)"
self_intact() {
    local now
    now="$(sha256sum "$SELF_PATH" 2>/dev/null | cut -d' ' -f1)"
    # **失败关闭**（第一版是"取不到摘要 ⇒ 两边都是空 ⇒ 判相等 ⇒ 静默通过"✗ —— 自查时实测踩到：
    #  `BASH_SOURCE[0]` 在被 `bash -c` 包起来时是空的 ⇒ 守卫**恒绿**。凡"算不出摘要"一律当**不可信** ✓）
    if [ -z "$now" ] || [ -z "$SELF_SUM_BEFORE" ]; then
        echo "[module-selftest] ⚠️ 取不到脚本摘要（path=$SELF_PATH）⇒ 无法证明本轮未被改过 ⇒ 按不可信处理 ✗" >&2
        return 1
    fi
    if [ "$now" != "$SELF_SUM_BEFORE" ]; then
        echo "[module-selftest] ⚠️ 本脚本在**运行期间被修改过** ⇒ 本轮退出码/汇总**不可作为验收证据** ✗" >&2
        echo "[module-selftest]    （逐模块判决行仍可用于排查，但「这一轮是绿的」必须重跑一次才算 ✓）" >&2
        return 1
    fi
    return 0
}

EXTRA_ARGS=()
WANTED=()
MODDIR="src/main/java/com/dddgn/alice/task/check/modules"
CHANGED=0
LIST_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --no-build) EXTRA_ARGS+=(--no-build) ;;
        --changed) CHANGED=1 ;;
        --list) LIST_ONLY=1 ;;
        -*) echo "未知选项：$arg" >&2; exit 5 ;;
        *) WANTED+=("$arg") ;;
    esac
done

# ---- `--changed`：本轮碰过的模块（见文件头 D-304 的选法）----
CHANGED_IDS=()
if [ "$CHANGED" = 1 ]; then
    FRAMEWORK=("src/main/java/com/dddgn/alice/task/check/CheckHarness.java"
               "src/main/java/com/dddgn/alice/task/check/CheckStep.java"
               "src/main/java/com/dddgn/alice/task/check/CheckModule.java"
               "src/main/java/com/dddgn/alice/task/check/CheckContext.java"
               "src/main/java/com/dddgn/alice/bot/BotManager.java"
               "src/main/java/com/dddgn/alice/task/FixturePremise.java")
    DIRTY="$(git status --porcelain | awk '{print $NF}'; git diff --name-only HEAD)"
    ALL_OF_THEM=0
    for f in "${FRAMEWORK[@]}"; do
        if printf '%s\n' "$DIRTY" | grep -qx "$f"; then ALL_OF_THEM=1; echo "[module-selftest] --changed：框架文件变了（$f）⇒ 跑全部 ✓"; fi
    done
    if [ "$ALL_OF_THEM" = 0 ]; then
        while IFS= read -r f; do
            [ -z "$f" ] && continue
            case "$f" in
                "$MODDIR"/*.java)
                    id="$(grep -oE 'return "[a-z0-9_]+";' "$f" | head -1 | sed 's/return "//; s/";//')"
                    if [ -n "$id" ]; then CHANGED_IDS+=("$id"); else echo "[module-selftest] 解析不出 $f 的模块 id ⇒ 跑全部 ✓"; ALL_OF_THEM=1; fi
                    ;;
            esac
        done <<< "$DIRTY"
        # CheckModules 注册表：只认**新增**的 `new XxxModule()`（否则每次注册都跑全量，批量验收失去意义）
        if printf '%s\n' "$DIRTY" | grep -qx "src/main/java/com/dddgn/alice/task/check/CheckModules.java"; then
            while IFS= read -r cls; do
                [ -z "$cls" ] && continue
                f="$MODDIR/$cls.java"
                if [ -f "$f" ]; then
                    # **只认 HEAD 里还没有的类**：给上一行补个逗号也会让 `new MiningModule()` 出现在 + 行里，
                    # 那不是"新增模块" ⇒ 先查 HEAD 的注册表里有没有它（自查时实测踩到过这个假阳性 ✓）
                    if git show HEAD:src/main/java/com/dddgn/alice/task/check/CheckModules.java \
                            | grep -q "new $cls()"; then
                        continue
                    fi
                    id="$(grep -oE 'return "[a-z0-9_]+";' "$f" | head -1 | sed 's/return "//; s/";//')"
                    [ -n "$id" ] && CHANGED_IDS+=("$id")
                fi
            done < <(git diff HEAD -- src/main/java/com/dddgn/alice/task/check/CheckModules.java \
                     | grep -E '^\+.*new [A-Za-z]+Module\(\)' | grep -oE 'new [A-Za-z]+Module' | sed 's/new //')
        fi
    fi
    if [ "$ALL_OF_THEM" = 1 ]; then
        CHANGED_IDS=("__ALL__")
    fi
    if [ "${#CHANGED_IDS[@]}" -eq 0 ]; then
        echo "[module-selftest] --changed：没有检测到受影响的模块（只改了文档/工具？）⇒ 无事可做 ✓"; exit 0
    fi
    if [ "${CHANGED_IDS[0]}" != "__ALL__" ]; then
        WANTED=($(printf '%s\n' "${CHANGED_IDS[@]}" | sort -u))
    fi
fi

# ⓪ `--list`：**静态**列出将跑的模块（不 build、不起服务端、秒回 ✓）
if [ "$LIST_ONLY" = 1 ]; then
    STATIC_IDS=()
    for f in "$MODDIR"/*.java; do
        case "$(basename "$f")" in *Module.java) ;; *) continue ;; esac
        id="$(grep -oE 'return "[a-z0-9_]+";' "$f" | head -1 | sed 's/return "//; s/";//')"
        [ -n "$id" ] && STATIC_IDS+=("$id")
    done
    if [ "${#WANTED[@]}" -gt 0 ]; then
        echo "[module-selftest] --list：将跑 ${WANTED[*]}（共 ${#WANTED[@]} 个，已由 --changed/参数选定 ✓）"
    else
        echo "[module-selftest] --list：将跑 ${STATIC_IDS[*]}（共 ${#STATIC_IDS[@]} 个 = 全部模块 ✓）"
    fi
    exit 0
fi

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
if ! self_intact; then
    exit 4
fi
if [ "${#FAILED[@]}" -gt 0 ]; then
    echo "[module-selftest] FAIL：${FAILED[*]}"
    exit 1
fi
echo "[module-selftest] 全部模块可单独跑通 ✓（这就是"一个模块可以单独测"的判据）"
exit 0
