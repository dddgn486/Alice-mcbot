#!/usr/bin/env python3
"""内核**规划/执行谓词与状态**的可执行规则（挂在 `tools/check-all.sh` 上；失败即构建红）。

出处：内核审计 §2（K-4 规划/执行谓词不统一、K-5 死状态）在 2026-09-16 的复核。

K4-P1 **执行工厂不许自己手搓一份"看起来一样"的准入判据**：TRAVERSE/DIAGONAL/ASCEND/DESCEND 四个
   `*ExecutionFactory.validate` 必须引用规划侧同一谓词（`MovementHelper.canTraverse` / `canAscend` /
   `canDescend`）。为什么：两份判据会漂移，而方向危险的是"**执行比内核宽松**"（接受内核永不会生成的边）。
  注意**不覆盖** DOWNWARD / BREAK_* / PLACE_STEP 等家族：它们的准入**有意**比 `canStandCentered` 宽
  （provider 里写了原因），把它们并进来等于禁用这些移动。

K5-P1 **声明了的状态必须有生产者或明确消费者**：`PathSessionStatus.POSTCONDITION_FAILED` 必须出现在
   `PathSessionStatus` 的映射表里（`RULES.put(..., POSTCONDITION_FAILED)`），否则就是死状态（观测盲区）。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"

FACTORY_PREDICATES = {
    "TraverseExecutionFactory.java": "canTraverse",
    "DiagonalExecutionFactory.java": "canTraverse",
    "AscendExecutionFactory.java": "canAscend",
    "DescendExecutionFactory.java": "canDescend",
}


def method_body(text: str, signature: str) -> str:
    idx = text.find(signature)
    if idx < 0:
        return ""
    line_start = text.rfind("\n", 0, idx) + 1
    indent = len(text[line_start:idx]) - len(text[line_start:idx].lstrip())
    lines = text[idx:].split("\n")
    body = [lines[0]]
    for line in lines[1:]:
        stripped = line.strip()
        if stripped.startswith("}") and (len(line) - len(line.lstrip())) <= indent:
            break
        body.append(line)
    return "\n".join(body[1:])   # 去掉签名行（签名里可能含同名符号）


def rule_k4():
    violations = []
    for file, predicate in FACTORY_PREDICATES.items():
        path = CORE / file
        if not path.exists():
            violations.append(f"{file} 不存在（改名？同步本规则）")
            continue
        body = method_body(path.read_text(encoding="utf-8"), "ValidationResult validate(")
        if not body:
            violations.append(f"{file} 找不到 validate(...)（改名？同步本规则）")
            continue
        if f"MovementHelper.{predicate}(" not in body:
            violations.append(f"{file} 的 validate 没有引用规划侧谓词 MovementHelper.{predicate}()"
                              "（执行可能比内核宽松）")
    return violations


def rule_k5():
    path = CORE / "session" / "PathSessionStatus.java"
    if not path.exists():
        return ["PathSessionStatus.java 不存在（改名？同步本规则）"]
    text = path.read_text(encoding="utf-8")
    if "POSTCONDITION_FAILED" not in text:
        return ["POSTCONDITION_FAILED 已删除 ⇒ 请同时删掉本规则 K5-P1"]
    if not re.search(r"RULES\.put\([^)]*POSTCONDITION_FAILED\s*\)", text):
        return ["POSTCONDITION_FAILED 没有映射表生产者（死状态：永远不会被观测到）"]
    return []


def rule_s8():
    """S8-P1（D-264）：`policyVersion` 已按 S-8 裁定**删除**（恒 0 + 全仓零读者 ⇒ 纯装饰）。

    ⇒ 它不得无声复活：要重新引入，**先得有一个读取者**（并在 `AI_DECISIONS` 里说明它决定什么）。
    """
    hits = []
    for path in sorted((ROOT / "src" / "main" / "java").rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        if "policyVersion" in text:
            rel = path.relative_to(ROOT / "src" / "main" / "java")
            hits.append(f"{rel} 又出现 policyVersion（S-8 已删：先给读取者，再谈重新引入）")
    return hits


def rule_walk_budget():
    """W-P1（D-268④，2026-09-17 用户裁定）：**行走类请求必须有界**。

    现状问题：`PathRequest` 原先把五个 `of(...)` 调用点全传 `SearchBudget.UNLIMITED`
    ⇒ 规划器自己的天花板（`CorePathPlanner.DEFAULT_MAX_NODES/MILLIS`）被绕过，行走可以烧满整个搜索空间。
    裁定 = 有界 + 撞限如实报 `SEARCH_LIMIT`。本规则防止"无声改回无界"。
    """
    path = CORE / "search" / "PathRequest.java"
    if not path.exists():
        return ["PathRequest.java 不存在（改名？同步本规则）"]
    text = path.read_text(encoding="utf-8")
    problems = []
    if "SearchBudget.UNLIMITED" in text:
        problems.append("PathRequest 又出现 SearchBudget.UNLIMITED（行走类必须走 WALK_BUDGET：撞限要报 SEARCH_LIMIT）")
    if "WALK_BUDGET" not in text:
        problems.append("PathRequest 里找不到 WALK_BUDGET（有界预算被移除？）")
    return problems


def rule_progress_signal():
    """NP-P1（survey/17 §4.2(c)，2026-09-17）：**失败计数不得被当成进度**。

    背景：`NO_PROGRESS` 的进度指纹里若整串塞进 Job 的 `progressSummary()`（形如 `mined 3/16 failed=2`），
    那么"**一直在失败**"（`failed` 涨、`mined` 不动 —— 外部 141 小时实测里的"种土豆"失效模式）
    会被当作"有进度"从而**反武装**判据 ⇒ 越失败越显得在动。本规则要求那串先剔除 `failed=`。
    """
    path = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision" / "EventThresholds.java"
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    # ⚠️ 必须断言**调用**本身，不能只搜 `failed=` —— 注释里也写了 `failed=`，
    # 第一版就这么被**顶绿**过一次（2026-09-17 反向对照实测）。
    strip_call = 'replaceAll("\\\\s*failed=\\\\d+"'
    if strip_call not in text:
        return ["EventThresholds 里找不到剔除 `failed=` 的**调用**（进度信号又把失败当进度了？）"]
    return []


def rule_risk_profile():
    """S6-P1（2026-09-17 用户裁定「按 bot 冻结」）：**风险开关的消费者必须读冻结画像**。

    背景：`RiskSwitches` 是进程一份的全局静态（D-046/D-059 的痕迹）⇒ 一个 bot 的任务跑到一半、
    别人改了开关，同一份计划的两段就会用两套风险口径。S-6 的容器是 `RiskProfile`（按 bot 冻结，
    冻结点 = `BotManager.assignTask`）。本规则保证**消费者不会偷偷读回全局开关**。
    """
    targets = {
        CORE / "DescendExecutionFactory.java": "执行侧（DESCEND 过冲）",
        CORE / "search" / "SurfaceMovementProvider.java": "搜索侧（过冲列安全）",
        # D-291（2026-09-17）：容器访问策略的消费点 ⇒ 必须读**冻结画像**，不许直读全局开关
        (ROOT / "src/main/java/com/dddgn/alice/action/MenuSession.java"): "执行侧（容器访问策略）",
        # D-292（2026-09-17）：危险厌恶的消费点 ⇒ 必须读冻结画像
        (ROOT / "src/main/java/com/dddgn/alice/pathing/core/search/MovementContext.java"): "搜索侧（危险邻接加价）",
    }
    problems = []
    for path, label in targets.items():
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        # 泛化（D-291）：**任何** RiskSwitches 的 getter 直读都算违规（不再只盯 descendOvershootGuard 一个）
        for _m in re.finditer(r"RiskSwitches\.([a-zA-Z]+)\(\)", text):
            _getter = _m.group(1)
            if _getter in {"isKnown", "knownSwitches", "describe", "set"}:
                continue
            problems.append(f"{path.name}（{label}）直接读全局开关 RiskSwitches.{_getter}()"
                            f"（S-6：必须读 RiskProfile.of(bot).{_getter}()）")
        if "RiskProfile" not in text:
            problems.append(f"{path.name}（{label}）里找不到 RiskProfile —— 消费者没接冻结画像？")
    return problems


def rule_speech_channel():
    """F4-P1（D-267 地基 F4，2026-09-17 用户裁定「先做地基」）：**说话通道与决策通道双向分离**。

    背景：项目今天没有"不碰世界的输出通道"；最省事的错误写法是把说话文本塞进决策输入
    （`GoalDirector` 的 prompt / `instruct`）⇒ 等于让自然语言**绕过 `GoalAction` 白名单**。
    本规则把"分离"这件事变成机器可查的：两个方向都不许互相引用。
    """
    problems = []

    def code_only(text: str) -> str:
        """剥掉注释再判 —— ⚠️ 否则**注释里提到**被禁符号会造成误红（2026-09-17 实测踩到）。"""
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        return re.sub(r"//[^\n]*", "", text)

    decision = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision"
    speech = decision / "SpeechChannel.java"
    director = decision / "GoalDirector.java"
    if speech.exists():
        text = code_only(speech.read_text(encoding="utf-8"))
        for banned in ("GoalAction", "GoalDirector", "parse("):
            if banned in text:
                problems.append(f"SpeechChannel 引用了 `{banned}` —— 说话通道必须**只出不进**"
                                f"（否则文本可以流进动作解析/决策输入）")
    else:
        problems.append("找不到 SpeechChannel.java（F4 地基被移除？同步本规则）")
    if director.exists():
        if "SpeechChannel" in code_only(director.read_text(encoding="utf-8")):
            problems.append("GoalDirector 引用了 SpeechChannel —— 决策侧不许读说话通道"
                            "（那会让说话内容变成决策输入）")
    return problems


def rule_permission_service():
    """F3-P1（D-267 地基 F3，2026-09-17 用户裁定「先做地基」）：**请示答复入口唯一**。

    背景：`PermissionGate.answer(...)` 今天只有命令/客户端弹窗/夹具三类调用者各调各的 ⇒
    接"非游戏内主体"（桌面 AI）时无处可落。F3 把答复收口到 `PermissionService.answer(transport, ...)`，
    并把 `PermissionGate.answer` 降为**包内可见**（跨包直调编译不过 = 结构性保证）。
    本规则是它的**源码侧**兜底：`decision/` 之外不许出现对 `PermissionGate.answer` 的调用。
    """
    alice = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
    decision = alice / "decision"
    problems = []
    service = decision / "PermissionService.java"
    if not service.exists():
        problems.append("找不到 PermissionService.java（F3 收口被移除？同步本规则）")
    else:
        text = service.read_text(encoding="utf-8")
        for transport in ("TRANSPORT_COMMAND", "TRANSPORT_CLIENT_PACKET", "TRANSPORT_FIXTURE"):
            if transport not in text:
                problems.append(f"PermissionService 缺少 {transport}（transport 标识是归因的基础）")
    for path in alice.rglob("*.java"):
        if decision in path.parents:
            continue
        code = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        if "PermissionGate.answer(" in code:
            problems.append(f"{path.relative_to(alice)} 直接调了 PermissionGate.answer —— "
                            f"必须走 PermissionService.answer(transport, ...)（F3：答复入口唯一）")
    return problems


def rule_death_keeps_data():
    """D1-P1（死亡机制第 1 步，D-276，2026-09-17 用户裁定「不能直接删除数据」）。

    背景：旧行为是死亡 ⇒ 存档被清（`remove(bot)` 里 `BotWorldData.clearBot()`；
    `restoreFromWorld` 对 `Health<=0` 也直接 `clearBot()`）⇒ 死了进度归零。
    第 1 步把两条路都改掉：死亡**先写倒下态**（位置/死因/时刻）再只拆实体；
    恢复路径改用 `restoreDecisionFor`（FALLEN ⇒ 保留数据、不生成实体）。
    本规则防止它被无声改回去。
    """
    path = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot" / "BotManager.java"
    if not path.exists():
        return ["BotManager.java 不存在（同步本规则）"]
    text = path.read_text(encoding="utf-8")
    problems = []
    marker = "public static void onLivingDeath("
    if marker not in text:
        problems.append("找不到 onLivingDeath（改名？同步本规则）")
    else:
        start = text.index(marker)
        end = text.index("\n    }", start)
        body = re.sub(r"//[^\n]*", "", text[start:end])
        if "saveFallenState(" not in body:
            problems.append("死亡路径没有 `saveFallenState(...)` —— 死亡必须先把倒下态写进存档（D-276）")
        if re.search(r"\bremove\(bot\)", body):
            problems.append("死亡路径又调了 `remove(bot)` —— 那会 `clearBot()` 删数据（D-276）")
        if "clearBot(" in body:
            problems.append("死亡路径直接 clearBot() —— 删数据（D-276）")
    if "restoreDecisionFor(" not in text:
        problems.append("找不到 `restoreDecisionFor(...)` —— 恢复路径必须用它判 FALLEN/RESPAWN/DISCARD（D-276）")
    return problems


def rule_damage_observed():
    """S9-P1（S-9 消费，D-277）：**伤害事实必须对决策层可见**。

    背景：Alice 的 `aiStep()` 无条件回血（+1HP/20t）与原版火焰伤害（1HP/20t）**互相抵消**
    ⇒ 靠"采样血量差"读不出"挨了几下、多重"（D-261/D-271 实测）。S-9 的台账来自 `LivingDamageEvent`。
    本规则锁两处：台账存在且提供**窗口查询**；决策快照必须带 `damage` 节点（否则 LLM 依旧看不见挨打）。
    """
    problems = []
    ledger = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "survival" / "DamageLedger.java"
    snapshot = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision" / "DecisionSnapshot.java"
    if not ledger.exists():
        problems.append("找不到 DamageLedger.java（S-9 台账被移除？同步本规则）")
    else:
        text = ledger.read_text(encoding="utf-8")
        if "hitsSince(" not in text:
            problems.append("DamageLedger 没有 `hitsSince(...)` 窗口查询（窗口口径是它的意义）")
    if not snapshot.exists():
        problems.append("找不到 DecisionSnapshot.java")
    else:
        code = re.sub(r"/\*.*?\*/", "", snapshot.read_text(encoding="utf-8"), flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        if "DamageLedger" not in code:
            problems.append("DecisionSnapshot 没有读 DamageLedger —— 伤害事实进不了决策层（S-9 消费未接）")
        elif '"damage"' not in code:
            problems.append("DecisionSnapshot 没有 `\"damage\"` 节点 —— LLM 依旧看不见挨打")
    return problems


def rule_progress_switch():
    """PG-P1（2026-09-17）：**进度事件必须在游戏内可开关**。

    背景：`EventThresholds.setProgressEventInterval(...)` 原先**只有夹具在调** ⇒ 游戏内打不开 `PROGRESS`
    ⇒ `survey/16 §1.3/§12.1` 的"推送频率够不够 / 桌面 AI RTT"**没有观测手段**（本轮核实发现）。
    本规则钉住：命令层必须提供 `/alice progress [<ticks>]` 且真的接到那个 setter 上。
    """
    cmd = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "command" / "BotCommand.java"
    if not cmd.exists():
        return ["找不到 BotCommand.java（同步本规则）"]
    code = re.sub(r"/\*.*?\*/", "", cmd.read_text(encoding="utf-8"), flags=re.S)
    code = re.sub(r"//[^\n]*", "", code)
    problems = []
    if 'Commands.literal("progress")' not in code:
        problems.append("命令层没有 `/alice progress`（游戏内无法开关进度事件 ⇒ 推送频率无从观测）")
    if "setProgressEventInterval(" not in code:
        problems.append("`/alice progress` 没有接到 `EventThresholds.setProgressEventInterval`（开关是空壳）")
    return problems


def rule_no_planning_dependency():
    """S10-P1（2026-09-17 用户裁定「删」）：**`PlanningDependency` 不得复活**。

    事实（裁定依据，可复算）：6 个 `List<BlockPos>` + `worldRevision` **零读取者**
    （`worldRevision()`/6 个列表访问器/`planningDependency()` 全仓 grep 均为 **0**），
    且生产侧填的是"这条移动自己的格子"的**占位拷贝**、`worldRevision` **硬编码 0L**
    ⇒ 它不是"依赖追踪"，只是死管道（与已删的 `LiveExecutionContext.policyVersion` 同类，S-8/D-264）。
    将来若要"世界变了要不要重规划"，**先从消费者设计**，不要靠把字段加回来。
    """
    alice = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
    problems = []
    doomed = alice / "pathing" / "core" / "PlanningDependency.java"
    if doomed.exists():
        problems.append("`PlanningDependency.java` 又出现了 —— 该类已按 S-10 裁定删除（要重启这条线请先改本规则并说明消费者）")
    hits = []
    for path in alice.rglob("*.java"):
        code = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        if "PlanningDependency" in code or "planningDependency" in code:
            hits.append(str(path.relative_to(alice)))
    if hits:
        problems.append("仍有 `PlanningDependency`/`planningDependency` 残留：" + ", ".join(sorted(hits)[:5]))
    return problems


def rule_driver_attribution():
    """F1-P1（2026-09-17 用户裁定「F1 现在补」）：**玩家/物品入口的指派点必须标归因**。

    事实（裁定依据）：`Driver` 有 4 个取值（`in_game_player`/`llm`/`fixture`/`system`），但原先只有 3 处在设它
    （`GoalDirector`=LLM、电池=FIXTURE）⇒ **玩家命令入口**指派的任务在终态日志里一律写成 `driver=system` ✗。
    2026-09-17 已在 73 处指派点前插入归因（`command/`=IN_GAME_PLAYER，`item/`+夹具=FIXTURE）。
    本规则防止**新写的入口**忘记标：每个 `assignXxx(` 调用点之前 8 行内必须出现 `Driver.set`。
    """
    alice = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
    problems = []
    import re as _re
    assign = _re.compile(r"\.assign[A-Z]\w*\(")
    for sub in ("command", "item"):
        for path in sorted((alice / sub).glob("*.java")):
            lines = path.read_text(encoding="utf-8").split("\n")
            for i, line in enumerate(lines):
                if not assign.search(line):
                    continue
                recent = "\n".join(lines[max(0, i - 8):i])
                if "Driver.set" not in recent:
                    problems.append(f"{sub}/{path.name}:{i + 1} 指派点未标归因（前面 8 行没有 Driver.set）")
    return problems


def rule_stop_event_ring():
    """D-338 附注十五 + 附注十六（2026-09-20）：**终止 / 拒绝路径必须进事件环**。

    事实（裁定依据，客户端实测）：`BotSession.immediateStop`（玩家 `/alice region stop`、`/alice stop-task`、
    `stop_current`、延后到安全点）**不走 `complete()`** ⇒ 事件环里什么都不留，决策层下次被叫时
    **看不到"刚才被谁停了"**（用户 stop 后等 30 s 静默无反应，根因之一）；同理两个
    `REJECTED_BEFORE_START`（修路计划非法 / 实体目标未实现）与 `CANCELLED_REPLACED`（被顶替）。

    ⭐ 附注十六补齐的三条**派活/受理被拒**（此前只有一行 warn、甚至**完全静默** ⇒ 决策层以为派活成功了）：
    `replaceTaskIfRunning` 的「未结清传输」与「在飞传输」、`assignJob` 的「kind 契约不全」。

    ⚠️ 断言按**每个站点各自的函数体**做（结构断言）。旧版是全局 `count(REFUSED) >= 2` ——
    在别处**新增**一条 `REFUSED` 就能掩盖被删掉的那条（假绿）。删掉任何一处调用 ⇒ 门禁红。
    """
    path = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot" / "BotManager.java"
    text = path.read_text(encoding="utf-8")
    problems = []

    def body_of(signature):
        body = method_body(text, signature)
        if not body:
            problems.append("BotManager.java 找不到 `%s`（结构变了 ⇒ 本规则要跟着改）" % signature)
        return body

    sites = [
        ("String immediateStop(String reason, boolean forced) {",
         'BotEventLog.record(bot, "STOP"', "显式停止"),
        ("private boolean replaceTaskIfRunning() {",
         'BotEventLog.record(bot, "REPLACED"', "被新任务顶替"),
        ("private boolean replaceTaskIfRunning() {",
         "code=transfer_unsettled", "派活被拒：有未结清传输"),
        ("private boolean replaceTaskIfRunning() {",
         "code=transfer_in_transit", "派活被拒：在飞传输未结清"),
        ("public void assignRoadBuild(com.dddgn.alice.road.RoadPlan plan) {",
         'BotEventLog.record(bot, "REFUSED"', "启动前拒绝：修路计划非法"),
        ("public void assign(TaskTarget newTarget) {",
         'BotEventLog.record(bot, "REFUSED"', "启动前拒绝：实体目标未实现"),
        ("public static boolean assignJob(",
         'BotEventLog.record(bot, "REFUSED"', "受理侧拒绝：kind 缺世界事实对账契约"),
    ]
    for signature, needle, label in sites:
        body = body_of(signature)
        if body and needle not in body:
            problems.append("`%s` 没有写进事件环（`%s` 体内找不到 `%s`）" % (label, signature, needle))
    return problems


def rule_no_permitted_candidate():
    """D-341（2026-09-19 用户裁定 P2）：**「无权」≠「没有」**。

    事实（裁定依据，客户端实测）：区域作业的候选扫描把**没权限的树**丢进 `rejected`、`viable` 里根本没有
    它 ⇒ 作业的世界模型变成「区域里没有树」⇒ 走**待机巡查等生长**（那是为树苗生长设计的正常机制）。
    2026-09-19 19:06 客户端：LLM 自起的 `region_lumber` 在保护区内被封顶 `L1`、5 棵树全
    `zone_break_not_allowed` ⇒ `viable=0 inRegion=0` + `欠树 deficit=5`，**空转到 `maxTicks=24000`
    （20 分钟）**，期间反复唤醒 LLM。用户口径：「任务要如实失败，不能继续跑」。

    本规则断言：① 分类的**唯一出处** `ZoneAuthority.permanentDenial` 在、且**永久码齐**；
    ② 它**没有**把搜索性理由（`trunk_too_tall`）收进去（否则「该等」会被误判成「失败」）；
    ③ `RegionLumberJob.patrol()` **真的调用**它、并给出 `no_permitted_candidate` 终态码。
    删掉任何一处 ⇒ 门禁红。
    """
    zone = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "protection" / "ZoneAuthority.java"
    region = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "lumber" / "RegionLumberJob.java"
    problems = []
    ztext = zone.read_text(encoding="utf-8")
    start = ztext.find("public static boolean permanentDenial(String code) {")
    if start < 0:
        problems.append("ZoneAuthority 找不到 `permanentDenial`（分类的唯一出处没了 ⇒ 本规则要跟着改）")
    else:
        body = ztext[start:ztext.find("\n    }", start)]
        # ⚠️ 必须**先剥注释**：方法体里那句注释就举了 `trunk_too_tall(unreachable=…)` 当例子
        # （2026-09-19 实测：不剥注释 ⇒ 本规则假红，报"收了搜索性理由"）
        body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
        body = re.sub(r"//[^\n]*", "", body)
        for code in ('"zone_break_not_allowed"', '"protected_area"', '"protected_safe_zone"',
                     '"zone_read_only"', '"zone_place_not_scaffold"'):
            if code not in body:
                problems.append("`permanentDenial` 丢了永久码 " + code + "（权限类拒绝必须算永久）")
        if "trunk_too_tall" in body:
            problems.append("`permanentDenial` 里出现了 `trunk_too_tall` —— 那是**搜索性**理由，"
                            "不是权限 ⇒ 会把『该等生长』误判成『失败』")
    rtext = region.read_text(encoding="utf-8")
    if "public static String permissionBlock(" not in rtext:
        problems.append("`RegionLumberJob.permissionBlock` 没了（夹具 ⑫ 直接断言它）")
    start = rtext.find("Status patrol() {")
    if start < 0:
        problems.append("RegionLumberJob 找不到 `patrol()`（结构变了 ⇒ 本规则要跟着改）")
    else:
        body = rtext[start:rtext.find("\n    }", start)]
        # ⚠️ **结构断言，不是"文本在不在"**（2026-09-19 实测教训）：第一版只查 `permissionBlock(` 是否出现
        # ⇒ 反向对照把条件注入成 `if (inRegion.isEmpty() && false)` 时**规则照样 PASS**（调用文本还在，
        # 只是永远不执行）⇒ 门禁形同虚设。改成断言"那个分支是**无条件**的、且调用是它第一条语句"。
        expected = ("if (inRegion.isEmpty()) {\n"
                    "            String blocked = permissionBlock(region, raw.rejected(), effectiveTop);")
        if expected not in body:
            problems.append("`patrol()` 的『区域内没有可用树』分支没有**无条件**走 `permissionBlock(...)`"
                            "（被删掉 / 挪走 / 加了条件都会命中这里）⇒ 永久拒绝又会被当成"
                            "『区域里没有树』去待机巡查（= 那个 20 分钟空转）")
        if '"no_permitted_candidate"' not in body:
            problems.append("`patrol()` 没有 `no_permitted_candidate` 终态码 ⇒ 不会如实失败")
    return problems


def rule_loop_admission():
    """D-342（2026-09-19 用户裁定 ③）：**跨任务循环检测的受理闸必须真的接在 `start_job` 上**。

    事实（裁定依据）：客户端三次实测（16:53 / 17:30 / 19:06）同一形态 —— 一个目标失败后，决策层
    **换条路重试**（一次性被拒 ⇒ 自起常驻区域任务）。用户口径：「任务要如实失败，不能继续跑」。

    ⚠️ **本规则是"结构断言"，不是"文本在不在"**（`D-341` 的教训：只查文本会被 `&& false` 绕过）：
    断言 `execute` 的 `start_job` 分支里，`assignJob` **之前**必须有一次 `loopRefusal(...)` 判定
    与 `if (loop != null) {` 的拒绝分支。删掉受理闸 / 把它挪到 `assignJob` 之后 / 给它加条件 ⇒ 红。
    """
    path = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision" / "GoalDirector.java"
    text = path.read_text(encoding="utf-8")
    problems = []
    start = text.find("private static void execute(BotPlayer bot, State state, GoalAction action, String trigger) {")
    if start < 0:
        problems.append("GoalDirector 找不到 `execute(...)`（结构变了 ⇒ 本规则要跟着改）")
        return problems
    body = text[start:text.find("\n    }", start)]
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"//[^\n]*", "", body)
    call = "loopRefusal(bot, Driver.of(bot)"
    guard = "if (loop != null) {"
    assign = "BotManager.assignJob(bot, state.observer, start.request(), false)"
    if call not in body:
        problems.append("`execute` 的 start_job 分支没有调用 `loopRefusal(...)` ⇒ 同一目标反复失败可以无限重起")
    if guard not in body:
        problems.append("`execute` 没有 `if (loop != null) {` 的**拒绝分支** ⇒ 判定结果被丢掉（等于没接）")
    if call in body and assign in body and body.find(call) > body.find(assign):
        problems.append("`loopRefusal(...)` 出现在 `assignJob(...)` **之后** ⇒ 任务已经起了才判，闸门形同虚设")
    if "noteAttempt(bot" not in body:
        problems.append("`execute` 起任务成功后没有 `noteAttempt(...)` ⇒ 终态无法归到身份上（记账永远为空）")
    # ⚠️ 2026-09-19 客户端实测的缺陷 + 用户裁定「这类验证本该无头」之后的**结构修订**：
    # 终态归因**不许**再跨边界做字符串匹配（原先拿受理侧 kind 与终态侧 kind 对齐，而两者写法不同
    # ⇒ 静默不记账、闸门永不触发）；改为「在飞身份只由 LLM 受理侧写、别人派活即 `clearAttempt`」。
    # ⚠️ 必须先剥注释再搜：`attemptKey` 的 javadoc 里**引用了**那句被删掉的表达式当反例
    # （2026-09-19 实测：不剥注释 ⇒ 本规则假红，报"又出现字符串匹配"）
    code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    code = re.sub(r"//[^\n]*", "", code)
    if "startsWith(kind" in code:
        problems.append("`GoalDirector` 又出现了按 `kind` 做字符串匹配（`startsWith(kind`）⇒ 受理侧与终态侧"
                        "写法不同（`JobRequest.Kind.name()` 大写 / `Task.taskName()` 小写）就会静默不记账")
    if "public static void clearAttempt(BotPlayer bot) {" not in text:
        problems.append("`GoalDirector.clearAttempt` 没了（别人派活时清在飞身份的入口）")
    bm = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot" / "BotManager.java").read_text(encoding="utf-8")
    if "GoalDirector.clearAttempt(bot)" not in bm:
        problems.append("`BotManager.beginTask` 没有在『不是 LLM 派活』时调用 `GoalDirector.clearAttempt(bot)`"
                        "⇒ 玩家/夹具派的活会被记到 LLM 的账上")
    if "GoalDirector.noteTerminalOutcome(bot," not in bm:
        problems.append("`BotManager.complete` 没有调用 `noteTerminalOutcome(...)` ⇒ 循环闸永远收不到失败")
    else:
        i_note = bm.find("GoalDirector.noteTerminalOutcome(bot,")
        i_ret = bm.find("startSafeReturnIfNeeded()")
        if 0 <= i_ret < i_note:
            problems.append("`noteTerminalOutcome(...)` 出现在返程兜底**之后** ⇒ 漏掉「失败触发返程」那一次")
    # ⚠️ 受理侧"玩家豁免"的前提：应用模型回复时**必须先把 Driver 标成 LLM**
    # （否则 `Driver.of(bot)` 还是上一个任务的驱动者，可能是 `fixture` ⇒ 闸门被豁免吃掉）。
    i_drv = code.find("Driver.set(bot, Driver.LLM)")
    i_exec = code.find("execute(bot, state, action")
    if i_drv < 0 or i_exec < 0 or i_drv > i_exec:
        problems.append("应用模型回复时 `Driver.set(bot, Driver.LLM)` 不在 `execute(...)` **之前**"
                        "⇒ 受理闸读到的可能是上一个任务的驱动者（如 `fixture`）⇒ 被玩家豁免吃掉")
    if "noteAttempt(bot, start.request().kind().name()" not in text \
            or "loopRefusal(bot, Driver.of(bot), start.request().kind().name()" not in text:
        problems.append("受理侧两处（`loopRefusal` 与 `noteAttempt`）没有用**同一个表达式**喂身份 ⇒ 身份会分叉")
    return problems


def rule_bulk_write_zone_gate():
    """D-343（2026-09-19 用户裁定）：**道路施工那条批量写入链不许绕过区域授权面**，
    且 `RoadObstaclePolicy` 的"裸判据"必须**继续是**规划期规避（不许被顺手接上阶梯）。

    事实（裁定依据，代码级查证）：`RoadObstaclePolicy.exactForbidden` 读**裸** `protectionReason`，
    台账曾把它记成"未接阶梯 = 欠账"。实测**不是**：它只是**规划期让路线绕开保护格**（收紧，更保守），
    真正的写入闸门在动作层且已接好 ——
    `RoadBuilder.buildUnit` → `BlockInteraction.placeBulkEdit`（`setBlock` 前调 `regionRefusal(PLACE)`）/
    `breakForBulkEdit`（`breakRefusal` → `BlockBreakSafety.refusal` → 同一个 `regionRefusal`）。
    ⇒ 两边结论一致（保护区里都拒）。把它"接上阶梯"的方向是**放松**，还会造成"规划通过、逐块写入被拒"
    的**半成品路** ⇒ 故**零行为改动**；本规则把该结论变成**可失败**的约束（散文注释不失败 = 拦不住回归）。

    断言（任一被破坏 ⇒ 构建红）：
    ① `placeBulkEdit`：区域授权判定在 `level.setBlock(` **之前**；
    ② `breakForBulkEdit`：`breakRefusal(...)` 在 `level.destroyBlock(` **之前**，且
    ③ `BlockInteraction.breakRefusal` → `BlockBreakSafety.refusal(` 这一跳还在，
    ④ `BlockBreakSafety.refusal` → `clearingRefusal(`/`explicitTargetRefusal(` 这一跳还在，
    ⑤ `BlockBreakSafety.explicitTargetRefusal(..., WriteReason ...)` 真的调 `ZoneAuthority.regionRefusal(`；
    ⑥ ⭐ `RoadObstaclePolicy.exactForbidden` **不许**出现 `ZoneAuthority`
       （要放松规划期规避 ⇒ 先改本规则 + `D-343`，不许顺手改）。
    """
    alice = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
    bi = alice / "action" / "BlockInteraction.java"
    bbs = alice / "protection" / "BlockBreakSafety.java"
    rop = alice / "road" / "RoadObstaclePolicy.java"
    problems = []
    for path in (bi, bbs, rop):
        if not path.exists():
            problems.append(f"{path.name} 不存在（改名/移动？同步本规则）")
    if problems:
        return problems

    bi_text = bi.read_text(encoding="utf-8")
    bbs_text = bbs.read_text(encoding="utf-8")

    def gate_before_write(text, signature, gate, write, label):
        body = method_body(text, signature)
        if not body:
            problems.append(f"找不到 {label}（结构变了 ⇒ 本规则要跟着改）")
            return
        i_gate, i_write = body.find(gate), body.find(write)
        if i_gate < 0:
            problems.append(f"{label} 里没有 `{gate}` ⇒ **批量写入绕过了区域授权面**"
                            "（RoadBuilder 就是走这条路进保护区的）")
        elif i_write < 0:
            problems.append(f"{label} 里找不到 `{write}`（改名？同步本规则）")
        elif i_gate > i_write:
            problems.append(f"{label} 的 `{gate}` 在 `{write}` **之后** ⇒ 判定晚于写入 = 等于没有闸门")

    gate_before_write(bi_text, "public static boolean placeBulkEdit(",
                      "ZoneAuthority.regionRefusal(", "level.setBlock(", "`placeBulkEdit`")
    gate_before_write(bi_text, "public static boolean breakForBulkEdit(",
                      "breakRefusal(bot, level, pos, grant)", "level.destroyBlock(", "`breakForBulkEdit`")

    hop = method_body(bi_text, "public static String breakRefusal(")
    if not hop:
        problems.append("找不到 `BlockInteraction.breakRefusal`（断开链 ⇒ 本规则要跟着改）")
    elif "BlockBreakSafety.refusal(" not in hop:
        problems.append("`BlockInteraction.breakRefusal` 不再走 `BlockBreakSafety.refusal(` ⇒ 破坏闸门链断了")

    hop2 = method_body(bbs_text, "public static String refusal(")
    if not hop2:
        problems.append("找不到 `BlockBreakSafety.refusal`（断开链 ⇒ 本规则要跟着改）")
    elif not ("clearingRefusal(" in hop2 or "explicitTargetRefusal(" in hop2):
        problems.append("`BlockBreakSafety.refusal` 不再走 `clearingRefusal`/`explicitTargetRefusal` ⇒ 链断了")

    hop3 = method_body(bbs_text, "public static String explicitTargetRefusal(ServerPlayer bot, BlockPos target, WriteReason reason)")
    if not hop3:
        problems.append("找不到**带写入理由**的 `explicitTargetRefusal`（没了它，保护区那层无法按区域授权面判定）")
    elif "ZoneAuthority.regionRefusal(" not in hop3:
        problems.append("`explicitTargetRefusal(..., WriteReason ...)` 不再调 `ZoneAuthority.regionRefusal(` "
                        "⇒ 破坏路径绕过区域授权面")

    # ⑥ 反向：**故意不接阶梯**。少了这条，"顺手接上"就会静默改变 ② 的结论。
    rop_code = re.sub(r"/\*.*?\*/", "", rop.read_text(encoding="utf-8"), flags=re.S)
    rop_code = re.sub(r"//[^\n]*", "", rop_code)
    if "ZoneAuthority" in rop_code:
        problems.append("`RoadObstaclePolicy` 里出现了 `ZoneAuthority` ⇒ 规划期规避被接上权限阶梯"
                        "（方向是**放松**，会造出半成品路）。要改先改本规则 + `D-343`")
    return problems


def rule_replant_sweep_bounded():
    """D-344（2026-09-19 用户逐项裁定）：**区域补种的"扫地面"阶段必须互斥且有界**。

    事实（裁定依据）：用户细则（台账 §5.12 第 13 项）要求"窗口内主动扫区域内地上的东西、
    **一次不设上限、扫到捡完为止**"，同时④「**补种与扫描不许撞车**」（串行互斥）；
    而 `D-341`/`D-342` 的教训是**"捡不到"必须如实失败，不许空转** ⇒ ③ 裁定 `N = 3`
    + 新终态码 `sweep_no_progress`。①裁定 = 只在扫地面期间放宽拾取（`SESSION` 短 TTL + 用完即撤）。

    本规则把上面几条变成**可失败的结构断言**（散文注释不失败 ⇒ 拦不住回归）：
    ① `sweepDecision` 四态的**顺序 = 优先级**（改顺序 = 改行为）⇒ 逐个按位置断言；
    ② `sweep()` 必须真的用 `SWEEP_NO_PROGRESS_LIMIT` 判"零进展"并给 `sweep_no_progress` 终态；
    ③ 互斥：`tick()` 里 `current` 与 `sweepTask` 两个守卫都在，且 `sweepTask = new` 只出现在
       `startSweep`、`current = new` 只出现在 `patrol()`（同处赋值 ⇒ 两者可能同时非空）；
    ④ ①的授权：`startSweep` 里 `CollectGrants.add(... SESSION ...)` + 结束时 `dropSweepGrant()`，
       且 `finish()`（失败/收工路径）**也要**撤 ⇒ 权限不留在世上；
    ⑤ 预算**随落物数缩放**（`suggestedSweepTicks`），不是人为封顶（细则③「一次不设上限」）。

    ⭐ **`D-350` 修订**（2026-09-20 用户裁定「让区域任务显式收集自己的掉落物」）：
    ① 的**顺序变了**（`HAS_SAPLINGS → ENTER → NOTHING_TO_SWEEP → NO_DEFICIT`）：**不再要求欠树才扫**；
    但**顺序仍然是优先级**、仍然逐位置断言 ⇒ 再改照样红。新增 ⑥：`sweepRequired` +
    `SWEEP_OPTIONAL_BACKOFF_TICKS` + 纯函数 `sweepEntryAllowed` 必须在位
    （**非必需扫描零收获 ⇒ 退避而非失败** —— 否则"地上有一件捡不到的"会把整个区域任务判死）。
    """
    path = ROOT / "src/main/java/com/dddgn/alice/job/lumber/RegionLumberJob.java"
    if not path.exists():
        return ["RegionLumberJob.java 不存在（改名？同步本规则）"]
    text = path.read_text(encoding="utf-8")
    # ⚠️ 先剥注释：规则会引用被注释掉的示例/反例（`D-341`/`D-343` 各吃过一次假红）
    code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    code = re.sub(r"//[^\n]*", "", code)
    problems = []

    # ① 四态的**顺序**（= 优先级）
    body = method_body(code, "public static SweepDecision sweepDecision(")
    if not body:
        problems.append("找不到 `sweepDecision`（纯判据没了 ⇒ 夹具也没法零副作用地断言它）")
    else:
        # ⭐ `D-350`（2026-09-20 用户裁定「让区域任务显式收集自己的掉落物」）**故意改了优先级**：
        # 旧序 = NO_DEFICIT → HAS_SAPLINGS → NOTHING_TO_SWEEP → ENTER（"不欠树就不扫"）；
        # 新序 = HAS_SAPLINGS → ENTER → NOTHING_TO_SWEEP → NO_DEFICIT
        # （现场：树苗已选定、`deficit=0` ⇒ 从不进扫描 ⇒ 自己砍出来的树苗留在地上被被动闸门挡 ⇒ 后来真欠树时
        #   `tool_missing` 中止）。**顺序仍然 = 优先级**，仍然按位置断言 ⇒ 再改顺序照样红。
        marks = ["SweepDecision.HAS_SAPLINGS", "SweepDecision.ENTER",
                 "SweepDecision.NOTHING_TO_SWEEP", "SweepDecision.NO_DEFICIT"]
        idx = [body.find(m) for m in marks]
        if any(i < 0 for i in idx):
            problems.append("`sweepDecision` 少了某一态：" + ", ".join(
                m for m, i in zip(marks, idx) if i < 0) + "（四态缺一 = 判定表不完整）")
        elif idx != sorted(idx):
            problems.append("`sweepDecision` 的四态**顺序变了**（顺序 = 优先级 ⇒ 改顺序就是改行为）："
                            "现在顺序 = " + " → ".join(
                                m.rsplit(".", 1)[1] for _, m in sorted(zip(idx, marks))))
        # ⭐ `D-350`：新表的三个条件（缺一即红 —— 判定表不许被悄悄改窄/改宽）
        for cond in ("saplingInInventory > 0", "listDropsInRegion > 0", "deficit > 0"):
            if cond not in body:
                problems.append(f"`sweepDecision` 少了条件 `{cond}`（判定表被改窄/改宽）")

        # ⭐ `D-350` 的新护栏也必须**结构上**在位（否则"退避"会被悄悄删掉 ⇒ 每轮空转 / 或任务被判死）
        for cond in ("sweepEntryAllowed", "sweepOptionalBackoffUntil"):
            if cond not in code:
                problems.append(f"`D-350` 的护栏 `{cond}` 不见了 ⇒ 非必需扫描会每轮空转"
                                "（或「地上有一件捡不到的」会把任务判死）")

    # ② 零进展上限 + 如实终态
    sweep_body = method_body(code, "private com.dddgn.alice.task.Task.Status sweep()")
    if not sweep_body:
        problems.append("找不到 `sweep()`（扫描阶段没了？）")
    else:
        if "SWEEP_NO_PROGRESS_LIMIT" not in sweep_body:
            problems.append("`sweep()` 没有用 `SWEEP_NO_PROGRESS_LIMIT` 判零进展 ⇒ "
                            "「捡不到」会变成每轮重扫（**新的空转**，D-341/D-342 同一族）")
        if '"sweep_no_progress"' not in sweep_body:
            problems.append("`sweep()` 没有 `sweep_no_progress` 终态码 ⇒ 不会如实失败")
        if "finish(" not in sweep_body:
            problems.append("`sweep()` 判了零进展却没有 `finish(...)` ⇒ 只是记了个数、没有收工")
        if "sweepTask = null" not in sweep_body:
            problems.append("`sweep()` 没有把 `sweepTask` 置空 ⇒ **相位不前进**"
                            "（技能「夹具相位状态机」：会每 tick 重复同一分支）")

    # ③ 互斥（状态机结构）
    tick_body = method_body(code, "public com.dddgn.alice.task.Task.Status tick()")
    if not tick_body:
        problems.append("找不到 `tick()`（结构变了 ⇒ 同步本规则）")
    else:
        i_cur = tick_body.find("current != null")
        i_sweep = tick_body.find("sweepTask != null")
        i_patrol = tick_body.find("patrol()")
        if i_cur < 0 or i_sweep < 0:
            problems.append("`tick()` 少了 `current != null` 或 `sweepTask != null` 的守卫 ⇒ "
                            "扫描与砍树可能**同时**在跑（细则④「不许撞车」）")
        elif i_cur > i_sweep:
            problems.append("`tick()` 里 `sweepTask` 守卫排在 `current` **之前** ⇒ 顺序与设计相反")
        if i_patrol >= 0 and (i_cur > i_patrol or i_sweep > i_patrol):
            problems.append("`tick()` 里两个守卫必须在 `patrol()` **之前**（否则派活时不看谁在跑）")
    n_new_sweep = code.count("sweepTask = new ")
    if n_new_sweep != 1:
        problems.append(f"`sweepTask = new` 出现 {n_new_sweep} 次（应恰好 1 次，且只在 `startSweep`）"
                        "⇒ 多处赋值会让互斥不再由结构保证")
    start_body = method_body(code, "private com.dddgn.alice.task.Task.Status startSweep(")
    if not start_body:
        problems.append("找不到 `startSweep(...)`（扫描的派活入口没了）")
    else:
        if "sweepTask = new " not in start_body:
            problems.append("`startSweep` 里没有创建 `sweepTask`（那在哪里创建？互斥前提被破坏）")
        # ④①的授权
        if "CollectGrants.add(" not in start_body or "Scope.SESSION" not in start_body:
            problems.append("`startSweep` 没有签发 `SESSION` 收集授权 ⇒ 区内 `FOREIGN` 落物"
                            "**捡不起来**（①裁定失效；`CollectGrant` 是唯一正规出口）")
        # ⑤ 预算随落物数缩放
        if "suggestedSweepTicks(" not in start_body:
            problems.append("`startSweep` 没用 `CollectDropsTask.suggestedSweepTicks(` ⇒ "
                            "扫描预算变成了人为拍的数（细则③「一次不设上限」要用**按落物数缩放**的口径）")

    # ④ 撤销授权：正常结束 + 失败/收工路径都要撤
    # ⑦ `D-344` ②：**退避豁免**（有活的那一轮不吃退避）+ 与 ③ 的配套
    if tick_body and "workedThisPatrol ? patrolIntervalTicks : currentPatrolInterval" not in tick_body:
        problems.append("`tick()` 的巡查冷却没有按 `workedThisPatrol` 分流 ⇒ ②裁定失效："
                        "「砍完立刻补」最坏仍要等 600 tick（≈30 s）退避")
    n_worked = code.count("workedThisPatrol = true")
    if n_worked < 4:
        problems.append(f"`workedThisPatrol = true` 只有 {n_worked} 处（应 ≥4：挑树 / 起扫描 / 走回去补 / "
                        "真种下去）⇒ 有的\u300c干活\u300d分支没被算成有活，豁免就不完整")
    patrol_body = method_body(code, "private com.dddgn.alice.task.Task.Status patrol()")
    if not patrol_body:
        problems.append("找不到 `patrol()`（结构变了 ⇒ 同步本规则）")
    elif "workedThisPatrol = false" not in patrol_body[:300]:
        problems.append("`patrol()` 开头没有把 `workedThisPatrol` **复位** ⇒ 一旦为真就永久为真 "
                        "⇒ **退避永久失效**（高频扫描，正是 §13.1 禁止的）")

    if "dropSweepGrant()" not in sweep_body:
        problems.append("`sweep()` 结束时没有 `dropSweepGrant()` ⇒ 扫描的放宽权限会多活一个 TTL")
    finish_body = method_body(code, "private com.dddgn.alice.task.Task.Status finish(")
    if not finish_body:
        problems.append("找不到 `finish(...)`（结构变了 ⇒ 同步本规则）")
    elif "dropSweepGrant()" not in finish_body:
        problems.append("`finish(...)` 没有 `dropSweepGrant()` ⇒ **失败路径把放宽的拾取权限留在世上**"
                        "（技能 §6.9.2：失败必清理）")
    return problems


def rule_no_until_full():
    """J5-P1（2026-09-17 用户裁定「删」）：**`GoalSpec.Kind.UNTIL_FULL` 不得复活**。

    事实（裁定依据）：该常量全仓**只有声明、没有任何调用方**（grep 仅命中 `GoalSpec` 自身）⇒
    与 S-8（`policyVersion`）、S-10（`PlanningDependency`）同类：**声明了没人用** ⇒ 删。
    将来要做「采集到背包满」：**从消费者设计**（谁请求、判据是什么、怎么断言），不要先把常量加回来。
    """
    alice = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
    problems = []
    for path in alice.rglob("*.java"):
        code = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
        if "UNTIL_FULL" in code:
            problems.append(f"`UNTIL_FULL` 又出现了：{path.relative_to(alice)}（已按 D-290 删除；要重启请先改本规则并说明消费者）")
        if "stopWhenFull" in code:
            problems.append(f"`stopWhenFull` 又出现了：{path.relative_to(alice)}（已按 D-291 删除：全仓只出现它自己的声明 ✗；要重启请先给消费者）")
    return problems


def rule_harness_step_hygiene():
    """R2-P1（2026-09-17 实测缺陷，R-2 迁移 `craft` 时抓到）：**编排器的步边界必须与电池 `endStep` 同口径**。

    事实（怎么抓到的）：把 12 步合成链搬进 `CraftModule` 后第一次单跑 `module:craft` ⇒ **11/12**，
    `craft_goal` 红。根因**不是合成**，而是**站点选择跨步泄漏**：
    `craft_cooking` 的 provision 把 `CraftStation` 选择设成 `upgradetab`，编排器没还原 ⇒
    下一步 `CraftJob` 走升级页签路线 ⇒ `[CraftJob] 失败 code=upgrade_item_absent … station=upgradetab`。
    旧电池 `endStep` 里本来就有这一句（注释原话：「**站点选择不跨步泄漏**：谁设的谁收」）⇒
    **编排器缺它 = "行为等价"是假的**（CORE 曾经全绿，只是因为电池那边有那句话 ✓）。

    本规则钉住**两侧都要有**：电池 `endStep` 与编排器 `endStepHygiene` 各出现一次
    `CraftStation.select(bot, "auto")`。少任何一侧 ⇒ 构建红（这一条的价值就在于"哪一侧被删掉都当场知道"）。
    """
    targets = {
        ROOT / "src/main/java/com/dddgn/alice/task/RegressionBatteryTask.java": "电池 endStep",
        ROOT / "src/main/java/com/dddgn/alice/task/check/CheckHarness.java": "编排器 endStepHygiene",
    }
    needle = 'CraftStation.select(bot, "auto")'
    problems = []
    for path, label in targets.items():
        if not path.exists():
            problems.append(f"{path.name} 不存在（{label} —— 改名？同步本规则）")
            continue
        text = re.sub(r"//[^\n]*", "", path.read_text(encoding="utf-8"))
        if needle not in text:
            problems.append(f"{label}（{path.name}）里找不到 `{needle}`"
                            "（跨步全局态没还原 ⇒ 下一步的行为会取决于上一步设了什么）")
    return problems


def rule_module_step_inventory():
    """R2-P2（2026-09-17，R-2 迁移期）：**每个自检步必须恰好有一个提供者**（电池内联 or 某个模块）。

    为什么需要（迁移期的真实风险）：把步从电池搬进模块时，**删了旧的、忘了加新的**（或反过来）
    只有等那一天真的跑到那一步才会暴露；而"某步悄悄消失"在 SUMMARY 里看不出来（它只是不在列表里 ✗）。
    判据 = 静态清点三方：① `CURATION` 的步名（它构造期会与实际步表一一自校验 ✓）②电池内联的
    `step("X")`/`stepSkippable("X")`/`stepKeeping("X")`/`new Step("X"` ③各模块里的
    `CheckStep.of("X")`/`CheckStep.skippable("X")`/`CheckStep.keeping("X")` ⇒ 三者必须**逐一对应**。
    """
    import re as _re
    battery = (ROOT / "src/main/java/com/dddgn/alice/task/RegressionBatteryTask.java")
    modules_dir = (ROOT / "src/main/java/com/dddgn/alice/task/check/modules")
    text = battery.read_text(encoding="utf-8")
    start = text.index("private static final Map<String, Profile> CURATION")
    # 归属表可能以**单独一行的 `);`** 结束，也可能最后一行就是 `Map.entry(...));`
    # （照抄 `tools/step-names.py` 的稳健写法：找不到就吃到文件尾 ✓）
    end = text.index("\n    );", start) if "\n    );" in text[start:] else len(text)
    curated = set(_re.findall(r'Map\.entry\("([A-Za-z0-9_]+)",\s*Profile\.', text[start:end]))
    if not curated:
        return ["没能从 CURATION 解析出步名（规则需同步）"]

    inline = set(_re.findall(r'steps\.add\(\s*(?:new Step|step|stepSkippable|stepKeeping)\(\s*"([A-Za-z0-9_]+)"', text))
    provided: dict[str, list[str]] = {}
    for name in inline:
        provided.setdefault(name, []).append("RegressionBatteryTask")
    for path in sorted(modules_dir.glob("*Module.java")):
        body = path.read_text(encoding="utf-8")
        names = _re.findall(r'CheckStep\.(?:of|skippable|keeping)\(\s*"([A-Za-z0-9_]+)"', body)
        names += _re.findall(r'new CheckStep\(\s*"([A-Za-z0-9_]+)"', body)
        # **例外（正当的）**：**电池没有组合进来的模块**（如 `harness_self` —— 它是"编排器自检"，
        # 故意不进电池、只走 `module:harness_self`）⇒ 它的步本来就不该在 CURATION 里 ✓。
        # 判据用**唯一出处**：电池里出现 `new XxxModule().steps(` 才算"被电池组合" ✓。
        # 电池里用的是**全限定名**（new com.dddgn...modules.XxxModule().steps(...)）
        # ⇒ 必须用正则容忍前缀（第一版写成字面量匹配 ⇒ 全部模块被判成「未组合」⇒ 38 条假违规）
        if not _re.search(rf'new\s+[\w.]*{path.stem}\(\)\.steps\(', text):
            continue
        for name in names:
            provided.setdefault(name, []).append(path.name)

    problems = []
    for name in sorted(curated - set(provided)):
        problems.append(f"步 `{name}` 在 CURATION 里，但**没有任何提供者**（搬迁时搬丢了？）")
    for name in sorted(set(provided) - curated):
        problems.append(f"步 `{name}` 有提供者 {provided[name]}，但**不在 CURATION 里**（漏登记档位？）")
    for name in sorted(set(provided) & curated):
        if len(provided[name]) > 1:
            problems.append(f"步 `{name}` 被**提供了 {len(provided[name])} 次**：{provided[name]}"
                            "（搬迁时「新的加了、旧的没删」⇒ 会跑两遍）")
    return problems


def rule_step_boundary_parity():
    """R2-P3（2026-09-17，把 D-298 的 R2-P1 泛化）：**电池 `endStep` 里的跨步全局态还原，
    编排器 `endStepHygiene` 必须都有**。

    今天实测的坑（D-298）：电池 `endStep` 有一句 `CraftStation.select(bot, "auto")`（"站点选择不跨步泄漏"），
    而编排器没有 ⇒ 模块单跑时 `craft_goal` 被上一步泄漏的站点选择改道，假红 ✗。
    R2-P1 当初只钉了 `CraftStation` 这一个符号；本规则把它泛化：凡电池 `endStep` 里形如
    `Xxx.select/clear/reset(bot…)` 的**跨步重置**，编排器 `endStepHygiene` 里都要有同一句 ✓
    （账本/写入预算的 `close*` 不在此列 —— 那些由会话任务生命周期负责 ✓）。
    """
    import re as _re
    battery = ROOT / "src/main/java/com/dddgn/alice/task/RegressionBatteryTask.java"
    harness = ROOT / "src/main/java/com/dddgn/alice/task/check/CheckHarness.java"
    if not battery.exists() or not harness.exists():
        return ["电池或编排器文件不存在（改名？同步本规则）"]

    def body_of(text: str, signature: str) -> str:
        idx = text.find(signature)
        if idx < 0:
            return ""
        line_start = text.rfind("\n", 0, idx) + 1
        indent = len(text[line_start:idx]) - len(text[line_start:idx].lstrip())
        out = []
        for line in text[idx:].split("\n")[1:]:
            if line.strip().startswith("}") and (len(line) - len(line.lstrip())) <= indent:
                break
            out.append(line)
        return "\n".join(out)

    b_body = body_of(_re.sub(r"//[^\n]*", "", battery.read_text(encoding="utf-8")), "private void endStep()")
    h_body = body_of(_re.sub(r"//[^\n]*", "", harness.read_text(encoding="utf-8")),
                     "private void endStepHygiene()")
    if not b_body or not h_body:
        return ["找不到电池 `endStep()` 或编排器 `endStepHygiene()`（改名？同步本规则）"]
    pattern = _re.compile(r'([A-Za-z_][\w.]*)\.(?:select|clear|reset)\(\s*bot[^;]*\);')
    battery_calls = sorted({_re.sub(r"\s+", "", m.group(0)) for m in pattern.finditer(b_body)})
    harness_calls = {_re.sub(r"\s+", "", m.group(0)) for m in pattern.finditer(h_body)}
    problems = []
    for call in battery_calls:
        if call not in harness_calls:
            problems.append(f"电池 `endStep` 有跨步还原 `{call}`，**编排器 `endStepHygiene` 没有**"
                            "（模块单跑会因上一步残留的全局态假红/假绿 ✗ —— D-298 就是这么红过一次）")
    return problems


def rule_structured_attribution():
    """`D-329` ⑤.3 / `M4`（2026-09-20 落地）：**顶层失败归因必须读结构化码字段，禁止子串匹配**。

    事实（本次 retrofit 的对象，代码为准）：伐木 `LumberJob.deriveTopLevelReason` 旧版是
    `attemptFailures.stream().allMatch(f -> f.contains("no_suitable_tool") || f.contains("tool_missing"))`
    —— `f` 是 `"pos:code gained=x/y failed=code"` 这种**拼接串**。后果：同一棵树里"缺镐 + 那格被换成
    别的方块"这类**混合原因**也会被报成 `tool_missing`（把锅甩给工具 ⇒ 决策层去弄工具，而不是换目标）。
    挖矿侧 `MineJob` 早已是逐码比较（`AttemptFailure::code` + `TOOL_CODES`/`BUDGET_CODES`）。

    本规则把两边一起钉住（**类型即约束**：`List<TreeFailure>` / `List<LogFailure>` 拿不到串，
    只有 `describe()` 给人类看）。删任一处 ⇒ 门禁红；把 `f.contains(...)` 写回归因 ⇒ 门禁红。
    """
    problems = []
    lumber = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "lumber"
              / "LumberJob.java").read_text(encoding="utf-8")
    mine = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
            / "MineJob.java").read_text(encoding="utf-8")

    for needle, why in [
        ("public record LogFailure(", "逐原木失败事实不是结构化记录"),
        ("public record TreeFailure(", "逐树失败事实不是结构化记录"),
        ("List<TreeFailure> attemptFailures",
         "逐树失败清单不是结构化类型（只要是 List<String>，就一定会被拿去 contains）"),
        ("List<LogFailure> failedLogs", "逐原木失败清单不是结构化类型"),
        ("public static String deriveTopLevelReason(String base, List<TreeFailure> failures, int treesDone)",
         "归因没有可测试的纯函数形态（夹具喂不了合成事实 ⇒ 判据只能靠跑世界）"),
    ]:
        if needle not in lumber:
            problems.append("LumberJob.java: %s（找不到 `%s`）" % (why, needle))

    body = method_body(lumber, "public static String deriveTopLevelReason(")
    if not body:
        problems.append("LumberJob.java: 找不到归因纯函数体（结构变了 ⇒ 本规则要跟着改）")
    else:
        if "logCodes()" not in body or "TreeFailure::code" not in body:
            problems.append("LumberJob 归因没有读结构化字段（要 `logCodes()` 与 `TreeFailure::code`）")
        if "f.contains(" in body or 'contains("no_suitable_tool")' in body:
            problems.append("LumberJob 归因又用回了**子串匹配**（`f.contains(...)`）—— `D-329` ⑤.3 禁止")

    mbody = method_body(mine, "private String deriveTopLevelReason(String base) {")
    if not mbody:
        problems.append("MineJob.java: 找不到归因方法（结构变了 ⇒ 本规则要跟着改）")
    elif "AttemptFailure::code" not in mbody:
        problems.append("MineJob 归因没有读 `AttemptFailure::code`（逐码比较是这里的口径）")
    return problems


def rule_search_limit_not_unreachable():
    """`D-329` §2 **S3**（2026-09-20 落地）：**`SEARCH_LIMIT ≠ UNREACHABLE`**。

    依据：`D-329` ① 用户裁定"扫描边界 = **不加载**" ⇒ "**没扫到**"与"**没有**"在数据上**必然**同时存在。
    混为一谈的后果有两层：① 决策层拿到错的下一步（去换地方挖，而真相是"还没看完"）；
    ② 任何"那就挖过去"的动作都等于**拿搜索预算当写入授权** —— `D-076` 明令禁止
    （破坏/放置只能由上层任务显式授权并受预算闸门约束）。

    本规则断言（删任一处 ⇒ 红）：
    ① `MineJob.shortfallReason` 纯函数存在，且**搜索受限优先**给 `search_incomplete`（不是 `no_reachable_candidate`）；
    ② `shortfall` 真把 `session.truncated()` 接了进去（没接线 ⇒ 截断永远报不出去）；
    ③ `not_found` 只允许出现在"**扫完**的收尾"里，**不许**出现在逐格 `visit()`（否则没扫完也记成"没有"）；
    ④ 收尾路径 `shortfall` 里**不许**构造挖掘子任务/写授权（S3 红线：搜索受限不得变成"挖过去"）。
    """
    problems = []
    mine = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
            / "MineJob.java").read_text(encoding="utf-8")
    src = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
           / "MineCandidateSource.java").read_text(encoding="utf-8")

    fn = method_body(mine, "public static String shortfallReason(boolean searchLimited, int minedCount) {")
    if not fn:
        problems.append("MineJob 缺 `shortfallReason` 纯函数（S3 的判据点没了 ⇒ 夹具喂不了合成事实）")
    elif "search_incomplete" not in fn:
        problems.append("`shortfallReason` 没有 `search_incomplete` 分支"
                        " ⇒ 搜索受限会退化成『没有可达候选』（S3 禁止）")

    body = method_body(mine, "private Task.Status shortfall(CandidateSet set) {")
    if not body:
        problems.append("MineJob 找不到 `shortfall`（结构变了 ⇒ 本规则要跟着改）")
    else:
        if "session.truncated()" not in body:
            problems.append("`shortfall` 没有把 `session.truncated()` 接进去"
                            "（没接线 ⇒ 分片被预算截断这件事永远报不出去）")
        if "new MineTask(" in body or "WriteGrant" in body:
            problems.append("`shortfall`（配额未达成的收尾）里出现了挖掘子任务/写授权 ⇒ S3 红线（不得『挖过去』）")

    fin = method_body(src, "private void finish(ServerLevel level) {")
    if not fin:
        problems.append("MineCandidateSource 找不到扫完的收尾 `finish(ServerLevel)`（结构变了 ⇒ 规则要跟着改）")
    elif "not_found" not in fin:
        problems.append("扫完的收尾里没有 `not_found` 归因 ⇒ 失去『真的找遍了也没有』这一态")

    vis = method_body(src, "private void visit(ServerLevel level, ServerPlayer bot, "
                           "SafeZoneData safeZones, BlockPos pos) {")
    if vis and "not_found" in vis:
        problems.append("逐格 `visit()` 里写了 `not_found`"
                        " ⇒ **没扫完**也会被记成『没有』（S3 禁止：未扫 ≠ 没矿）")
    return problems


def rule_scan_memory_has_no_positions():
    """`D-329` §2 **S5**（2026-09-20 落地）：扫描记忆**只有计数，没有位置**，且**只有扫完才写**。

    为什么做成门禁而不是注释：记忆一旦能存坐标，它就**必然**会被某个消费者当成"该挖哪一格"的
    事实来源 —— 而记忆是**历史**（那一刻的世界），不是**现在**（`D-348` 同一条纪律）。
    ⇒ 用**类型**挡住（与 `D-354` 的 retrofit 同一个手法）：记忆的字段形状里根本没有位置可放。

    断言（删任一处 ⇒ 红）：
    ① `MineScanMemoryData` 里**不出现 `BlockPos`**，且 `Memory` 的字段恰是 `lastTick/hits/cellsVisited`；
    ② 有界 + **确定性**淘汰（`DEFAULT_CAP` + `evictIfNeeded` + 逐级全序比较，不许随机）；
    ③ 写记忆只发生在**扫完的收尾** `finish(...)` 里，且**只出现一次**（截断的扫描不许写）。
    """
    problems = []
    base = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
    mem = (base / "MineScanMemoryData.java").read_text(encoding="utf-8")
    src = (base / "MineCandidateSource.java").read_text(encoding="utf-8")

    if "BlockPos" in mem:
        problems.append("`MineScanMemoryData` 里出现了 `BlockPos` ⇒ 记忆能存**位置**"
                        "（S5 红线：记忆是历史计数，不许变成「该挖哪一格」的来源）")
    if "public record Memory(long lastTick, int hits, int cellsVisited)" not in mem:
        problems.append("`Memory` 的字段形状变了（必须是 lastTick/hits/cellsVisited ⇒ 没有位置可存）")
    if "DEFAULT_CAP" not in mem or "evictIfNeeded" not in mem:
        problems.append("记忆缺『有界 + 淘汰』（S5 要求有界，且淘汰必须**确定性**）")
    if "thenComparing(" not in mem or "thenComparingLong(" not in mem:
        problems.append("淘汰顺序不是全序（必须 lastTick → bucketKey → chunkKey 逐级比较；随机淘汰 = 不可复现现场）")

    fin = method_body(src, "private void finish(ServerLevel level) {")
    if not fin:
        problems.append("找不到扫完的收尾 `finish(ServerLevel)`（结构变了 ⇒ 本规则要跟着改）")
    elif "noteScanned" not in fin:
        problems.append("写记忆**不在**『扫完的收尾』里 ⇒ 被预算截断的扫描也会写（把「没看完」记成「扫过了」）")
    if src.count("noteScanned") != 1:
        problems.append("`noteScanned` 在扫描器里出现了 %d 次（只许在扫完收尾里出现**一次**）"
                        % src.count("noteScanned"))
    return problems


def rule_intent_before_viability():
    """`D-329` §3（阶段 1.5，2026-09-20 落地）：**作业区/意图只回答"在不在计划里"，不替"能不能挖"下结论**。

    依据（用户 2026-09-20 点出的陷阱）：一个符合意图的作业区里**完全可能有一部分目标实际不可挖**
    （被保护、不可破、视线不可达）。如果**先**算可挖性再问作业区，区外候选就会被内容层的理由顶替
    （`:protected_area` / `:unbreakable`），于是"不在计划里"和"挖不动"**混成一个码** ——
    决策层再也分不清"该换地方"还是"该换个目标"。

    断言（删/改任一处 ⇒ 红）：
    ① `MineIntent` 里 `outside_work_area` 是**唯一**的区域外理由码；
    ② `MineCandidateSource.visit(...)` 与 `revalidate(...)` 里，`intent.refusalFor(...)` 必须**出现在**
       `viabilityRefusal(...)` **之前**（顺序即语义）；
    ③ `GoalSpec` 真的把意图当**输入**（组件在）。
    """
    problems = []
    base = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job"
    src = (base / "mine" / "MineCandidateSource.java").read_text(encoding="utf-8")
    intent = (base / "mine" / "MineIntent.java").read_text(encoding="utf-8")
    spec = (base / "GoalSpec.java").read_text(encoding="utf-8")

    if 'OUTSIDE = "outside_work_area"' not in intent:
        problems.append("`MineIntent` 里没有 `outside_work_area` 这个区域外理由码"
                        "（拒绝必须带**显式码**，不许静默丢弃 —— `D-341` 口径）")
    # ⚠️ 只看**记录头**：工厂方法里也会出现同一个类型名（第一版就是这么被"绿"过去的）
    header_start = spec.find("public record GoalSpec(")
    header_end = spec.find(") {", header_start) if header_start >= 0 else -1
    header = spec[header_start:header_end] if header_start >= 0 and header_end > header_start else ""
    if not header:
        problems.append("找不到 `public record GoalSpec(` 的记录头（结构变了 ⇒ 本规则要跟着改）")
    elif "com.dddgn.alice.job.mine.MineIntent intent" not in header:
        problems.append("`GoalSpec` 的**记录头**里没有意图组件（组件不在 ⇒ 意图只能靠散装字段传，迟早漂）")

    for signature, label in [
        ("private void visit(ServerLevel level, ServerPlayer bot, SafeZoneData safeZones, BlockPos pos) {", "visit"),
        ("public CandidateSet revalidate(ServerPlayer bot, int targetIndex) {", "revalidate"),
    ]:
        body = method_body(src, signature)
        if not body:
            problems.append("找不到 `%s`（结构变了 ⇒ 本规则要跟着改）" % signature)
            continue
        area_at = body.find("intent.refusalFor(")
        viability_at = body.find("viabilityRefusal(")
        if area_at < 0:
            problems.append("`%s` 里没有作业区判定（意图没接上）" % label)
        elif viability_at >= 0 and area_at > viability_at:
            problems.append("`%s` 的**顺序反了**：先算可挖性、后问作业区 ⇒ "
                            "区外候选会被 `:protected_area`/`:unbreakable` 顶替（陷阱）" % label)
    return problems


def rule_cluster_is_pure_geometry():
    """`D-329` §3 邻居（2026-09-20 落地）：**目标簇只回答"谁和谁相连"**。

    依据（用户 2026-09-20 点出的陷阱）：一个"符合要求"的簇里**完全可能有一部分目标实际不可挖**。
    ⇒ 簇判定必须**纯粹是几何**：一旦它开始看授权面/可破性（或扫描记忆），"这一簇里有几格挖得动"
    就会被**固化进簇的身份** —— 而那是**那一刻**的世界事实，会过期（`D-348` 同一条纪律）。
    "哪些真能挖"永远由调用方用**当前**的授权/可破性去算（`MineJob` 的 `revalidate` 就是那个位置）。

    断言（加任一符号 ⇒ 红）：
    ① `TargetClusters` 里**不出现** `ZoneAuthority` / `breakable` / `WriteBudget` / `MineScanMemoryData`
       / `getBlockState`（几何就是几何：不读世界、不问授权、不查记忆）；
    ② 相邻判定只有**一处出处**（`isNeighbour`），且两种口径都在（`FACE` / `DIAGONAL_26` 逐条有判据）；
    ③ 超预算的宽容度是**常量**（`DEFAULT_EXTRA_SEARCH_BUDGET`），不许散落在调用方。
    """
    problems = []
    path = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
            / "TargetClusters.java")
    if not path.exists():
        return ["`TargetClusters` 不在了（本规则要跟着改）"]
    text = path.read_text(encoding="utf-8")
    for banned in ["ZoneAuthority", "breakable", "WriteBudget", "MineScanMemoryData",
                   "getBlockState", "SafeZoneData"]:
        if banned in text:
            problems.append("`TargetClusters` 里出现了 `%s` ⇒ 簇判定**不再纯粹是几何**"
                            "（授权/可破性/记忆都会过期；见本规则头部）" % banned)
    if "public static boolean isNeighbour(" not in text:
        problems.append("找不到唯一的相邻判定出处 `isNeighbour(...)`")
    # ⚠️ 只看**声明处**（`FACE` / `DEFAULT_EXTRA_SEARCH_BUDGET` 这些名字在别处也会出现；
    #    同名子串会把"删掉常量声明"这种注入放绿 —— 本项目当天已在 GoalSpec 上踩过同一个坑）
    enum_start = text.find("public enum Connectivity {")
    enum_end = text.find("\n    }", enum_start) if enum_start >= 0 else -1
    enum_body = text[enum_start:enum_end] if enum_start >= 0 and enum_end > enum_start else ""
    if not enum_body:
        problems.append("找不到 `public enum Connectivity {`（连通口径的声明处）")
    for mode in ["FACE", "DIAGONAL_26"]:
        if not re.search(r"^\s*%s\s*[,;]?\s*$" % mode, enum_body, re.M):
            problems.append("连通口径 `%s` 的**常量声明**不在枚举里"
                            "（两种口径都必须存在且各有判据）" % mode)
    if not re.search(r"public static final int DEFAULT_EXTRA_SEARCH_BUDGET\s*=", text):
        problems.append("超预算宽容度的**常量声明** `public static final int DEFAULT_EXTRA_SEARCH_BUDGET =` 不在"
                        "（散落到调用方 ⇒ 每个调用点一个口径）")
    return problems


def main() -> int:
    k4 = rule_k4()
    k5 = rule_k5()
    s8 = rule_s8()
    walk = rule_walk_budget()
    # ⚠️ NP-P1 与 PG-P1 **必须分开两个变量**（2026-09-17 实测缺陷：两者都叫 `prog` ⇒
    # 下面 `prog = rule_progress_switch()` 把 NP-P1 的结果**静默顶掉** ⇒ 该规则连打印和 `ok` 都不进
    # ⇒ 一次真实注入（去掉剔除 `failed=` 的调用）后门禁**仍然 PASS** ✗ 已修）
    np = rule_progress_signal()
    risk = rule_risk_profile()
    speech = rule_speech_channel()
    perm = rule_permission_service()
    death = rule_death_keeps_data()
    dmg = rule_damage_observed()
    prog_default = []
    _et = (ROOT / "src/main/java/com/dddgn/alice/decision/EventThresholds.java")
    _m = re.search(r"PROGRESS_EVENT_INTERVAL_TICKS\s*=\s*(\d+)", _et.read_text(encoding="utf-8"))
    if not _m or _m.group(1) != "200":
        prog_default.append("`PROGRESS_EVENT_INTERVAL_TICKS` 默认值不是 200（D-289 裁定 8-3(i)：默认打开、粗粒度）")
    prog = rule_progress_switch()
    s10 = rule_no_planning_dependency()
    f1 = rule_driver_attribution()
    j5 = rule_no_until_full()
    r2 = rule_harness_step_hygiene()
    r2p2 = rule_module_step_inventory()
    r2p3 = rule_step_boundary_parity()
    ring = rule_stop_event_ring()
    noperm = rule_no_permitted_candidate()
    loop = rule_loop_admission()
    bwg = rule_bulk_write_zone_gate()
    d344 = rule_replant_sweep_bounded()
    attr = rule_structured_attribution()
    s3 = rule_search_limit_not_unreachable()
    s5 = rule_scan_memory_has_no_positions()
    intent = rule_intent_before_viability()
    clusters = rule_cluster_is_pure_geometry()
    for line in k4:
        print(f"[K4·谓词统一] {line}")
    for line in k5:
        print(f"[K5·状态生产] {line}")
    for line in s8:
        print(f"[S8·死字段] {line}")
    for line in walk:
        print(f"[W·行走预算] {line}")
    for line in np:
        print(f"[NP·进度信号] {line}")
    for line in risk:
        print(f"[S6·风险画像] {line}")
    for line in speech:
        print(f"[F4·说话通道] {line}")
    for line in perm:
        print(f"[F3·请示答复] {line}")
    for line in death:
        print(f"[D1·死亡保留数据] {line}")
    for line in dmg:
        print(f"[S9·伤害可见] {line}")
    for line in prog:
        print(f"[PG·进度开关] {line}")
    for line in j5:
        print(f"[J5·完成判据] {line}")
    for line in prog_default:
        print(f"[PG·进度默认] {line}")
    for line in s10:
        print(f"[S10·依赖管道] {line}")
    for line in f1:
        print(f"[F1·归因] {line}")
    for line in r2:
        print(f"[R2·编排器步边界] {line}")
    for line in r2p2:
        print(f"[R2·步清单完整性] {line}")
    for line in r2p3:
        print(f"[R2·步边界对齐] {line}")
    for line in ring:
        print(f"[D-338·事件环补全] {line}")
    for line in noperm:
        print(f"[D-341·无权≠没有] {line}")
    for line in loop:
        print(f"[D-342·循环受理闸] {line}")
    for line in bwg:
        print(f"[D-343·批量写入区域闸] {line}")
    for line in d344:
        print(f"[D-344·补种扫描有界互斥] {line}")
    for line in attr:
        print(f"[D-329·结构化归因] {line}")
    for line in s3:
        print(f"[D-329·搜索受限≠没有] {line}")
    for line in s5:
        print(f"[D-329·扫描记忆无位置] {line}")
    for line in intent:
        print(f"[D-329·意图先于可挖性] {line}")
    for line in clusters:
        print(f"[D-329·簇只做几何] {line}")
    ok = (not k4 and not k5 and not s8 and not walk and not np and not risk and not speech
          and not perm and not death and not dmg and not prog and not s10 and not f1
          and not prog_default and not j5 and not r2 and not r2p2 and not r2p3 and not ring
          and not noperm and not loop and not bwg and not d344 and not attr and not s3 and not s5 and not intent and not clusters)
    print(f"KERNEL_PREDICATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"工厂谓词漂移={len(k4)} / 死状态={len(k5)} / 死字段复活={len(s8)} / 行走无界={len(walk)} / 失败当进度={len(np)} / 风险画像未接={len(risk)}"
          f" / 编排器步边界={len(r2)} / 步清单={len(r2p2)} / 步边界对齐={len(r2p3)} / 结构化归因={len(attr)} / 搜索受限≠没有={len(s3)} / 扫描记忆无位置={len(s5)} / 意图先于可挖性={len(intent)} / 簇只做几何={len(clusters)}"
          f"（K4-P1/K5-P1/S8-P1/W-P1/NP-P1/S6-P1/F4-P1/R2-P1/R2-P2/R2-P3/M4-P1 —— 见各规则头部的注释）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
