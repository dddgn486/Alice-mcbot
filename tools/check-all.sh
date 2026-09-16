#!/usr/bin/env bash
# T0-b（2026-09-14）：**把 7 道离线门禁串成一条命令**，并接进 CI。
#
# 为什么需要它（出处：`docs/reviews/2026-09-14-项目完成度与优先级审查.md` §1.2 / §3.2）：
#   在此之前 7 道门禁**一道都没进 CI**（`.github/workflows/build.yml` 只跑 `./gradlew build`），
#   全部依赖"人记得跑" ⇒ 门禁红了拦不住 push ⇒ **规则只能活在散文里**，于是被随口重议。
#   本脚本 + CI 那一步，是把"规则"从"散文"搬进"机器"的最小落地。
#
# 判决口径（三态，与 `machine-map.py` 对齐**不把"没复核"当"通过"**）：
#   · PASS    —— 该门禁的断言真的执行了，且通过；
#   · WARN    —— 断言**没执行**（例如 CI 上没有上游模组 jar ⇒ Tier B 无法复核上游覆盖）。
#               WARN **不等于 PASS**，会计入末尾的 `warning=N` 并**醒目打印**；
#   · FAIL    —— 断言执行了但不成立 ⇒ 非零退出码。
#
# 退出码：0 = 没有 FAIL（可以有 WARN）；1 = 至少一道 FAIL。
#
# 环境：
#   · `ALICE_MODS_DIR` 可指定上游模组目录（缺省用 `machine-map.py` 自带的 Windows 客户端路径）；
#   · 没有上游 jar 时 `check-machine-map.sh` 会以 exit 2 表示 INCOMPLETE ⇒ 本脚本记 WARN。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FAILED=0
WARNED=0
PASSED=0

hr() { printf '%s\n' "------------------------------------------------------------------------------"; }

# 普通门禁：0 = PASS，其它 = FAIL
run_gate() {
  local label="$1"; shift
  local out rc
  out="$("$@" 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ]; then
    PASSED=$((PASSED + 1))
    printf '  [PASS] %-30s %s\n' "$label" "$(printf '%s' "$out" | grep -E '_RESULT|_CHECK ' | tail -1 | cut -c1-110)"
  else
    FAILED=$((FAILED + 1))
    printf '  [FAIL] %-30s exit=%d\n' "$label" "$rc"
    printf '%s\n' "$out" | tail -n 12 | sed 's/^/         /'
  fi
}

# 无头回归电池（T2）：**默认不跑**（要 4 分钟 + 需要一次性装好的生产服务端）。
# 为什么做成"可选门禁"而不是"另一个脚本"：AGENTS.md 的尺子要求**任何新验证手段必须挂在已有命令上**
# —— 挂在旁边的验证 = 下一个 `BotSelftest`（被删了都没人发现）。不跑时**记为 WARN（断言没执行）**，
# 绝不写成 PASS。跑法：`ALICE_HEADLESS=1 bash tools/check-all.sh`
run_headless_battery() {
  if [ "${ALICE_HEADLESS:-0}" != "1" ]; then
    WARNED=$((WARNED + 1))
    printf '  [WARN] %-30s %s\n' "check-headless-battery" \
      "未执行（设 ALICE_HEADLESS=1 才跑；需 tools/headless-battery.sh --install 一次）"
    return
  fi
  local out rc
  out="$(bash tools/headless-battery.sh core 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ]; then
    PASSED=$((PASSED + 1))
    printf '  [PASS] %-30s %s\n' "check-headless-battery" \
      "$(printf '%s' "$out" | grep -E '^\[headless\] verdict' | tail -1 | cut -c1-110)"
  else
    FAILED=$((FAILED + 1))
    printf '  [FAIL] %-30s exit=%d（0=PASS 1=FAIL 2=DEGRADED 3=无判决 4=起不来 5=环境/脚本）\n' \
      "check-headless-battery" "$rc"
    printf '%s\n' "$out" | tail -n 12 | sed 's/^/         /'
  fi
}

# 文档预算（`AGENTS.md` 的「规则准入尺子」）：三项总行数**冻结**，新增必须删旧的（净增 ≤ 0）。
# 为什么把它做成机器断言：否则"冻结在 1,476 行"本身又只是一条**散文规则** ——
# 而本项目的问题恰恰是"规则≈95% 靠人记得"。这一条让"规则膨胀"变成**会失败**的事。
run_doc_budget() {
  local budget=1476 total
  total=$(cat AGENTS.md docs/AI_DEVELOPMENT_PLAYBOOK.md docs/AI_PROJECT_STATE.md | wc -l)
  if [ "$total" -le "$budget" ]; then
    PASSED=$((PASSED + 1))
    printf '  [PASS] %-30s %s\n' "check-doc-budget" \
      "AGENTS+PLAYBOOK+STATE = $total 行 ≤ $budget（余额 $((budget - total))）"
  else
    FAILED=$((FAILED + 1))
    printf '  [FAIL] %-30s 超预算 %d 行（实际 %d / 上限 %d）\n' "check-doc-budget" "$((total - budget))" "$total" "$budget"
    printf '         ⇒ 按 AGENTS.md「规则准入尺子」：新增一条散文规则必须同时删掉一条旧的。\n'
    printf '         若要**有理由地**上调预算，请改本函数的 budget 并在 commit message 写明为什么。\n'
  fi
}
run_machine_map() {
  local out rc
  local args=(--check)
  if [ -n "${ALICE_MODS_DIR:-}" ]; then
    args+=(--mods-dir "$ALICE_MODS_DIR")
  fi
  out="$(python3 tools/machine-map.py "${args[@]}" 2>&1)"; rc=$?
  case "$rc" in
    0)
      PASSED=$((PASSED + 1))
      printf '  [PASS] %-30s %s\n' "check-machine-map" "$(printf '%s' "$out" | tail -1 | cut -c1-110)"
      ;;
    2)
      WARNED=$((WARNED + 1))
      printf '  [WARN] %-30s %s\n' "check-machine-map" "INCOMPLETE：缺上游 jar ⇒ **上游双向覆盖断言本轮未执行**"
      printf '%s\n' "$out" | grep -E '^\[UNVERIFIED\]|INCOMPLETE' | sed 's/^/         /'
      printf '         ⇒ 要复核上游覆盖请设 ALICE_MODS_DIR 指向含模组 jar 的目录后重跑\n'
      ;;
    *)
      FAILED=$((FAILED + 1))
      printf '  [FAIL] %-30s exit=%d\n' "check-machine-map" "$rc"
      printf '%s\n' "$out" | tail -n 12 | sed 's/^/         /'
      ;;
  esac
}

printf '\n=== Alice 离线门禁总检（%s） ===\n' "$(date '+%Y-%m-%d %H:%M')"

run_gate             "check-item-models"        bash tools/check-item-models.sh
run_gate             "check-precharge-containment" bash tools/check-precharge-containment.sh
run_gate             "check-provision-containment" bash tools/check-provision-containment.sh
run_gate             "check-fixture-hygiene"    bash tools/check-fixture-hygiene.sh
# §5.9（2026-09-16）：传输账本**只用一个时钟**（混用 `getTickCount()` 会让挂起永不过期 = 永久阻塞）
# + 替换型派活必须过 `replaceTaskIfRunning()` 门禁。
run_gate             "check-transfer-clock"    bash tools/check-transfer-clock.sh
run_gate             "check-policy-matrix"      bash tools/check-policy-matrix.sh
run_gate             "check-authz-registry"     bash tools/check-authz-registry.sh
run_machine_map
run_gate             "check-scene-connectivity" python3 tools/check-scene-connectivity.py --all
run_headless_battery
run_doc_budget

hr
if [ "$FAILED" -eq 0 ]; then
  if [ "$WARNED" -gt 0 ]; then
    printf 'CHECK_ALL_RESULT PASS_WITH_WARNINGS: pass=%d warning=%d failed=0\n' "$PASSED" "$WARNED"
    printf '⚠️  warning=%d ⇒ 有断言**本轮没有执行**（不是通过）。别把这一行读成"全绿"。\n' "$WARNED"
  else
    printf 'CHECK_ALL_RESULT PASS: pass=%d warning=0 failed=0\n' "$PASSED"
  fi
  exit 0
fi
printf 'CHECK_ALL_RESULT FAIL: pass=%d warning=%d failed=%d\n' "$PASSED" "$WARNED" "$FAILED"
exit 1
