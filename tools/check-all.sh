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
# 内核 §2（2026-09-16 复核）：K-4 执行工厂必须用规划侧同一谓词 / K-5 声明了的状态必须有生产者。
run_gate             "check-kernel-predicates" bash tools/check-kernel-predicates.sh
run_gate             "check-risk-surface" bash tools/check-risk-surface.sh
# B3 / Q-22（2026-09-23）：「它知道的自己」= **从代码生成的能力清单**（`docs/CAPABILITY_LIST.md`）。
# 牙齿是**跨出处**双向断言（步声明↔CURATION / 模块档位↔CURATION / 注册表↔电池成员 /
# Kind↔契约表 / MovementType↔changesWorld）+ 人口下限 + 解析崩塌即红。
# 起因：`survey/29 §3.8⑥` 要求"结构上不可能分叉"，而活体反例就在 `docs/BATTERY_CURATION.md` §2
#（手写清单的小节计数 15/35/15 vs 真值 15/26/52，靠人读才发现）。
run_gate             "check-capability-list" bash tools/check-capability-list.sh
  # G3（2026-09-21 用户裁定「这不是小事」）：架构红线必须带门禁指针，或带**复核触发**的「未门禁」标记。
  # 起因：6 条红线里只有 D-076 真被门禁覆盖，而 D-374 恰落在零门禁的 D-036 上 ⇒ 没人会因此变红。
run_gate             "check-redline-gates"   bash tools/check-redline-gates.sh
# 动作词汇单一出处（D-267 F2，survey/16 §5.4 + survey/17 复核）：prompt 列出的动作
# 必须与 `GoalAction.parse` 的白名单**集合相等**（加动作要同时改两处，漂了就必须响）
run_gate             "check-goal-vocabulary"   bash tools/check-goal-vocabulary.sh
# 站点映射单一出处（队列第①项，survey 14 §11-1 / survey 15 §3.6）：
# `RecipeDump.STATION_BY_TYPE`（运行时类型 id）与 `tools/recipe-graph.py`（数据包序列化器 id）必须一致
run_gate             "check-station-mapping"   bash tools/check-station-mapping.sh
# 引用完整性（队列第⑦项）：文档里的 `文件:行` 不得过期（行号超界 = 必然过期；
# "文件不存在"只提示不失败 —— 历史记录/提案会合法引用已删文档与未实现文件）
run_gate             "check-ref-integrity"     bash tools/check-ref-integrity.sh
# SH-P1（2026-09-17）：文档/脚本里的 `single:<步名>` 必须真的存在（本轮实测：写错步名会白跑 200 tick）。
run_gate             "check-step-names"       bash tools/check-step-names.sh
run_gate             "check-job-kind-contracts" bash tools/check-job-kind-contracts.sh
run_gate             "check-job-menu-listable" bash tools/check-job-menu-listable.sh
run_gate             "check-far-goal-usage"   bash tools/check-far-goal-usage.sh
# 终态执行记录接线（D-258 复核发现的 J-1/J-3）：taskKind 必须用 taskName()；terminalReason/botId 必须进快照。
run_gate             "check-exec-record"      bash tools/check-exec-record.sh
run_gate             "check-policy-matrix"      bash tools/check-policy-matrix.sh
run_gate             "check-authz-registry"     bash tools/check-authz-registry.sh
# 保护作用域安装点（`1.4r`，2026-09-26）：`TaskTargetProtection.begin*` 不许装在构造器里 ——
# `BotManager:1998` 的 `beginTask` 会在构造器之后按 botId 清空它 ⇒ 生产侧护栏一直是空的，
# 而电池**直驱子任务**（不过 `beginTask`）看不到 ⇒ 判据只能是静态门禁（`D-425`）。
# 自带 6 条合成红臂（构造器 / `if` 嵌构造器 / 静态块 / 注释假命中 / `tickOnce` / 类名别名）。
run_gate             "check-protection-install-point" python3 tools/check-protection-install-point.py
# 额度归 Job（`J-★` 第 6 段 step 2a / `D-459`，2026-09-27）：**任务不许自带额度** ——
# `MineTask` 曾有一个"调用方不说额度也能用"的便利构造器（自己 `MiningBudget.forTarget(..., true)`
# ⇒ `RoadBuildTask` 从不知道自己是"收掉落物 + 放支撑块"那一档）= 原语自带额度的入口。
# 断言 = 额度制造必须出现在**非构造器的方法体**里；自带 6 条合成红臂 + 人口下限（防抽空即假绿）。
# ⚠️ 边界写在门禁里：**不含**"类内默认额度常量"（= step 2b / step 5），别把绿读成 ⑧③ 全合规。
run_gate             "check-primitive-budget-injection" python3 tools/check-primitive-budget-injection.py
# 相位转换唯一出口（`J-★` 第 6 段 step 5a-0 / `D-464`，2026-09-27）：
# `plan §2.2` 禁令③（每个相位值有外部可验证的进出条件）今天**不成立** —— `phase=` 这个字段只在
# `miner.tick()` 之后与失败报告里打印 ⇒ CLEAR/GAIN_CLEAR/GAIN/CHAIN/COLLECTING/RESTORE 不会以 `phase=`
# 出现（语料 1080 份里除 `no_suitable_tool@EVALUATING` 之外，没有任何 Phase 枚举名出现过）。
# 根因 = 转换点散在 14 处直接赋值（其中 3 处连专用日志都没有）⇒ 收成一个 `enterPhase(Phase)`。
# 断言 = `MineTask` 里 `phase = Phase.X;` 的直接赋值 **0 处** + `enterPhase(` 调用点 ≥8 + 方法体真赋值
# （防"掏空"假绿）。自带 11 条红臂。⚠️ 它同时是 step 5a 搬编排时的安全带（过渡点缺/多一个会红）。
# ⚠️ step 5a 落地后的读数：转换点 **14 → 13**（`tickOnce` 里那条执行段尾巴被切成
# `tickEvaluating` / `tickMining` 两半，`enterPhase(MINING)` 随执行段归位）。
run_gate             "check-phase-transition-outlet" python3 tools/check-phase-transition-outlet.py
# 原子/编排分家（`J-★` 第 6 段 step 5a / `D-466` 十拍 + `D-469` 落地，2026-09-27）：
# `MineTask` 里"跑一格"的那一段切进了新原语 `task/mining/MineStep.java`。边界**不能靠读代码守**
# （本仓 `D-178` 的"终态硬编码"曾同时存在 6 处而无人发现）⇒ 写成五条可失败断言：
# A 原语无相位机（`Phase`/`phase` 各 0）· B 成功出口**恰好 1** + 四个结论工厂正向人口 ·
# C① 原语 `new MineTask(` 0 处 · C② 编排器委托点 ≥5（防把原语架空）·
# D1 原语额度只来自构造参数（比 `check-primitive-budget-injection` 更严：方法体里造也红）·
# D2 额度消费点**恰好 1** 且必须是具名的那一处（`miningPlanner.plan(`）。
# 自带 19 条红臂（每臂只打一条判据 ⇒ "红"必然红在那一条上）。
run_gate             "check-task-orchestration-split" python3 tools/check-task-orchestration-split.py
# 原语读数（`J-★` 第 6 段 step 5 / `D-463`，2026-09-27）：拆 `MineTask` / `CollectDropsTask` 前，
# **先把 4 个验收读数的定义钉死**（`Phase 值数 / 额度词数 / 构造器数 / 行数`）——
# 实测发现台账原登记的那组数（`8/47/5/975` · `0/16/6/1250`）**11 种口径都复现不出来**。
# ⭐ 命门：`MineTask:43` 的枚举是**一行写完**的 ⇒ 按"每常量一行"写的正则**静默返回 1**；
# 而"相位值数下降"正是本步目标 ⇒ **解析崩了与真的为零会印出同一个 0**（`silent-measurement-failure`）。
# 断言只覆盖**不会假红**的量（存在 / 下限 / 解析与引用自洽 / 口径自证）⇒ 用户 2026-09-27 拍：
# **不断言目标值**（值数下降是结果不是判据）。自带 13 条合成红臂。
run_gate             "check-primitive-readings" python3 tools/check-primitive-readings.py
# 层方向（`J-★` 第 6 段 step 3a / `D-460`，2026-09-27）：`D-455` 定了三层（`action/` < `task/` < `job/`），
# 而 `survey/42 §1.2` 实测**全仓唯一的循环依赖**就是 `action/MineBlockRunner ↔ task/mining/*`。
# step 3a = 把「触及站位」件搬进新顶层包 `reach/`（与 `pathing/` 同级：谁都能依赖它、它谁都不依赖）。
# 断言 = ① `reach/` 不许 import `task|action|job`（防"漏搬一个，循环换个方向长回来"）
# ② `action/` → `task/` 只许剩**表里登记的欠账**（step 3b 后**表空 = 无条件 0 命中**）。
# 自带 6 条合成红臂 + 人口下限（reach ≥4 / action ≥10 / 扫描 ≥480）。
run_gate             "check-layer-direction"     python3 tools/check-layer-direction.py
# 回迁（2026-09-24）：`tools/dsh-session-rollback.mjs` 决定"云端哪些字节要搬回本机" ——
# 决定错了**不会响**（本机会安静地留一个半截会话）⇒ 自检必须进构建：
# 分叉判 suffix（只搬前缀之后那段）/ 逐字节相同判 skip / 无公共前缀判 whole / 重建 sha256 必须对得上 /
# 反向臂：故意改坏一个 part ⇒ rebuild 必须 exit 1。
run_gate             "check-rollback-delta"   node tools/dsh-session-rollback.mjs selftest
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
