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
# ⭐ 2026-09-29（`D-536`）：电池 `verdict=FAIL` 且**红全部已登记**时为 1 ⇒ 汇总行按"已登记红"收口。
BATTERY_REGISTERED_REDS=0

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
    # ⭐ 2026-09-29（`D-536`）：**"电池有判决但红"≠"未预期的失败"** ——
    # 只有 `rc=1`（verdict=FAIL）**且**"红全部已在 `docs/EXPECTED_REDS.md` 里"时才算**已登记状态**。
    # ⛔ 这不是放宽：清单外只要多一个红，`check-expected-reds` **自己**就红 ⇒ **两条门禁同时红**（不静默）。
    # 为什么必须做这个合成：`D-532` §六 逐字「编译红 或 `check-all failed>0` ⇒ 立即中止」——
    # 而 `mine_regression` 的红是**计划内红**（它断言的正是已退役的模式 B 主体，修它属批次 2 的夹具改革）
    # ⇒ 不做合成 ⇒ 批次 1 **永远关不了门**（`O47` ⑦ 已经预告过这个后果）。
    if [ "$rc" -eq 1 ] && python3 tools/check-expected-reds.py >/dev/null 2>&1; then
      BATTERY_REGISTERED_REDS=1
      printf '  [FAIL] %-30s %s\n' "check-headless-battery" \
        "verdict=FAIL —— 但红**全部已在预期红清单里**（横切闸门② PASS ⇒ 属**已登记**状态，见汇总行）"
    else
      printf '  [FAIL] %-30s exit=%d（0=PASS 1=FAIL 2=DEGRADED 3=无判决 4=起不来 5=环境/脚本）\n' \
        "check-headless-battery" "$rc"
    fi
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
# 强制力是**跨出处**双向断言（步声明↔CURATION / 模块档位↔CURATION / 注册表↔电池成员 /
# Kind↔契约表 / MovementType↔changesWorld）+ 人口下限 + 解析崩塌即红。
# 起因：`survey/29 §3.8⑥` 要求"结构上不可能分叉"，而活体反例就在 `docs/BATTERY_CURATION.md` §2
#（手写清单的小节计数 15/35/15 vs 真值 15/26/52，靠人读才发现）。
run_gate             "check-capability-list" bash tools/check-capability-list.sh
# 决策索引不许与 `AI_DECISIONS.md` 分叉（台账 `O12`，2026-09-28 用户拍「③ 入口一刀」）。
# 起因：`AGENTS.md` 原文让每个会话「先读 `AI_DECISIONS.md`」，而它是 **23,616 行 / 1,225,967 字符**
# ⇒ 粗估 **0.6M–1.2M tokens** ⇒ **读不进 512K 窗口** ⇒ **那条启动指令按字面不可满足**；
# 而它又是**唯一**的规则出处（找不到前置裁定 ⇒ 重复立法，活例 `D-455` 漏引 `D-080`）。
# ⚠️ 两个它**不是**：① 不是"规则的替代"（是索引，正文仍去 `AI_DECISIONS.md`）
# ② 不是"哪些决策已废弃"的权威（`状态` 只覆盖 **18%**，`—` = 没写，**不等于废弃**）。
# 强制力 = 人口下限（解析崩塌即红）+ 逐字节新鲜度（陈旧即红）。
# ⚠️ 顺带钉住一个数字：**决策编号 492 个**（`##` 独有 114 + `###` 独有 331 + 两者都有 47）
#   —— 只数 `###` 会**漏 114 个**；⚠️ 那个错勘测侧犯过、**我在 `O12` 初版也犯了一次**。
run_gate             "check-decisions-index" bash tools/check-decisions-index.sh
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
# ⭐ `4a` 柱③ 第 2 件：`Kind ↔ 菜单 kind` 的**生成视图 ＋ 双向防漂移**。
# ⚠️ 必须排在 `check-job-menu-listable` **之前** —— 后者读这张生成视图当映射表，
#    所以"视图陈旧"要先被本门禁点名（否则两条门禁一起红，读数会指向错的地方）。
run_gate             "check-job-kind-view"     python3 tools/job-kind-view.py
run_gate             "check-job-menu-listable" bash tools/check-job-menu-listable.sh
# 同名类（`survey/46 §8.1`，2026-09-28 用户拍「按乙来」）：`src/main/java` 下**同名类 ⇒ 红**。
# 起因：术语审计三轮（`survey/44/45/46`）挖到 ⚠️ **`src/` 里真的存在同名类** ——
# `GoalSpec`（`job/` record vs `pathing/core/search/` interface **⚠️ 2026-09-29 已按 `P12/A` 消除**）·
# `DecisionTrace`（`decision/` vs `job/`）。
# ⚠️ 危险在"**靠名字找东西静默多给一半结果**"：`grep 类名` 不报错；而 `pathing` 那个只在同包里用
#（同包 ⇒ 无 `import`）⇒ **`grep import` 根本看不见它**。
# ✅ **时效已兑现（`D-516`）**：`D-478` 的 `P12/A` 把 `job/GoalSpec` 改名 `JobDeclaration` ⇒
# 豁免条目**按"得手也 FAIL"当场判红** ⇒ 逼人同刀删条目 —— ⭐ **本检查第一次在真树上生效**。
# 豁免**逐字比对路径集合** + **豁免得手也 FAIL**。自带 1 对照臂 + 6 红臂（含 R5 `package-info` 必须**过**）。
run_gate             "check-duplicate-class-names" python3 tools/check-duplicate-class-names.py
# ⭐ 粗目标**分类判据**（`D-500` §V，2026-09-29 `D-517` 落地）：**旧口径是点名白名单**
# （只抓字面 `GoalNearXZ.around(`）⇒ ⭐ **新类能从它下面走过去**。
# 新口径 = 凡 `exactFoot() == false` 的实现**必须登记**，且登记表**记录取值**（取值本身是红线开关）。
# ⚠️ Py 重写**同时保留**旧的字面断言作**第二项检查**（互补：分类管**实现**，字面管**调用点**）。
run_gate             "check-far-goal-usage"   python3 tools/check-far-goal-usage.py
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
# 冻结项（用户 2026-09-27 裁定：「**冻结连锁挖掘执行器** —— 它属于模组兼容交付内容，
# 只是一个提前太多的实验性产物」）：
# ⚠️ 必须门禁化，因为那段代码**长得像死代码** —— `MiningTuning.chainMode` 默认 `OFF`、
# 电池里 `[ChainMine]` 0 行、夹具里的连选用例已撤出（`D-470`）⇒ 下一个会话做 `5b`/`L1` 时
# **最可能顺手把它清理掉**，而那不是清理，是销毁一份将来要交付的实验产物。
# 断言（对登记表里每条冻结项）= ① 每个**声明形**符号仍命中（防"静默消失"；⚠️ 只写 `foo(` 会被
# **调用点**满足 ⇒ 真树红臂 R1 实测过这个假绿）② 冻结的功能仍**默认关闭**（改成 AUTO ⇒ 红）
# ③ 提到它的文件**恰好**是登记的那 5 个（多一个 = 冻结期间又长出去了）。
# 自带 5 条合成红臂 + 3 条真树红臂。
run_gate             "check-frozen-code" python3 tools/check-frozen-code.py
# 批量收集调用点的**实参形状**（`step 5b` 刀① / `D-496` 丙①，2026-09-27）：`FishboneJob` 起批量收集时
# 传的 5 个实参（`worldMod=false` / 显式额度 / `STANDABLE_ONLY` / 候选源 `null` / 清单 `PRODUCT_FILTER::matches`）
# 每一个被改坏都**照样编译、照样跑、没有一行报错**，而**行为夹具抓不到**（夹具传的是它自己的实参：
# 生产改成 `true`，`CollectConservationCheckTask` 臂④ 照样绿）⇒ 这一半只能靠**读文本**。
# ⚠️ 命门：`FishboneJob` 里 `PRODUCT_FILTER::matches` 有 **3 处**，其中 `:571`/`:1308` 是 `MineTask` 的实参
#（形状几乎一样）⇒ 锚错就会被它们满足 = **假绿**。合成红臂 R6 专门钉这一条；另有 R1~R5/R7 + 1 对照臂。
# ⛔ 不断言额度的值/常量名（`3甲` 要删那个常量）、不断言调用时机、不断言行为。
run_gate             "check-collect-callsite-shape" python3 tools/check-collect-callsite-shape.py
# 原语读数（`J-★` 第 6 段 step 5 / `D-463`，2026-09-27）：拆 `MineTask` / `CollectDropsTask` 前，
# **先把 4 个验收读数的定义钉死**（`Phase 值数 / 额度词数 / 构造器数 / 行数`）——
# 实测发现台账原登记的那组数（`8/47/5/975` · `0/16/6/1250`）**11 种口径都复现不出来**。
# ⭐ 命门：`MineTask:43` 的枚举是**一行写完**的 ⇒ 按"每常量一行"写的正则**静默返回 1**；
# 而"相位值数下降"正是本步目标 ⇒ **解析崩了与真的为零会印出同一个 0**（`silent-measurement-failure`）。
# 断言只覆盖**不会假红**的量（存在 / 下限 / 解析与引用自洽 / 口径自证）⇒ 用户 2026-09-27 拍：
# **不断言目标值**（值数下降是结果不是判据）。自带 13 条合成红臂。
run_gate             "check-primitive-readings" python3 tools/check-primitive-readings.py
# 脚下安全判据（`D-472`，2026-09-27）：`D-399` C 的"拆了它会让自己掉下去"必须**唯一出处**
# （`MovementHelper.underfootUnsafe`）且用在**三个动作点**上（`MineBlockRunner.canMineInPlace` /
# `RestoreScopeTask.pickNext` / `RestoreScopeTask.startSideBreak`）。
# 为什么门禁化：`D-365` 的"就地挖"跳过 Movement 层（Baritone `MovementDownward.java:61` 同形前置），
# 且 `D-406` §三 的"时机错"实测过一次真摔（`exec_support`：脚位 64 → 59，旧判据还判 PASS）。
run_gate             "check-underfoot-safety"   python3 tools/check-underfoot-safety.py
# 层方向（`J-★` 第 6 段 step 3a / `D-460`，2026-09-27）：`D-455` 定了三层（`action/` < `task/` < `job/`），
# 而 `survey/42 §1.2` 实测**全仓唯一的循环依赖**就是 `action/MineBlockRunner ↔ task/mining/*`。
# step 3a = 把「触及站位」件搬进新顶层包 `reach/`（与 `pathing/` 同级：谁都能依赖它、它谁都不依赖）。
# 断言 = ① `reach/` 不许 import `task|action|job`（防"漏搬一个，循环换个方向长回来"）
# ② `action/` → `task/` 只许剩**表里登记的欠账**（step 3b 后**表空 = 无条件 0 命中**）。
# 自带 6 条合成红臂 + 人口下限（reach ≥4 / action ≥10 / 扫描 ≥480）。
run_gate             "check-baritone-anchor"    python3 tools/check-baritone-anchor.py
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

# 预期红清单（`D-532` §三 横切闸门②）：**实际红 ⊆ `docs/EXPECTED_REDS.md`**。
# 为什么与电池**同条件**（`ALICE_HEADLESS=1` 才跑）：闸门② 的对象**就是电池的判决** ——
# 电池没跑时根本没有"实际红"可比，再记一条 WARN 只会把已有的那条（"电池未执行"）
# 稀释成两条同义告警 ⇒ 这里**不另记**，而是明确挂在电池那一轮里。
# 三态：0 = 集合相等（且清单无陈旧行）· 2 = 断言未执行（无日志/整轮中止/陈旧/SKIP）· 其它 = 红。
# ⚠️ 本函数定义**贴在最底部**（在其调用点之前）是刻意的：位置若前移，会把本文件后面所有行号推走，
# 而 `docs/` 有若干处按 `tools/check-all.sh:NN` 检索（`ref-integrity` 只抓越界、**抓不出"界内但指错"**）
# ⇒ **新增的门禁一律往尾部挂**。⚠️ **如实记**：本次改动**仍然在中间插了行**（顶部的
# `BATTERY_REGISTERED_REDS` 初值 ＋ `run_headless_battery` 里的合成分支）—— 那两处**无法后移**
# （一个必须在使用前、一个必须在电池结果旁）⇒ 那几处 `:NN` 再漂一次，**这正是 `O51` ④ 登记的问题**。
run_expected_reds() {
  if [ "${ALICE_HEADLESS:-0}" != "1" ]; then return; fi
  local out rc
  out="$(python3 tools/check-expected-reds.py 2>&1)"; rc=$?
  case "$rc" in
    0)
      PASSED=$((PASSED + 1))
      printf '  [PASS] %-30s %s\n' "check-expected-reds" \
        "$(printf '%s' "$out" | grep -E 'EXPECTED_REDS_RESULT' | tail -1 | cut -c1-110)"
      ;;
    2)
      WARNED=$((WARNED + 1))
      printf '  [WARN] %-30s %s\n' "check-expected-reds" \
        "断言未执行（无电池日志 / 整轮中止 / 日志陈旧 / 清单里的步本轮 SKIP）"
      printf '%s\n' "$out" | grep -E 'EXPECTED_REDS_RESULT' | sed 's/^/         /'
      ;;
    *)
      FAILED=$((FAILED + 1))
      printf '  [FAIL] %-30s exit=%d\n' "check-expected-reds" "$rc"
      printf '%s\n' "$out" | tail -n 12 | sed 's/^/         /'
      ;;
  esac
}
run_expected_reds

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
# ⭐ 2026-09-29（`D-536`）：**"有判决但红全在清单里"不是未预期的失败**。
# ⛔ 这个分支只在**唯一**的失败就是那一项电池、且它已被上一条门禁确认"红 ⊆ 清单"时才成立：
# 清单外多一个红 ⇒ `check-expected-reds` 也红 ⇒ `FAILED ≥ 2` ⇒ 走下面的 FAIL（不静默、不吞）。
# 为什么必须有它：`D-532` §六 逐字「`check-all failed>0` ⇒ 立即中止」，而计划内红（修它属批次 2）
# 期间电池**必然** `verdict=FAIL` ⇒ 没有这个合成，批次 1 **永远关不了门**。
if [ "$FAILED" -eq 1 ] && [ "$BATTERY_REGISTERED_REDS" -eq 1 ]; then
  printf 'CHECK_ALL_RESULT PASS_WITH_REGISTERED_REDS: pass=%d warning=%d failed=0\n' "$PASSED" "$WARNED"
  printf '⚠️ 唯一"失败"= **电池 verdict=FAIL**，而它的红**全部已在 `docs/EXPECTED_REDS.md` 里**（横切闸门②）。\n'
  printf '⚠️ 这不是"全绿"：红**还在**，只是**每一个都有主**（`D-532` §三 闸门②）。\n'
  exit 0
fi
printf 'CHECK_ALL_RESULT FAIL: pass=%d warning=%d failed=%d\n' "$PASSED" "$WARNED" "$FAILED"
exit 1
