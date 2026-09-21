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

SEARCH_BUDGET_CEILING_MILLIS = 250
ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"

FACTORY_PREDICATES = {
    "TraverseExecutionFactory.java": "canTraverse",
    "DiagonalExecutionFactory.java": "canTraverse",
    "AscendExecutionFactory.java": "canAscend",
    "DescendExecutionFactory.java": "canDescend",
}


def code_only(text: str) -> str:
    """去掉 `//` 行注释后的代码（判据只该看代码；注释里提到旧写法不算违规）。"""
    return "\n".join(line.split("//")[0] for line in text.split("\n"))


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
    # ---- S3 扩展（`P1`，2026-09-21）：**挖掘侧**的 `SEARCH_LIMIT ≠ UNREACHABLE` ----
    # 起因：`A1`（每 tick 搜索总账）上线后，`MiningPlanner.selectBestApproach` 把"本 tick 被限流"
    # 与"搜完了确实没有路"当成同一件事（`if (!path.reached()) continue;`）⇒ 输出 `no_reachable_candidate`
    # ⇒ `MineJob.mine()` **无条件** `attempted.add(mined)` ⇒ 该格本会话再也不会被选中。
    # 真机铁证（第五轮）：25 次拒绝全部 `已发起=1`；目标 `436,82,229` **从未被挖**（`[WRITE] break` 0 次）
    # 却已 `already_attempted`；`search_incomplete` 出现 **0 次**（词早就有，没人用）。
    planner_code = code_only((ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
                              / "mining" / "MiningPlanner.java").read_text(encoding="utf-8"))
    approach = method_body(
        planner_code,
        "private Result selectBestApproach(ServerPlayer bot, ServerLevel level, BlockPos target,")
    if not approach:
        problems.append("`MiningPlanner` 找不到 `selectBestApproach`（结构变了 ⇒ 本规则要跟着改）")
    else:
        if ("PlanningStatus.SEARCH_LIMIT)" not in approach or "searchLimited = true;" not in approach):
            problems.append("`selectBestApproach` 不再区分 `SEARCH_LIMIT`（缺「判状态 + 置标志」）"
                            " ⇒ 「本 tick 被限流」会被写进 `no_reachable_candidate`"
                            "（S3：`SEARCH_LIMIT ≠ UNREACHABLE`）")
        if "search_incomplete" not in approach:
            problems.append("`selectBestApproach` 全失败时没有 `search_incomplete` 分支"
                            " ⇒ 输出端仍然分不出「没评价完」")
    aggregate = method_body(
        planner_code,
        "public Result plan(ServerPlayer bot, BlockPos target, MiningBudget budget, boolean standableOnly) {")
    if not aggregate:
        problems.append("`MiningPlanner` 找不到聚合入口 `plan(…, standableOnly)`（结构变了 ⇒ 规则要跟着改）")
    elif not all(f'"search_incomplete".equals({leg}.failureReason())' in aggregate
                 for leg in ("direct", "tunnel", "enter")):
        problems.append("`MiningPlanner.plan` 聚合三条腿时没有把 `search_incomplete` 单独识别"
                        " ⇒ 任一条腿被限流仍会整体报 `found_but_unminable`（= 不可挖）")
    mine_more = code_only(mine)
    mine_body = method_body(mine_more, "private Task.Status mine() {")
    if not mine_body:
        problems.append("`MineJob` 找不到 `mine()`（结构变了 ⇒ 规则要跟着改）")
    else:
        # ⚠️ 断言必须钉**有效表达式**：只查 `transientFailure(` 会被 `if (true)` 之类的改动骗过
        #（本规则第一次写完就实测到了：注入 `if (true) { attempted.add(mined); }` 仍然 PASS）
        if "!transientFailure || transientSoFar > MAX_TRANSIENT_RETRIES" not in mine_body:
            problems.append("`MineJob.mine()` 的暂时性失败**没有被真正用作闸门**"
                            "（找不到 `!transientFailure || transientSoFar > MAX_TRANSIENT_RETRIES` 这个有效表达式）"
                            " ⇒ 改动绕过闸门不会被发现 ⇒ `search_incomplete` 仍会被永久了结")
        if "MAX_TRANSIENT_RETRIES" not in mine_body:
            problems.append("`MineJob.mine()` 没有有界的重试上限 ⇒ 要么永久跳过、要么空转")
    if "startsWith(\"search_incomplete\")" not in mine_more:
        problems.append("`MineJob.transientFailure` 判据不再以 `search_incomplete` 为准（口径漂了）")
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


def rule_kind_filter_before_cluster():
    """`D-361` 种类分配（用户 2026-09-20）：「挖一组煤炭和一组铁，**煤炭多了就不要了**，做成种类分配」，
    键**既支持标签也支持 ID**；并明确「**还是要保持配额当上限**，当前簇没挖完就放弃」。

    断言（改任一处 ⇒ 红）：
    ① **种类过滤必须发生在簇/选择之前**：`MineJob.select()` 里 `filterByKind(` 的出现位置必须**早于**
       `TargetClusters.queueFor(` 与 `policy.select(` —— 否则"簇"会随配额状态漂移
       （`rule_cluster_is_pure_geometry` 保护的是 `TargetClusters` 自己不读世界，这条保护的是**调用顺序**）；
    ② 配额**仍是硬上限**：`minedCount >= spec.quota()` 这条判定必须**仍在**（不许为了"挖完一簇"把它推迟）；
    ③ 键支持两种形态：`MineKindPlan` 必须复用 `Target#parse`（标签优先、否则方块 id）—— 不许自己写一套解析；
    ④ 空计划惰性：`refusal(...)` 必须在 `active()` 为假时返回 `null`（"没配种类"的老行为不许被改掉）。
    """
    problems = []
    base = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
    job = (base / "MineJob.java").read_text(encoding="utf-8")
    plan = (base / "MineKindPlan.java").read_text(encoding="utf-8")

    body = job[job.find("private Task.Status select()"):]
    if not body:
        return ["找不到 `MineJob.select()`（本规则要跟着改）"]
    at_filter = body.find("filterByKind(")
    at_cluster = body.find("TargetClusters.queueFor(")
    at_policy = body.find("policy.select(")
    if at_filter < 0:
        problems.append("`MineJob.select()` 里没有 `filterByKind(` ⇒ 种类分配没接进决策缝")
    else:
        if at_cluster >= 0 and at_filter > at_cluster:
            problems.append("`filterByKind(` 出现在 `TargetClusters.queueFor(` **之后** ⇒ 簇会随配额状态漂移"
                            "（`D-361` 口径：过滤必须在簇之前）")
        if at_policy >= 0 and at_filter > at_policy:
            problems.append("`filterByKind(` 出现在 `policy.select(` **之后** ⇒ 满足的种类仍会被选中")

    if "minedCount >= spec.quota()" not in job:
        problems.append("找不到 `minedCount >= spec.quota()` ⇒ 配额**不再是硬上限**"
                        "（用户裁定：「还是要保持配额当上限，当前簇没挖完就放弃」）")
    if "Target.parse(" not in plan:
        problems.append("`MineKindPlan` 没有复用 `Target.parse(` ⇒ 键解析**另一套口径**（标签/ID 会漂）")
    if "if (!active()) {\n            return null;\n        }" not in plan:
        problems.append("`MineKindPlan.refusal` 里找不到「空计划 ⇒ 返回 null」⇒ 惰性保证不在了"
                        "（没配种类时老行为会被悄悄改掉）")
    return problems


def rule_clearance_never_eats_task_target():
    """`D-362` **清障不得吃掉任务目标**（用户 2026-09-20 修正口径：「要修的是清障与任务目标的区分，
    即使是绕过去；不管的是成本模型隐含的不准确问题」）。

    真机靶子（A 路线第一轮）：第 8 个目标 `479,68,103`（煤）的站位点 `479,68,104` **本身也是煤**，
    被当清障方块挖掉 —— `[WRITE] break … minecraft:coal_ore by=mine-runner:attempt0:PATH_ACCESS`
    ⇒ 产物进包但**既不进 success 也不进 failure**（同时解释当轮 `candidates=55` / `inventoryDelta=9`）。

    对照 Baritone（本 skill 要求给 `文件:行`）：`MovementHelper.avoidBreaking:68` ⇒ `:590` 代价 COST_INF
    （**绕行**）；`BuilderProcess:1166` `isPossiblyProtected` 同款。Alice 的闸门放在**授权侧**
    （`BlockInteraction`），搜索与执行共用 ⇒ 不会"计划说能过、执行才被拒"。

    断言（改任一处 ⇒ 红）：
    ① `BlockInteraction.breakRefusal` 必须问 `TaskTargetProtection.refusalFor(`，**且**必须**只在 `PATH_ACCESS` 下**问
       （无条件问 ⇒ `EXPECTED_TARGET` 也被拦 ⇒ 挖矿整体被打断；夹具 `guard_expected_allowed` 也会红）；
    ② `beginBreak`（**唯一真正写世界**的入口）也必须问一遍 —— 不能只靠"调用点记得先问 breakable"；
    ③ `BotManager` 换任务时必须撤销作用域（与 `WriteEnvelopes.clear` 同一处）—— 泄漏比 bug 更隐蔽
       （上一个任务的目标保护会把下个任务的开路清障全拦掉）；
    ④ 生产侧必须真的有人装：`MineJob` / `LumberJob` 都要 `begin(` + `end(`（否则规则空转）。
    """
    problems = []
    action = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "action"
    bi = (action / "BlockInteraction.java").read_text(encoding="utf-8")
    guard = (action / "TaskTargetProtection.java").read_text(encoding="utf-8")
    manager = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot"
               / "BotManager.java").read_text(encoding="utf-8")
    mine = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
            / "MineJob.java").read_text(encoding="utf-8")
    lumber = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "lumber"
              / "LumberJob.java").read_text(encoding="utf-8")

    if "public static final String CODE" not in guard:
        problems.append("`TaskTargetProtection` 没有稳定的拒绝码常量 `public static final String CODE`"
                        "（判据/日志要按它比对）")
    refusal = method_body(bi, "public static String breakRefusal(")
    if "TaskTargetProtection.refusalFor(" not in refusal:
        problems.append("`breakRefusal` 没问 `TaskTargetProtection.refusalFor(` ⇒ 清障仍会吃掉任务目标")
    elif "grant.reason() == WriteReason.PATH_ACCESS" not in refusal:
        problems.append("`breakRefusal` 里问了目标保护但**没有限定 `PATH_ACCESS`** ⇒ `EXPECTED_TARGET`"
                        "（真的去挖那一格）也会被拦 ⇒ 挖矿整体被打断")
    begin = method_body(bi, "public static BlockBreakSession beginBreak(")
    if "TaskTargetProtection.refusalFor(" not in begin:
        problems.append("`beginBreak`（**唯一真正写世界**的入口）没问目标保护 ⇒ 只靠调用点自觉，迟早漏一处")
    if "TaskTargetProtection.end(bot)" not in manager:
        problems.append("`BotManager` 换任务时没有 `TaskTargetProtection.end(bot)` ⇒ 作用域会跨任务泄漏"
                        "（下个任务的开路清障全被拦，且很难查）")
    for name, text in (("MineJob", mine), ("LumberJob", lumber)):
        if "TaskTargetProtection.begin(" not in text or "TaskTargetProtection.end(bot)" not in text:
            problems.append("`%s` 没有成对安装/撤销目标保护（`begin(` + `end(bot)`）⇒ 规则空转" % name)
    if "pos.equals(current)" not in mine:
        problems.append("`MineJob` 的目标保护谓词没有豁免「当前这一格」 ⇒ `ENTER_TARGET`（破坏进入自己那格）"
                        "会被自己拦死")
    return problems


def rule_cost_includes_break():
    """`D-363` **`break` 分量进选择成本**（用户 2026-09-20：「`break` 分量我觉得可以马上做」）。

    缺口（A 路线第一轮实测）：选择成本只算**纯通行** ⇒ 真实地形里全候选 `∞`（`cells=0`）⇒ 排序退化成
    欧氏最近；而执行器在用 `TUNNEL`（走+挖）。修法 = 补上 `D-329` §2.1 里设计过但没实现的 **top-K 精算**，
    复用 `MiningPlanner`（它的路径成本本来就含破坏 tick 折算）⇒ **不新造破坏估算器**。

    断言（改任一处 ⇒ 红）：
    ① 生产策略必须走精算链：`CostOptimalPolicy.production()` 里出现 `PlanRefinedCostProvider`；
    ② 精算次数是**常量**（`REFINE_PER_SELECT`）且 > 0（有界，不许无上限地每个候选都跑规划器）
       —— `D-368` 起是**摊销**：每次选择 ≤1 次，覆盖靠缓存跨选择累积；
    ②b 必须有缓存 TTL 常量，且**精算失败也要记账**（否则失败候选每次挡住轮转）；
    ③ 精算必须真的用 `MiningPlanner`（`new MiningPlanner()` + `.plan(`）—— 手写一套破坏估算 = 另造内核；
    ④ **不许把"估不出"当"不能挖"**：精算失败只能 `continue`（保持"估不出"），不许据此拒绝候选。
    """
    problems = []
    base = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job"
    policy = (base / "policy" / "CostOptimalPolicy.java").read_text(encoding="utf-8")
    provider = (base / "mine" / "PlanRefinedCostProvider.java").read_text(encoding="utf-8")

    production = method_body(policy, "public static CostOptimalPolicy production()")
    if "PlanRefinedCostProvider" not in production:
        problems.append("`CostOptimalPolicy.production()` 没用 `PlanRefinedCostProvider` ⇒ 生产仍是纯走路成本"
                        "（`break` 分量没接上；`D-363` 的真机退化会原样回来）")
    if not re.search(r"public static final int REFINE_PER_SELECT\s*=\s*[1-9]", provider):
        problems.append("`REFINE_PER_SELECT` 的声明不是 > 0 的常量 ⇒ 摊销精算要么关掉、要么无界"
                        "（`D-367` 实测：3 次完整规划器 = 102→126 ms ⇒ 超 tick 预算 2 倍）")
    if not re.search(r"public static final long CACHE_TTL_TICKS\s*=\s*[1-9]", provider):
        problems.append("缺 `CACHE_TTL_TICKS` 常量 ⇒ 摊销靠什么复用、过期由谁说了算都不可见")
    if "if (Double.isFinite(cost)) { cache.put(" in provider or "cache.put(key, new Entry(cost, now));" not in provider:
        problems.append("精算**失败**没有记账（`cache.put` 被 `Double.isFinite` 包住）⇒ 同一个失败候选"
                        "每次选择都会挡住轮转 ⇒ 覆盖永远涨不上去（`D-368` 反向对照实测：这条断言最初太弱，"
                        "必须用「排序第一个」当失败靶子才抓得住）")
    if "new MiningPlanner()" not in provider or ".plan(" not in provider:
        problems.append("精算没有走 `MiningPlanner`（`new MiningPlanner()` + `.plan(`）⇒ 等于自己另写一套破坏估算"
                        "（内核路线禁止：破坏成本已有唯一出处）")
    if "continue;" not in provider:
        problems.append("精算失败分支没有 `continue`（保持「估不出」）⇒ 有把「估不出」当成「不能挖」的风险"
                        "（`SEARCH_LIMIT != UNREACHABLE`）")
    return problems


def rule_support_and_cluster_order():
    """`D-364` **垫方块只在「掉落物真会丢」时** + **垫不上不判死** + **簇内按图距**（2026-09-20 真机实测）。

    真机靶子（`新的世界 (2)`，`/alice mine here` @393,71,176）：
    ①**垫方块过触发**：先挖 y=72、再挖 y=73 时下方正是**自己刚挖空的空气** ⇒ 旧判据 `!hasSupportBelow`
      （下方那格不是实心就垫）判它「悬空」⇒ 要垫 ⇒ **垫不上就把目标判死**（实测 9 次 `SUPPORT_PLACE_FAILED`
      + 23 次 `MOVE_MOVEMENT_FAILED`）⇒ 整层 y=73 的煤被留下（存档核对：`400/401/402,73,181` 仍是 coal_ore），
      bot 跑去 18 格外的远簇；垫上了又**挡住相邻矿视线**（实测 `LINE_OF_SIGHT_BLOCKED`）。
      而代码注释写的原意只是「防止掉进**虚空/岩浆/深坑**」—— **实现比意图宽**。
    ②**簇内顺序**：`queueFor` 直接用发现顺序（= 扫描的逐 y 层方环序）⇒ `y=71 三格 → y=72 七格 → y=73`、
      层内横跳；单矿只挖 12 tick（0.6s）而走到下一站位点要 9s ⇒ 用户看到「挖一半突然跑出几格又跑回来」。

    断言（改任一处 ⇒ 红）：
    ① `MiningPlanner` 必须按**真会丢**判（`dropWouldBeLost(`），**不得**再出现旧的 `!hasSupportBelow(level, target)` 判据；
    ② `MineBlockRunner.tickSupportPlacement` **不得**再把「垫不上」变成目标失败（不许 `fail("SUPPORT_PLACE_FAILED"`）
       且必须留下 `supportSkipped = true`（可归因）；
    ③ `TargetClusters.queueFor` 必须用 `graphDistance(`（BFS 图距）排序 —— 仍是**纯几何**。
    """
    problems = []
    planner = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task" / "mining"
               / "MiningPlanner.java").read_text(encoding="utf-8")
    runner = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "action"
              / "MineBlockRunner.java").read_text(encoding="utf-8")
    clusters = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
                / "TargetClusters.java").read_text(encoding="utf-8")

    if "dropWouldBeLost(" not in planner:
        problems.append("`MiningPlanner` 没有 `dropWouldBeLost(` ⇒ 垫方块又变成「下方那格不是实心就垫」"
                        "（真机实测会把挖矿自己挖出的坑当悬空 ⇒ 目标被垫方块毁掉）")
    if "!hasSupportBelow(level, target)" in planner:
        problems.append("`MiningPlanner` 里仍以 `!hasSupportBelow(level, target)` 作判据 ⇒ `D-364` 的口径回退")
    placement = code_only(method_body(runner, "private Status tickSupportPlacement()"))
    if 'fail("SUPPORT_PLACE_FAILED"' in placement:
        problems.append("`tickSupportPlacement` 仍把「垫不上」变成目标失败 ⇒ 实测会毁掉整层矿石"
                        "（应降级为「照挖」，掉落物落到坑底仍能捡）")
    if "supportSkipped = true" not in placement:
        problems.append("`tickSupportPlacement` 没有 `supportSkipped = true` ⇒ 「垫不上」不可归因")
    queue = method_body(clusters, "public static List<BlockPos> queueFor(Collection<BlockPos> anchors, BlockPos picked, Connectivity mode,")
    if "graphDistance(" not in queue:
        problems.append("`TargetClusters.queueFor` 没用 `graphDistance(` ⇒ 簇内顺序退回「发现顺序」"
                        "（真机观感：挖一半突然跑出几格又跑回来）")
    if ".sort(" not in queue:
        problems.append("`TargetClusters.queueFor` 没有对簇成员排序 ⇒ 顺序不确定/退化")
    return problems


def rule_mine_in_place_before_walk():
    """`D-365` **目标在视线内就地挖**（用户 2026-09-20 要求）。

    真机靶子：目标 `367,77,430`(铜矿) 离 bot 站位 `369,78,430` 只有 **2 格**，规划器却给
    `mode=TUNNEL pathSize=11`；这一轮 **64 次破坏里 56 次是挖路**（只有 8 次挖到目标矿）
    ⇒ 写预算打满、`mined 8/64`、`partial_quota`。

    断言（改任一处 ⇒ 红）：
    ① `MineBlockRunner.tick()` 里 `canMineInPlace()` 必须**在走路闸门之前**被问（否则等于没修）；
    ② 判据必须用**执行期同一套**（`checkFromEye(` + `getBlockReach()`）—— 不许自己另写一套视线/触及公式；
    ③ 判据里**不得**要求「这格适合站位」（`canStandCentered`/`isStandable`）：bot 已经在上面了，
       "适不适合站位"是寻路问题 —— 真机里正是这种错位让 bot 放着眼前的矿不挖、去挖隧道；
    ④ 用户明确要求保留的那条：**会丢的掉落物仍要先处理**（计划要求垫方块且还没垫 ⇒ 先按计划走）。
    """
    problems = []
    runner = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "action"
              / "MineBlockRunner.java").read_text(encoding="utf-8")
    tick = code_only(method_body(runner, "public Status tick()"))
    judge = code_only(method_body(runner, "private boolean canMineInPlace()"))
    if "canMineInPlace()" not in tick:
        problems.append("`tick()` 里没有 `canMineInPlace()` ⇒ 就地挖没接上（`D-365` 未生效）")
    elif tick.index("canMineInPlace()") > tick.index("if (runner != null)"):
        problems.append("`canMineInPlace()` 出现在走路闸门**之后** ⇒ 永远先走路，等于没修")
    if "checkFromEye(" not in judge or "getBlockReach()" not in judge:
        problems.append("就地挖判据没有用执行期同一套（`checkFromEye(` + `getBlockReach()`）"
                        "⇒ 自己另写视线/触及公式，会出现「预检说能挖、真挖被拒」")
    if "canStandCentered" in judge or "isStandable" in judge:
        problems.append("就地挖判据里要求了「这格适合站位」⇒ 真机里正是这条错位让 bot 放着眼前的矿不挖、去挖隧道")
    if "supportPlacementPos()" not in judge or "supportPlaced" not in judge:
        problems.append("就地挖判据没有保留「会丢的掉落物要先接住」⇒ 用户明确要求不能跳过这条")
    return problems


def rule_movement_contract_agreement():
    """`D-366` **移动契约三方一致**（2026-09-20 真机崩服换来的规则）。

    崩服原文（`crash-reports/crash-2026-09-20_21.32.05-server.txt`）：
    `IllegalArgumentException: PLACE_STEP_AND_TRAVERSE requires one cardinal step, dy 0 or -1`
    ← `MovementSpec.validateDisplacement:110`（**硬抛**）← `PlannedMovementSpecs.toSpec:57`
    ← `PathSession.startSegment:329`。根因：搜索生成侧 `SurfaceMovementProvider` 的
    `for (int dy = 1; dy >= -1; dy--)` 允许 `dy=+1`（D-336 于 2026-09-19 加入），
    而 `MovementSpec` 与 `PlaceStepAndTraverseExecutionFactory` **都只接受 {0,-1}**（2:1）
    ⇒ 搜索规划出执行端构造不出来的边 ⇒ 崩服。D-336 的夹具是 **EXTRA + 规划级**，只测了"能规划出来"。

    断言（改任一处 ⇒ 红）：
    ① 生成侧必须从 `dy = 0` 起（不得再有 `dy = 1`）；
    ② `PathSession.startSegment` 必须把 `toSpec` 包起来 ⇒ **契约不一致降级为段失败，绝不崩服**；
    ③ 必须保留"搜索产出的每一步都能构造 `MovementSpec`"的**执行级**不变量夹具；
    ④ `miningApproach` 的 D-366b 让步（放开 PILLAR/FALL/DOWNWARD）必须**显式标注回收条件**，不许隐形放宽红线。
    """
    problems = []
    provider = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
                / "search" / "SurfaceMovementProvider.java").read_text(encoding="utf-8")
    session = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
               / "session" / "PathSession.java").read_text(encoding="utf-8")
    fixture = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
               / "PlaceStepDiagonalCheckTask.java").read_text(encoding="utf-8")
    request = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
               / "search" / "PathRequest.java").read_text(encoding="utf-8")

    if "for (int dy = 1; dy >= -1; dy--)" in code_only(provider):
        problems.append("`SurfaceMovementProvider` 又从 `dy = 1` 起生成 `PLACE_STEP_AND_TRAVERSE` ⇒ "
                        "执行端 `MovementSpec` 只接受 {0,-1} ⇒ 会规划出构造不出来的边（真机崩服原因）")
    start = code_only(method_body(session, "private void startSegment()"))
    if "PlannedMovementSpecs.toSpec(" not in start:
        problems.append("`startSegment` 里找不到 `PlannedMovementSpecs.toSpec(` ⇒ 结构变了，请人工核对")
    elif "catch (RuntimeException" not in start or "mapFailure(" not in start:
        problems.append("`startSegment` 没有把 `toSpec` 包在 try/catch（+ `mapFailure(`）⇒ "
                        "一条违反契约的边会**崩掉服务器**，而不是让这一段失败")
    # ⚠️ 断言要"能被违反"：只查方法定义存在会被"定义了但没调用"骗过（反向对照实测）⇒ 必须**调用点也在 check 里**
    if (fixture.count("contractViolation(") < 2
            or "check(" not in fixture.split("contractViolation(")[1][:200]
            or "PlannedMovementSpecs.toSpec(" not in fixture):
        problems.append("缺少「搜索产出的每一步都能构造 `MovementSpec`」的执行级不变量断言"
                        "（`D-336` 只有规划级 EXTRA 夹具，所以当年没拦住）")
    mining = method_body(request, "public static PathRequest miningApproach(String botId, BlockPos startFoot, BlockPos goalFoot,")
    if "MovementType.PILLAR" in mining and "回收条件" not in request:
        problems.append("`miningApproach` 放开了 PILLAR/FALL/DOWNWARD（D-366b 让步）却**没有标注回收条件**"
                        "⇒ 临时让步变永久红线")
    return problems


def rule_search_budget_is_tick_aware():
    """`D-369` **搜索的时间预算必须与 tick 预算同量级**（2026-09-20 真机掉刻的机制）。

    事实链：① Alice 的搜索**跑在服务器 tick 线程上**（`PathRetryRunner.tick → PathSession.tick`，
    2026-09-20 崩服栈可见）；② tick 预算 = **50 ms**；③ `CorePathPlanner.DEFAULT_MAX_MILLIS` 原值
    **3_000 ms = 60 倍预算** ⇒ 单次搜索可合法独占服务器近 3 秒；④ 真机三次
    `Can't keep up! … Running 2035 / 2632 / 2232 ms`（21:30:53 / 21:31:11 / 21:31:30）正落在该上限之下；
    ⑤ 无头电池（CORE）里全部搜索实测 ≤ 9 ms ⇒ 正常路径用不到大预算。

    内核对照（`D-036`）：Baritone 把搜索**放独立线程**（`PathingBehavior.java:469 findPathInNewThread` +
    `safeForThreadedUse` 断言，另有 `primaryTimeoutMS`/`failureTimeoutMS`）⇒ 线程化才是根治；
    Alice 目前只能**把默认预算压到 tick 量级**（本规则守的就是这个值不许悄悄涨回去）。

    断言（改任一处 ⇒ 红）：
    ① `DEFAULT_MAX_MILLIS` ≤ {@link #SEARCH_BUDGET_CEILING_MILLIS}（涨回去 = 单 tick 卡顿回归）；
    ② 搜索循环必须**真的检查**时间预算（`budget.timeBudgetExhausted(`）；
    ③ 超 tick 预算必须有日志（`TICK_BUDGET_WARN_MILLIS` + `[Search] 超 tick 预算`）⇒ 调参有数据，
       否则"该调紧还是调松"只能靠猜。
    """
    problems = []
    planner = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
               / "search" / "CorePathPlanner.java").read_text(encoding="utf-8")
    search = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
              / "search" / "AStarMovementSearch.java").read_text(encoding="utf-8")
    match = re.search(r"DEFAULT_MAX_MILLIS\s*=\s*([0-9_]+)L", planner)
    if not match:
        problems.append("找不到 `DEFAULT_MAX_MILLIS` 的常量声明 ⇒ 搜索墙钟预算不可见（调度器可能无界）")
    else:
        value = int(match.group(1).replace("_", ""))
        if value > SEARCH_BUDGET_CEILING_MILLIS:
            problems.append("`DEFAULT_MAX_MILLIS=%d ms` 超过 %d ms 上限 ⇒ 搜索跑在 tick 线程上（预算 50 ms），"
                            "单次搜索最多可独占服务器 ~%d 个 tick ⇒ 真机 `Can't keep up! 2035/2632/2232 ms` "
                            "会原样回来；要调大必须在 `docs/AI_DECISIONS.md` 登记并说明为何不要线程化"
                            % (value, SEARCH_BUDGET_CEILING_MILLIS, value // 50))
    # ⚠️ 必须断言**强制点本身**，不能只找标识符：`AStarMovementSearch` 里还有一处"记账"用途
    # （`budgetNote` 里 `budget.timeBudgetExhausted(elapsed) ? "time" : "nodes"`）⇒ 只查标识符会被它满足，
    # 反向对照实测**没红**（本会话第三次「判据太弱」）。这里钉住**那条 if 条件**。
    if "budget.nodeBudgetExhausted(expandedNodes) || budget.timeBudgetExhausted(elapsed)" \
            not in code_only(search):
        problems.append("搜索循环没有检查时间预算（`budget.timeBudgetExhausted(`）⇒ 墙钟预算形同虚设"
                        "（节点预算挡不住「单点很贵」的搜索）")
    if "TICK_BUDGET_WARN_MILLIS" not in search or "[Search] 超 tick 预算" not in search:
        problems.append("超 tick 预算没有日志 ⇒ 默认预算该调紧还是调松没有数据来源（只能靠猜）")
    return problems


def rule_standing_point_detour_bounded():
    """`D-370` **不许绕远**（用户 2026-09-20 亲眼所见：「跑到了很远的第一个同层可站点，然后水平挖过去」）。

    真机证据：bot 在自挖沟底 `461,77,318`、目标 `463,79,317`（高 2 格）⇒ 规划器给
    `standingFoot=462,79,317`（**站位格离目标只 1 格**）但 **`pathSize=11`** ⇒
    **病根是"到达路径长度"，不是站位格远近**（同层石壳几何实测站位格距离 = 1、pathSize = 3–4）。

    断言（改任一处 ⇒ 红）：
    ① 夹具必须断言**到达路径有界**（`pathSize` 与上限的比较）—— 没有这条，"绕远"就没有判据；
    ② 夹具必须断言**站位格紧邻目标**（距离 ≤ 2 格）；
    ③ **夹具场景必须物理合法**：坑底几何里**头位格也要清成空气**
       （第一版只清脚位 ⇒ 眼睛嵌在石头里 ⇒ `LineOfSightChecker` **假阳性**，实测 `mode=CURRENT pathSize=0`
       看着"完美"其实是非法状态下的错判）。
    """
    problems = []
    fixture = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
               / "MineMenuCheckTask.java").read_text(encoding="utf-8")
    marker = "private void runStandingPointChoiceChecks()"
    if marker not in fixture:
        return ["找不到 `runStandingPointChoiceChecks`（R3 站位点/路径夹具被删？）—— "
                "真机「目标 2 格远却 11 段隧道」就没有判据了"]
    body = method_body(fixture, marker)
    # ⚠️ 必须查**去掉注释后**的代码：注入把调用注释掉（`// runStandingPointChoiceChecks();`）时，
    # 单纯查字符串仍然命中 ⇒ 反向对照实测**没红**（本会话第四次「判据太弱」，同 `D-366` 一类）。
    if "runStandingPointChoiceChecks();" not in code_only(fixture):
        problems.append("`runStandingPointChoiceChecks()` 没有被调用（夹具在但不跑 = 等于没有）")
    if "path().movements().size() <= 6" not in code_only(body):
        problems.append("缺少「到达路径必须有界（≤6 段）」的判据 ⇒ 用户看到的「绕远」没有断言守着")
    if "distSqr(" not in code_only(body):
        problems.append("缺少「站位格必须紧邻目标（≤2 格）」的判据")
    if "pitFoot.above(2)" not in code_only(body):
        problems.append("坑底几何**没有清头位格** ⇒ 眼睛嵌在方块里会让 `LineOfSightChecker` 假阳性"
                        "（实测出现 `mode=CURRENT pathSize=0` 的错判）⇒ 夹具场景必须物理合法")
    return problems


def rule_scan_advances_every_select():
    """`D-371` **扫描必须每次选择都推进**（R1 覆盖缺口的直接原因）。

    真机证据（2026-09-20）：整轮 `分片推进` **只出现 1 次**（`visited=8192/117649` = 7%），
    同一矿脉 `y=76`×5 / `y=81`×5 共 **10 格从未进入任何一次选择**。原因：`session.advance(bot)`
    写在 `if (selection.picked() == null)` 分支里 ⇒ **只要第一分片里还有能挖的，候选集永久冻结**。

    断言（改任一处 ⇒ 红）：
    ① `session.advance(` 必须出现在 `MineJob.select()` 里 `if (selection.picked() == null)` **之前**
       （放回那个分支 = 覆盖永冻，真机退化原样回来）；
    ② 夹具必须存在且被调用（"一次一格预算 + 反复推进能覆盖全量"两条合同）。
    """
    problems = []
    job = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
           / "MineJob.java").read_text(encoding="utf-8")
    fixture = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
               / "MineMenuCheckTask.java").read_text(encoding="utf-8")
    body = code_only(method_body(job, "private Task.Status select()"))
    advance = body.find("session.advance(")
    if advance < 0:
        problems.append("`MineJob.select()` 里找不到 `session.advance(` ⇒ 分片扫描根本不会推进"
                        "（第一分片之外的矿**永远进不了候选**：真机实测 10 格从未出现）")
    else:
        frozen = body.find("if (selection.picked() == null)")
        if frozen >= 0 and advance > frozen:
            problems.append("`session.advance(` 又回到了「没得挖才扫」的分支里 ⇒ 只要第一分片还有能挖的，"
                            "候选集**永久冻结**（真机 `visited=8192/117649` 的退化会原样回来）")
    if "runScanCoverageChecks" not in code_only(fixture) or "runScanCoverageChecks();" not in code_only(fixture):
        problems.append("缺少 R1 覆盖夹具（一次一格预算 + 反复推进覆盖全量）或它没被调用")
    return problems


def rule_write_caps_default_open_protection_kept():
    """`D-372` **默认不设格数上限**（用户 2026-09-21 裁定）。

    用户原话：「**保护区外，世界修改全部放开，只限制时间防止空转**」；
    「不是说区内强制不让修改啊，**保护区本来不就是有分级权限管理吗，保持权限管理就行**」。

    三条语义（缺一不可，改任一处 ⇒ 红）：
    ① **默认不限**：破坏/放置的上限回退必须是 `Caps.UNBOUNDED`（不是 `Caps.DEFAULT`）
       —— 闸门换成**时间预算**防空转；
    ② **显式装订的上限照旧强制**（`setCaps` / `capForEscape`）⇒ `D-241` 逃生准备金与各夹具的
       "1 格"用例继续有效；容器轴（别人的存储）**不随本次放开**，仍回退 `Caps.DEFAULT`；
    ③ **保护区权限层必须还在**：约束不在格数上限里，而在 `CapabilityGate` 的
       `protectionReason(...)`（`protected_area` / `protected_block`）——放开默认上限**不许顺手拆掉它**。
    """
    problems = []
    budget = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "action"
              / "WriteBudget.java").read_text(encoding="utf-8")
    gate = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
            / "CapabilityGate.java").read_text(encoding="utf-8")
    collector = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
                 / "CollectDropsTask.java").read_text(encoding="utf-8")

    for method in ("public static Verdict consumeBreak(", "public static Verdict consumePlace("):
        body = code_only(method_body(budget, method))
        if "Caps.UNBOUNDED" not in body:
            problems.append("`%s` 的默认回退不是 `Caps.UNBOUNDED` ⇒ 默认格数上限又回来了"
                            "（`D-372`：保护区外世界修改放开，只留时间预算防空转）" % method.split()[3])
    # ⚠️ 必须断言**取值那一行**：只查"body 里出现过 DEFAULT"会被"别处仍有一处 DEFAULT"满足
    # （注入实测没红，本会话第 5 次「判据太弱」）。
    container = code_only(method_body(budget, "public static Verdict consumeContainerWrite("))
    if "Caps caps = CAPS.getOrDefault(scope, Caps.DEFAULT);" not in container:
        problems.append("容器轴（`consumeContainerWrite`）不该随本次放开：它是**别人的存储**"
                        "（另一条红线，`D-076` 容器授权面）⇒ 回退应保持 `Caps.DEFAULT`")
    if "capForEscape" not in budget or "setCaps" not in budget:
        problems.append("显式装订上限的入口（`setCaps` / `capForEscape`）不见了 ⇒ "
                        "`D-241` 逃生准备金与「1 格」夹具会失去强制力")
    # ⚠️ 断言**调用点**（带接收者）：`CapabilityGate` 里还有一条**接口声明**
    # `String protectionReason(BlockPos pos, boolean placing);` ⇒ 只查标识符会被声明满足
    # （注入实测没红，本会话第 6 次「判据太弱」）。
    if "facts.protectionReason(" not in code_only(gate):
        problems.append("`CapabilityGate` 不再查 `protectionReason(...)` ⇒ **保护区权限层被拆掉了**"
                        "（放开默认上限 ≠ 放开保护区：用户明确要求「保持权限管理」）")
    if not re.search(r"(?:public|private) static final int DEFAULT_TOTAL_BUDGET_TICKS\s*=\s*[0-9_]+", collector):
        problems.append("收集器没有时间预算常量 ⇒ 「只限制时间防止空转」这条没有落点")
    return problems


def rule_tick_search_account_enforced():
    """**A1（2026-09-21）：每 tick 搜索总账必须真的在拦**（真机"57.6 秒掉刻 / 0.4 TPS"的止血判据）。

    真机第四轮复算（`docs/reviews/2026-09-21-客户端第四轮-深矿搜索卡顿.md` + 日志原文计数）：
    `[Search] 超 tick 预算` **376 条**、最大一波 **336 条跨 57.6 s**；`[Job] step` 间隔 **2.4 s**
    （= 一个 tick ⇒ ≈0.4 TPS）；根因不是"单次搜索慢"（`D-369` 已框 200 ms），而是
    **"一个 tick 里连发 13 次全预算搜索"**（`MiningPlanner` 模式 B 对 13 个站位候选逐一精算）。
    定性同 `survey/24 §1.1`：**共享资源的记账缺失** —— 与项目自己解决过的 `WriteBudget` 同一类问题。

    断言（改任一处 ⇒ 红）：
    ① 规划入口（`CorePathPlanner.plan`）必须过 tick 边界 + 过闸门（**钉有效调用**，不是钉类名）；
    ② 拒绝必须是 `SEARCH_LIMIT` + `tick_search_budget_exhausted` 诊断（`D-076`：不许伪装成不可达）；
    ③ **没有搜索能绕过记账**：`search.search(context, forbiddenEdges)` 在 `CorePathPlanner` 里
       只许出现 **1 次**（那个唯一出口 = `searchAndRecord`）—— 漏记一处，闸门就会在"实际已超预算"时放行；
    ④ 主判据必须是**"烧预算的搜索次数"**而不是纯总毫秒（电池实测：夹具一个 tick 里 20 次**廉价**搜索
       累计 161 ms 被旧写法误伤 ⇒ `就地挖：远处先得到真计划` 判据变红；廉价搜索不是病灶）；
    ⑤ `recordExternal`（选择期成本场）**只许记毫秒、不许吃主判据的额度**（否则成本场会把挖矿规划挤掉）。
    """
    problems = []
    planner = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
               / "search" / "CorePathPlanner.java").read_text(encoding="utf-8")
    account_path = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"
                    / "search" / "SearchTickBudget.java")
    if not account_path.exists():
        return ["`SearchTickBudget`（每 tick 搜索总账）不存在 ⇒ 掉刻的根因（预算不在同一个账上）没有落点"]
    account = account_path.read_text(encoding="utf-8")
    planner_code = code_only(planner)
    if "SearchTickBudget.handleTick(level.getGameTime())" not in planner_code:
        problems.append("`CorePathPlanner.plan` 没有过 tick 边界（`SearchTickBudget.handleTick(level.getGameTime())`）"
                        "⇒ 账会跨 tick 累加 / 或永远不会清零")
    gate = "if (!SearchTickBudget.tryAcquire())"
    if gate not in planner_code:
        problems.append("`CorePathPlanner.plan` 没有 A1 闸门（`if (!SearchTickBudget.tryAcquire())`）"
                        "⇒ 每 tick 搜索总账形同虚设，真机 2.4 s/tick 会原样回来")
    else:
        # ⚠️ 钉**闸门之后那段**：拒绝必须是 SEARCH_LIMIT（不是 UNREACHABLE）+ 带唯一诊断字段
        tail = planner_code[planner_code.find(gate):][:600]
        if "PlanningStatus.SEARCH_LIMIT" not in tail or "tick_search_budget_exhausted" not in tail:
            problems.append("A1 闸门的拒绝没有如实交出 `SEARCH_LIMIT` + `tick_search_budget_exhausted` 诊断"
                            "⇒ 「预算不够」与「根本没有路」事后分不开（`D-076` + `survey/24 §2.4`）")
    # ③ 唯一出口：漏记一处 = 闸门在"实际已超预算"时放行
    call_sites = planner_code.count("search.search(context, forbiddenEdges)")
    if call_sites != 1:
        problems.append("`CorePathPlanner` 里有 %d 处 `search.search(context, forbiddenEdges)`（应为 1）"
                        "⇒ 有搜索绕过了 `searchAndRecord` 的记账（漏记的那次会让闸门误放行）" % call_sites)
    # ④ 主判据 = 烧预算的次数（钉**强制表达式**，不是标识符）
    if "expensiveSearches >= limitExpensive" not in code_only(account):
        problems.append("A1 主判据不是「烧预算的搜索次数」（缺 `expensiveSearches >= limitExpensive`）"
                        "⇒ 退回纯总毫秒会误伤「一个 tick 里一串廉价搜索」的正常链条（电池实测已咬到一次）")
    if "if (elapsedMillis >= EXPENSIVE_SEARCH_MILLIS)" not in code_only(account):
        problems.append("没有按 `EXPENSIVE_SEARCH_MILLIS` 判定「这次搜索烧掉了预算」⇒ 主判据没有计量来源")
    external = code_only(method_body(account, "public static void recordExternal("))
    if "expensiveSearches++" in external:
        problems.append("`recordExternal`（选择期成本场）在吃主判据的额度 ⇒ 成本场会把挖矿规划挤掉"
                        "（真机实测成本场单次 `ms=69`，与路径搜索争同一个 tick）")
    fixture = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
               / "MineMenuCheckTask.java").read_text(encoding="utf-8")
    if "runSearchBudgetChecks();" not in code_only(fixture):
        problems.append("A1/A2 夹具没有被调用（夹具在但不跑 = 等于没有）")
    else:
        body = code_only(method_body(fixture, "private void runSearchBudgetChecks()"))
        if "!SearchTickBudget.tryAcquire()" not in body:
            problems.append("A1 夹具没有断言「超限必须拒新搜索」这件事本身")
        if "tickExpensiveSearches()" not in body or "recordMillis(5L)" not in body:
            problems.append("A1 夹具缺少**负向对照**（廉价搜索不许消耗「烧预算」额度）"
                            "⇒ 判据退化成「只要拒绝就算对」，压不住误伤正常链条的回归")
    return problems


def rule_approach_plans_bounded():
    """**A2（2026-09-21）：模式 B 的站位候选穷举必须有界**（真机 2.4 s/tick 的直接来源）。

    事实：`MiningPlanner.selectBestApproach` 原来对 `tunnelCandidates` **全部**候选各跑一次
    `PathRequest.miningApproach` 全预算 A\\*（`WALK_BUDGET` = 20 000 节点 / 200 ms）。
    真机实测 `candidates=13 planned=13` ⇒ 一次规划 **≈2.4 s**（且发生在 tick 线程上）。

    断言（改任一处 ⇒ 红）：
    ① 上限常量存在；② **上限检查必须出现在 `planPath(` 调用之前**（写在调用之后 = 一点都没省）；
    ③ 截断必须是 `break`（`continue` 只跳过本次，等于没截断）；④ 夹具按**实测量**断言
       （一次模式 B 规划发起的搜索次数），且用**字面量 3** 钉住 —— 用常量断言等于"改大常量就自动变绿"。
    """
    problems = []
    planner = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task" / "mining"
               / "MiningPlanner.java").read_text(encoding="utf-8")
    if "MAX_APPROACH_PLANS" not in planner:
        problems.append("`MiningPlanner` 没有模式 B 的穷举上限常量（`MAX_APPROACH_PLANS`）")
    body = code_only(method_body(planner, "private Result selectBestApproach("))
    cap = body.find("planned >= MAX_APPROACH_PLANS")
    call = body.find("planPath(bot, startFoot, foot,")
    if cap < 0:
        problems.append("`selectBestApproach` 没有上限检查（`planned >= MAX_APPROACH_PLANS`）"
                        "⇒ 13 个候选各跑一次全预算搜索 = 2.4 s/tick 的根因原样回来")
    elif call < 0:
        problems.append("找不到 `selectBestApproach` 里的 `planPath(` 调用 ⇒ 判据无法定位（代码形状变了？）")
    elif cap > call:
        problems.append("上限检查出现在 `planPath(` **之后** ⇒ 搜索已经跑过了，一点都没省")
    elif "break;" not in body[cap:cap + 200]:
        problems.append("上限处不是 `break`（`continue` 只跳过本次 ⇒ 等于没有上限）")
    fixture = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
               / "MineMenuCheckTask.java").read_text(encoding="utf-8")
    fbody = code_only(method_body(fixture, "private void runSearchBudgetChecks()"))
    if "issued == 3" not in fbody:
        problems.append("A2 夹具没有用**字面量 3** 断言「一次模式 B 规划最多发起 3 次全预算搜索」"
                        "⇒ 把 `MAX_APPROACH_PLANS` 改大就会自动变绿（判据太弱）")
    if "SearchTickBudget.tickSearches()" not in fbody:
        problems.append("A2 夹具没有按**实测量**（`SearchTickBudget.tickSearches()`）断言搜索次数"
                        "⇒ 它量的会是常量而不是真实行为")
    return problems


def rule_edge_destination_body_clearance():
    """`D-374`（2026-09-21 真机实测 + 存档取证）：**边生成器的「目的地」闸门必须查整体通行**。

    事故原文（真机第五轮，逐字见 `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md`）：
    `SurfaceMovementProvider.appendBreakAndEnter` 的入口闸门只查 `canWalkThrough(level, to)`
    （**单格**谓词 —— 名字读起来像"人能走过去"，实际只查**一格**，而玩家占两格）就 `return`；
    而 TRAVERSE 的准入 `canTraverse → canStandCentered` 要求**头位**也可通行
    ⇒「脚位可通行 + 头位被挡」的目的地**两条边都不生成** ⇒ 一格高夹缝在**整张图里没有任何入边**。
    代价：掉落物落在夹缝里 ⇒ 20 000 节点搜爆 ⇒ `SEARCH_LIMIT` ⇒ 零重试退役（`collected=0/2`）；
    而真机上用户手挖的那**一格**（头位方块）正是缺失的边本该破的东西。

    **为什么必须门禁**：这类缺口是**完成性**缺口（"执行器/物理允许 ⇒ 生成器必须给出这条边"），
    而现有全部门禁只保证**健全性**（可规划 ⇒ 可执行：`MovementSpec.validateDisplacement` 硬抛、K-4）
    ⇒ 它**永远不会自己变红**，只能靠真人踩到。该缺口自 `da56fc0`（2026-09-09）起存在，
    期间同一函数还被复核并改过一次（只改了紧邻的那一行）。

    断言（改任一处 ⇒ 红）：
    ① `MovementHelper.bodyPassable` 存在，且真的是「脚位 + 头位」的合取；
    ② `appendBreakAndEnter` / `appendFall` / `appendPillar` / `appendPlaceStepAndTraverse`
       四个目的地闸门必须是整体通行（`bodyPassable(level, <目的地>)` 或「脚位 + 头位」合取）；
    ③ 历史原文 `if (MovementHelper.canWalkThrough(level, to)) {` 不许复活（= 缺口形态本身）。
    """
    problems = []
    provider = code_only((CORE / "search" / "SurfaceMovementProvider.java").read_text(encoding="utf-8"))
    helper = code_only((ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing"
                        / "MovementHelper.java").read_text(encoding="utf-8"))

    named_predicate = method_body(
        helper, "public static boolean bodyPassable(ServerLevel level, BlockPos foot) {")
    if not named_predicate:
        problems.append("`MovementHelper.bodyPassable` 不存在 ⇒ 目的地闸门没有可读的「整体通行」谓词"
                        "（单格谓词会被误当成「人能走过去」，D-374 就是这么发生的）")
    elif ("canWalkThrough(level, foot)" not in named_predicate
          or "canWalkThrough(level, foot.above())" not in named_predicate):
        problems.append("`bodyPassable` 不是「脚位 + 头位」的合取（口径漂了）")

    gates = (
        ("appendBreakAndEnter",
         "private static void appendBreakAndEnter(MovementContext context, ServerLevel level, BlockPos from,", "to"),
        ("appendFall",
         "private static void appendFall(MovementContext context, ServerLevel level, BlockPos from,", "edge"),
        ("appendPillar",
         "private static void appendPillar(MovementContext context, ServerLevel level, BlockPos from,", "to"),
        ("appendPlaceStepAndTraverse",
         "private static void appendPlaceStepAndTraverse(MovementContext context, ServerLevel level,", "to"),
    )
    for name, signature, var in gates:
        body = method_body(provider, signature)
        if not body:
            problems.append(f"找不到 `{name}`（结构变了 ⇒ 本规则要跟着改）")
            continue
        if f"bodyPassable(level, {var})" in body:
            continue
        if f"canWalkThrough(level, {var})" in body and f"canWalkThrough(level, {var}.above())" in body:
            continue
        problems.append(f"`{name}` 的目的地闸门**没有**查整体通行"
                        f"（既无 `bodyPassable(level, {var})`，也无「脚位 + 头位」合取）"
                        f"⇒ 会重演 D-374：脚位空、头位实的格子在整张图里没有入边")

    if "if (MovementHelper.canWalkThrough(level, to)) {" in provider:
        problems.append("历史原文 `if (MovementHelper.canWalkThrough(level, to)) {` 复活"
                        "（= D-374 的缺口形态本身）⇒ 一格高夹缝会再次没有任何入边")
    return problems


def rule_value_is_only_a_cost_component():
    """`D-329` §2.2 成本模型（用户 2026-09-20 三条裁定）：
    **「矿物价值优先级」只能是成本函数里的一个可配置分量**，不是独立模型、不是硬优先。

    裁定原文：① 价值**不是**单独模型，应存在于成本函数里；② **只在多目标种类任务**里启用；
    ③ **不许无条件挖最高级矿**。

    断言（改任一处 ⇒ 红）：
    ① `CostOptimalPolicy` 里**不出现方块名字符串**（`*_ore` 之类）—— 价值只能来自 `MineValueTable`；
    ② 价值启用必须经 `config.valueEnabled(multiKind)`，且 `multiKind` 来自"候选里 ≥2 种方块"；
    ③ 策略**不读世界**（不出现 `getBlockState` / `serverLevel()` / `BlockState`）—— 价值取自**候选快照**，
       世界变化由 `MineJob` 的身份复检处理（快照口径让策略可注入、可确定性判定）；
    ④ 成本读数**每次选择都重算**（`select(...)` 体内必须调 `provider.estimate(`）—— 成本场以 bot 当前位置
       为源 ⇒ 跨选择复用就是错的（用户裁定其三）；
    ⑤ 默认权重声明恰为 `0.0`（关闭）；⑥ `MineValueTable` 未登记方块**恰为 0**（不猜）。
    """
    problems = []
    base = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job"
    policy = (base / "policy" / "CostOptimalPolicy.java").read_text(encoding="utf-8")
    table = (base / "mine" / "MineValueTable.java").read_text(encoding="utf-8")
    config = (base / "mine" / "MineCostConfig.java").read_text(encoding="utf-8")

    if re.search(r'"[^"]*_ore"', policy):
        problems.append("`CostOptimalPolicy` 里出现了方块名字符串 ⇒ 价值变成了**硬优先**"
                        "（用户裁定①：价值只能在成本函数里）")
    if "config.valueEnabled(multiKind)" not in policy:
        problems.append("价值启用没有走 `config.valueEnabled(multiKind)` ⇒ 单种类任务也会被价值扰动"
                        "（用户裁定②：只在多目标种类任务里启用）")
    if "kinds.size() >= 2" not in policy:
        problems.append("`multiKind` 不是由「候选里 ≥2 种方块」判定的（判据必须现场可观测）")
    for banned in ["getBlockState", "serverLevel()", "BlockState"]:
        if banned in policy:
            problems.append("`CostOptimalPolicy` 里出现 `%s` ⇒ 策略**读了世界**"
                            "（价值必须取自候选快照；世界变化交给身份复检）" % banned)
    body = method_body(policy, "public Selection select(ServerPlayer bot, GoalSpec spec, "
                               "CandidateSet candidates) {")
    if not body:
        problems.append("找不到 `select(...)`（结构变了 ⇒ 本规则要跟着改）")
    elif "provider.estimate(" not in body:
        problems.append("`select(...)` 里没有调 `provider.estimate(` ⇒ 成本读数被跨选择复用"
                        "（成本场以 bot 当前位置为源，复用即错）")
    if not re.search(r"DEFAULT_VALUE_WEIGHT\s*=\s*0\.0D\s*;", config):
        problems.append("`MineCostConfig.DEFAULT_VALUE_WEIGHT` 不是 `0.0D` ⇒ 默认就打开了价值项"
                        "（用户裁定②的默认值是关闭）")
    if "return tier <= 0 ? 0.0D : tier / 3.0D;" not in table:
        problems.append("`MineValueTable` 未登记方块的归一化价值不是 `0.0D` ⇒ **在猜**（可能把未知当低级/高级）")
    return problems


def rule_world_refused_is_attributed():
    """`D-359`（2026-09-20，为真机地形实测补）：**被世界侧拒绝 ⇒ 顶层码必须说出来**。

    依据：`D-323` 附注一那批真机日志（FTB 认领拦下 4 次破坏）。第一层坑已修（"没发生的破坏被记成成功"
    ⇒ `BlockBreakSession` 用 `BlockState` 身份比对，报 `REFUSED`）；**第二层**是归因：
    若每次尝试都被拒而顶层只按"有没有挖到"算 ⇒ 退化成 `no_reachable_candidate` ⇒
    真机里读成"这里没矿"，而真相是"世界不许我们改"（处置完全不同：换目标 vs 换地方/要权限）。

    断言：① `WORLD_REFUSED_CODES` 家族在（含 `BREAK_REFUSED`）；② 归因里 `world_refused` 分支在；
    ③ 归因是**纯函数**且被 `deriveTopLevelReason` 调用（夹具喂得了合成码，接线也钉住）；
    ④ 触发时有**响亮**日志（真机里这是判断"换地方还是换目标"的唯一依据）。
    """
    problems = []
    mine = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
            / "MineJob.java").read_text(encoding="utf-8")
    family = method_body(mine, "private static final java.util.Set<String> WORLD_REFUSED_CODES = "
                               "java.util.Set.of(")
    if not family or "BREAK_REFUSED" not in family:
        problems.append("`WORLD_REFUSED_CODES` 家族不在（或缺 `BREAK_REFUSED`）⇒ 被拒会退化成『没矿』")
    body = method_body(mine, "public static String attributeFailure(String base, "
                             "java.util.List<String> attemptCodes) {")
    if not body:
        problems.append("归因不是纯函数 `attributeFailure(String, List<String>)`（夹具喂不了合成码）")
    else:
        if '"world_refused"' not in body:
            problems.append("归因里没有 `world_refused` 分支")
        if "attemptFailures" in body:
            problems.append("纯函数里读了实例字段 `attemptFailures` ⇒ 不再是纯函数")
    derived = method_body(mine, "private String deriveTopLevelReason(String base) {")
    if not derived or "attributeFailure(" not in derived:
        problems.append("`deriveTopLevelReason` 没有走纯函数（接线断了 ⇒ 判据测的不是生产路径）")
    if "world_refused\".equals(terminalReason)" not in mine:
        problems.append("触发 `world_refused` 时没有响亮日志（真机里分不清『换地方』还是『换目标』）")
    return problems


def rule_manual_test_lock_blocks_llm():
    """`D-360`（用户 2026-09-20："测试工具要阻断 LLM 接手"）：**手动占用锁必须在唯一的执行入口上生效**。

    为什么做成门禁：锁失效是**静默**的 —— LLM 插进来起了任务（或换掉了正在跑的任务），
    人却以为数据干净。而"锁生效"这件事只能靠结构断言咬住：
    ① 生产入口 `assignJob` 里**真的**查了锁；
    ② 放行口是**作用域内的一次性窗口**（`beginManualWindow`/`endManualWindow`，不新增 public 绕过 API）；
    ③ 窗口的**调用点只有一个**（= 手动测试命令）且必须在 `finally` 里关闭；
    ④ 拒绝必须**可见**（事件环 `REFUSED` + 日志）；
    ⑤ 手动实测的采集**收口在 `MineJob` 的终态**（四条终态路径共用一个出口，漏一条就会"跑完没数据"）；
    ⑥ `MineSurveyStats` 是**纯函数**（不读世界）。
    """
    problems = []
    bot_dir = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot"
    manager = (bot_dir / "BotManager.java").read_text(encoding="utf-8")
    lock = (bot_dir / "ManualTestLock.java").read_text(encoding="utf-8")
    job = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
           / "MineJob.java").read_text(encoding="utf-8")
    stats = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
             / "MineSurveyStats.java").read_text(encoding="utf-8")

    body = method_body(manager, "public static boolean assignJob(BotPlayer bot, ServerPlayer observer,")
    if not body:
        problems.append("找不到 `BotManager.assignJob`（结构变了 ⇒ 本规则要跟着改）")
    elif "ManualTestLock.refusalFor(" not in body:
        problems.append("生产入口 `assignJob` 没有查手动占用锁 ⇒ LLM 能在实测中途插进来起任务"
                        "（数据作废且现场难复盘）")
    if "beginManualWindow()" not in lock or "endManualWindow()" not in lock:
        problems.append("没有**作用域内的一次性放行窗口**（`beginManualWindow`/`endManualWindow`）"
                        "⇒ 要么锁形同虚设，要么得新增一条能被误用的 public 绕过入口")
    if "BotEventLog.record(" not in lock or '"REFUSED"' not in lock:
        problems.append("锁的拒绝没有进事件环（`REFUSED`）⇒ 拒绝是静默的，事后无法复盘")

    callers = []
    for path in (ROOT / "src" / "main" / "java").rglob("*.java"):
        if path.name == "ManualTestLock.java":
            continue
        # ⚠️ 剥掉注释再数（第一版把 BotManager 里**提到**窗口的注释也当调用点 ⇒ 假红）；
        # ⚠️ 数**出现次数**而不是文件数（第二版按文件名去重 ⇒ 同一文件里多加一处调用**假绿**）
        text = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
        text = re.sub(r"//[^\n]*", "", text)
        count = text.count("beginManualWindow()")
        if count:
            callers.append(f"{path.name}×{count}")
    if callers != ["BotCommand.java×1"]:
        problems.append("`beginManualWindow()` 的调用点必须恰好是手动测试命令一处（实测 %s）"
                        % (", ".join(callers) if callers else "0 处"))
    command = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "command"
               / "BotCommand.java").read_text(encoding="utf-8")
    if "beginManualWindow();" in command:
        window_at = command.find("beginManualWindow();")
        tail = command[window_at:window_at + 400]
        if "finally" not in tail or "endManualWindow();" not in tail:
            problems.append("手动窗口没有在 `finally` 里关闭 ⇒ 起任务抛异常就会把锁**永久**留在放行态")

    finish = method_body(job, "private Task.Status finish(Task.Status status) {")
    if not finish:
        problems.append("找不到 `MineJob.finish(...)`（终态收口）")
    elif "MineSurvey.reportTerminal(" not in finish:
        problems.append("`MineJob` 的终态收口没有打采集点 ⇒ 四条终态路径里漏一条就是『跑完没数据』")
    for banned in ["getBlockState", "serverLevel()", "BlockState"]:
        if banned in stats:
            problems.append("`MineSurveyStats` 里出现 `%s` ⇒ 统计不再是纯函数（夹具喂不了合成数据，口径也无法逐条断言）"
                            % banned)
    return problems



def rule_height_change_sweep():
    """`D-376`（2026-09-21 第八轮真机）：**高度变化必须查「过渡空间」—— 不能只证"站进去放得下"**。

    事故原文（真机，逐字见 `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` §13.2）：
    ```
    [R4 Session] segment_stall kind=segment_timeout to=632, 64, 93 botFoot=632, 65, 94
      pos=632.499,65.000,94.300 onGround=true delta≈0
      input=BotController[forward=1.00 strafing=0.00 jumping=false]
      toBlock=空气 headBlock=空气 supportBlock=圆石 segmentTicks=222     ← 同一形状两次，约 20 秒
    ```
    `toBlock`/`headBlock` 都是空气（= 闸门查过的那两层没问题），位置 **z=94.300**（包围盒北面正好贴住格
    边界）⇒ 挡住 bot 的方块只能在**身体扫掠盒会覆盖、而闸门不查的那一层**（`to.above(2)`）。

    口径差（这就是缺口）：`canDescend` 早就查了 `canSweepPlayer`（扫掠盒 `maxY = max(from,to)+1.8` ⇒ 含第 3 层），
    `AscendExecutionFactory` 也早就查 `from.up2`（`ASCEND_NO_HEADROOM`）；唯独
    `appendPlaceStepAndTraverse` 只查 `bodyPassable(to)`（两层）⇒ 计划里出现**物理上过不去**的段。

    断言（改任一处 ⇒ 红）：
    ① 规划侧 `appendPlaceStepAndTraverse` 必须查**扫掠空间**（`MovementHelper.canSweepPlayer(level, from, to)`）；
    ② 执行侧 `PlaceStepAndTraverseExecutionFactory.validate` 必须查**同一个谓词**（K-4 双向一致），
       且拒绝码是 `PLACE_STEP_AND_TRAVERSE_NO_SWEEP`（带几何，便于真机归因）；
    ③ 夹具必须存在，且它的**前提**断言两用例的 `canSweepPlayer` 取值恰好相反
       （`which == Case.TRANSITION_BLOCKED ? !sweep : sweep`）—— 而不是自己另写一份近似判据；
    ④ 夹具必须有「过渡被挡 ⇒ **不许**生成该边」的断言（`edge == null`）**和**反证
       「过渡通畅 ⇒ **必须**生成」（`edge != null`）—— 只有前者 = 永远绿；
    ⑤ 夹具必须断言执行工厂的拒绝码是 `NO_SWEEP`（若因别的原因拒绝，说明前提没立住）；
    ⑥ 步骤名 `place_step_descend_clearance` 必须注册进模块。
    """
    problems = []
    provider = ROOT / "src/main/java/com/dddgn/alice/pathing/core/search/SurfaceMovementProvider.java"
    factory = ROOT / "src/main/java/com/dddgn/alice/pathing/core/PlaceStepAndTraverseExecutionFactory.java"
    fixture = ROOT / "src/main/java/com/dddgn/alice/task/PlaceStepDescendClearanceCheckTask.java"
    module = ROOT / "src/main/java/com/dddgn/alice/task/check/modules/PathingModule.java"

    for path in (provider, factory, fixture, module):
        if not path.exists():
            problems.append(f"缺文件：{path.relative_to(ROOT)}")
    if problems:
        return problems

    provider_code = code_only(provider.read_text(encoding="utf-8"))
    append_body = method_body(provider_code, "private static void appendPlaceStepAndTraverse(")
    if "MovementHelper.canSweepPlayer(level, from, to)" not in append_body:
        problems.append("规划侧 `appendPlaceStepAndTraverse` 没查扫掠空间"
                        "（缺 `MovementHelper.canSweepPlayer(level, from, to)`）⇒ 高度变化只看两层，"
                        "真机就会顶在格边界原地走到段超时")

    factory_code = code_only(factory.read_text(encoding="utf-8"))
    validate_body = method_body(factory_code, "public ValidationResult validate(")
    if "MovementHelper.canSweepPlayer(context.level(), from, to)" not in validate_body:
        problems.append("执行侧 `PlaceStepAndTraverseExecutionFactory.validate` 没查同一个谓词"
                        "（K-4 双向一致）")
    if "PLACE_STEP_AND_TRAVERSE_NO_SWEEP" not in validate_body:
        problems.append("执行侧拒绝码不是 `PLACE_STEP_AND_TRAVERSE_NO_SWEEP`（真机归因要靠它）")

    fixture_code = code_only(fixture.read_text(encoding="utf-8"))
    if "which == Case.TRANSITION_BLOCKED ? !sweep : sweep" not in fixture_code:
        problems.append("夹具前提没断言「两用例的 `canSweepPlayer` 取值恰好相反」"
                        "（`which == Case.TRANSITION_BLOCKED ? !sweep : sweep`）⇒ 红了也说不清是不是夹具坏了")
    if "canSweepPlayer(level, from, to)" not in fixture_code:
        problems.append("夹具没有复用生产谓词 `MovementHelper.canSweepPlayer(`（自己另写近似判据 = 骗自己）")
    if not re.search(r'check\("⭐ ① 过渡空间被挡[\s\S]{0,200}?edge == null\)', fixture_code):
        problems.append("夹具没有「过渡被挡 ⇒ 不许生成该边」的断言（`edge == null` 在 TRANSITION_BLOCKED 分支内）")
    if not re.search(r'check\("⭐ ② 过渡空间通畅[\s\S]{0,200}?edge != null\)', fixture_code):
        problems.append("夹具缺反证「过渡通畅 ⇒ 必须生成该边」（`edge != null` 在 TRANSITION_CLEAR 分支内）"
                        "⇒ 只有前者的话永远绿")
    if "PLACE_STEP_AND_TRAVERSE_NO_SWEEP" not in fixture_code:
        problems.append("夹具没断言执行工厂的拒绝码是 `NO_SWEEP`")

    module_code = code_only(module.read_text(encoding="utf-8"))
    if '"place_step_descend_clearance"' not in module_code:
        problems.append("步骤名 `place_step_descend_clearance` 没注册进 `PathingModule`")
    return problems



def rule_hazard_not_task_gated():
    """`D-377`（2026-09-21 真机）：**危险处理不许挂在任务上** + **溺水前置分类**。

    真机原文（第八轮 17:56–17:57，bot `tango` 在水塘 `633,59,94`）：
    ```
    17:56:50.569 task_execution_terminal kind=RestoreScope terminal=CANCELLED_BY_USER
    17:56:50.571 任务在**不安全时刻被强制停止**（累计 1）        ← 延后停止落地 ⇒ task = null
    17:57:00.233 [SurvProbe] enter type=LOW_AIR duration=20 …    ← 危险处理"进得来"
    （之后 340 tick：`verdict` 再没打过、无任何 [Survival] 日志、air 300→-2、health 20→1.0）
    ```
    病根：`BotSession.tick(HazardState)` 里 `if (task == null) return;` 把 **`decide` 本身**跳过了
    ⇒ 空闲的 bot 溺水/着火/被埋时维生**零动作**。第二处：`classify` 只在 `air <= 0` 才算 `LOW_AIR`，
    而沉底期间是 `WATER_CONTACT`（**不是**软危险）⇒ 判决恒 `IGNORE` ⇒ 最长 15 秒白等。

    断言（改任一处 ⇒ 红）：
    ① `tick(HazardState)` 的 `task == null` 分支**不许直接 return**，必须调无任务危险处理
       （`tickHazardWithoutTask(hazard);` 与该 return **同段**，钉成一体避免"别处也有这个词"）；
    ② 无任务处理必须真的动作：`SurvivalSystem.decide(` + `SurvivalFloatTask` + `startSurvivalExit()`
       （三档里至少这两条动作路径在）；
    ③ 无任务处理**恒为**纯通行口径（`decide(bot, hazard, false)`）—— 无任务就没有写信封，
       不许动用逃生准备金（`D-241`）；
    ④ `SurvivalSystem` 必须有**溺水前置**常量与分类分支（阈值 + `isEyeInFluid` 同段），
       且判据拼写为 `airSupply() <= DROWN_PRECURSOR_AIR`；
    ⑤ 夹具必须存在且它断言的是**有效表达式**：`hasTask`（无任务前提）+ `FLOAT_UP`（前提判决）
       + `SurvivalFloatTask.AIR_SAFE`（自救成功判据）；同时必须有 `BotManager.hasTask` 这个只读读数
       （否则"无任务"只能靠陈旧 `taskKind` 猜）。
    """
    problems = []
    manager = ROOT / "src/main/java/com/dddgn/alice/bot/BotManager.java"
    survival = ROOT / "src/main/java/com/dddgn/alice/survival/SurvivalSystem.java"
    fixture = ROOT / "src/main/java/com/dddgn/alice/task/SurvivalIdleDrownCheckTask.java"
    module = ROOT / "src/main/java/com/dddgn/alice/task/check/modules/SurvivalModule.java"
    for path in (manager, survival, fixture, module):
        if not path.exists():
            problems.append(f"缺文件：{path.relative_to(ROOT)}")
    if problems:
        return problems

    mgr = code_only(manager.read_text(encoding="utf-8"))
    tick_body = method_body(mgr, "private void tick(HazardState hazard)")
    if not tick_body:
        problems.append("找不到 `BotSession.tick(HazardState hazard)`")
    else:
        if "tickHazardWithoutTask(hazard);" not in tick_body:
            problems.append("`task == null` 分支没调无任务危险处理 ⇒ 空闲 bot 溺水时维生零动作（`D-377` 病根）")
        if not re.search(r"if \(task == null\) \{[\s\S]{0,600}?tickHazardWithoutTask\(hazard\);[\s\S]{0,80}?return;",
                         tick_body):
            problems.append("无任务处理没接在 `if (task == null) { … return; }` 这一段里"
                            "（钉成一体：只在别处出现 `tickHazardWithoutTask` 不算）")
    handler = method_body(mgr, "private void tickHazardWithoutTask(HazardState hazard)")
    if not handler:
        problems.append("缺 `tickHazardWithoutTask(HazardState)`")
    else:
        if "SurvivalSystem.decide(bot, hazard, false)" not in handler:
            problems.append("无任务处理没走**纯通行**判决（`decide(bot, hazard, false)`；"
                            "无任务=没有写信封，不许动用逃生准备金 —— D-241）")
        if not re.search(r"case FLOAT_UP -> \{[\s\S]{0,2000}?new com\.dddgn\.alice\.task\.SurvivalFloatTask\(",
                         handler):
            problems.append("无任务处理的 `FLOAT_UP` 档里没有上浮自救（溺水无出口时唯一能做的动作）"
                            "—— 注意：只查「文件里出现过 SurvivalFloatTask」会被**沉底档**那次调用顶包（实测漏过）")
        if "startSurvivalExit();" not in handler:
            problems.append("无任务处理里没有「走向出口」（有可规划出口时应当直接去，无任务可中断）")
    if "public static boolean hasTask(BotPlayer bot)" not in mgr:
        problems.append("缺 `BotManager.hasTask` 只读读数 ⇒ 夹具只能靠**陈旧** `taskKind` 猜有无任务"
                        "（第八轮探针就是这样被骗过去的）")

    sv = code_only(survival.read_text(encoding="utf-8"))
    if "public static final int DROWN_PRECURSOR_AIR" not in sv:
        problems.append("缺 `DROWN_PRECURSOR_AIR` 常量（溺水前置阈值）")
    if re.search(r"DROWN_PRECURSOR_AIR[\s\S]{0,120}?return HazardType\.LOW_AIR", sv):
        problems.append("`DROWN_PRECURSOR_AIR` 被写进了**共享分类表** `classify` —— 第一版就是这么写的，"
                        "结果电池步 `survival_exit`（那相位故意 air=5 + 眼在水里）被判成真溺水 ⇒ "
                        "`FLOAT_UP` 分支 `complete(..., SURVIVAL_INTERRUPTED)` **中断了整轮电池**（no_verdict）。"
                        "这一档必须只对**无任务**生效")
    if not re.search(r"isEyeInFluid\(net\.minecraft\.tags\.FluidTags\.WATER\)[\s\S]{0,200}?DROWN_PRECURSOR_AIR",
                     handler):
        problems.append("无任务处理里没有「沉底提前自救」那一档（眼在水里 + `air ≤ DROWN_PRECURSOR_AIR`）"
                        "⇒ 空闲 bot 沉底仍要白等到空气耗尽")

    fx = code_only(fixture.read_text(encoding="utf-8"))
    for token, why in (
            ("BotManager.hasTask(probe)", "夹具没断言「探针 bot 无任务」这个前提"),
            ("SurvivalSystem.Verdict.FLOAT_UP", "夹具没断言前提判决是 FLOAT_UP"),
            ("SurvivalFloatTask.AIR_SAFE", "夹具没断言「自救成功」（空气回到 AIR_SAFE）"),
            ("sawTask && firstTaskAir > 0", "夹具没断言「自救在空气还够时就开始了」"
                                             "（钉有效表达式 `sawTask && firstTaskAir > 0`；"
                                             "只钉标识符会被别处的引用顶包 —— 实测漏过）"),
            ("isEyeInFluid(FluidTags.WATER)", "夹具没断言「眼睛在水里」（沉底的事实前提）")):
        if token not in fx:
            problems.append(f"{why}（缺 `{token}`）")
    mod = code_only(module.read_text(encoding="utf-8"))
    if '"survival_idle_drown"' not in mod:
        problems.append("步骤名 `survival_idle_drown` 没注册进 `SurvivalModule`")
    return problems


def rule_collect_goal_standable():
    """`D-375`（2026-09-21 第六轮真机 + 存档取证）：**「够得着的可站格」必须与真实拾取盒同一谓词；
    一个都没有时不许规划，更不许退回「物品自身格」**。

    事故原文（真机，逐字见 `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` §10）：
    `CollectDropsTask.pickupGoalFor` 的「够得着吗」是**粗判**（逐轴 1.2），而真实拾取盒的逐轴上界是
    `0.125 + 0.3 + 1.0 = 1.425` ⇒ 存在一段「**真够得着、却被规划期否掉**」的位置；真机上那件落物
    正好落在里面 ⇒ 搜索一圈没找到 ⇒ 静默 `best == null → return itemCell` 把**站不住的物品自身格**
    当目标（**一行日志都没有**）⇒ 走到那种格只能靠同层 `BREAK_AND_ENTER` ⇒ 唯一可行路线是
    **19 段计划 / 破 6 格 / 约 10 秒** ⇒ 烧满 200 tick 簇预算 ⇒ 最坏 `collected=0/13`。

    断言（改任一处 ⇒ 红）：
    ① `withinPickupReach` 必须是**真实拾取盒**（`AABB` + 与 `inPickupRange` **共享的**外扩常量 +
       `item.getBoundingBox()` 相交）；
    ② 旧粗判形态 `Math.abs(cell.getX() + 0.5D - item.getX()) <= 1.2D` 不许复活；
    ③ `inPickupRange` 必须复用同一对外扩常量（不许再出现写死的 `inflate(1.0D, 0.5D, 1.0D)`）；
    ④ `pickupGoalFor` 不许「找不到就退回物品自身格」（`best == null ? itemCell : best` 形态）；
    ⑤ 找不到时必须**如实拒绝 + 留日志**（`goal == null ⇒ return false` + `BotLog.warn`）；
    ⑥ 规划前必须有硬不变式「收集请求的 `GoalFoot` 必须可站」（`if (!isStandableCell(anchor))`）；
    ⑦ 决策层信号必须在：`PICKUP_DETOUR` / `PICKUP_SLOW`，且「改造地形」的判据必须是**世界改动运行账
       的增量**（`TaskMetrics.snapshot().delta(簇基线).worldChanges()`）—— 自检实测：读"走位执行过的
       Movement 类型"会漏（`executedMovementTypes()` 要等某一段**成功**才追加，而"为捡一件东西挖一格"
       常常正好是最后一段 ⇒ 破了 2 格石墙、事件计数仍是 0）；运行账是"真的扣了写入预算那一刻"记的；
    ⑧ 夹具必须**复用生产谓词**（`CollectSlotApproachCheckTask` 里出现 `CollectDropsTask.withinPickupReach(`）
       —— 夹具自己另写一份近似判据 = 自己骗自己。
    """
    problems = []
    task_file = ROOT / "src/main/java/com/dddgn/alice/task/CollectDropsTask.java"
    text = code_only(task_file.read_text(encoding="utf-8"))
    shared_inflate = "inflate(PICKUP_INFLATE_XZ, PICKUP_INFLATE_Y, PICKUP_INFLATE_XZ)"

    if "PICKUP_INFLATE_XZ = 1.0D" not in text or "PICKUP_INFLATE_Y = 0.5D" not in text:
        problems.append("拾取盒外扩常量 `PICKUP_INFLATE_XZ = 1.0D` / `PICKUP_INFLATE_Y = 0.5D` 不存在"
                        "（口径的唯一来源没了 ⇒ 规划期与执行期必然各写一份）")

    reach = method_body(text, "static boolean withinPickupReach(BlockPos cell, ItemEntity item) {")
    if not reach:
        problems.append("找不到 `CollectDropsTask.withinPickupReach`（结构变了 ⇒ 本规则要跟着改）")
    else:
        if "AABB" not in reach or "intersects(" not in reach or "item.getBoundingBox()" not in reach:
            problems.append("`withinPickupReach` 不是真实拾取盒（缺 `AABB` / 相交 / `item.getBoundingBox()`）"
                            "⇒ 会重演 D-375：真够得着却被规划期否掉")
        if shared_inflate not in reach:
            problems.append("`withinPickupReach` 没有用共享外扩常量 ⇒ 与 `inPickupRange` 的口径会漂移")

    if ("Math.abs(cell.getX() + 0.5D - item.getX())" in text
            or "Math.abs(cell.getZ() + 0.5D - item.getZ())" in text):
        problems.append("旧粗判（逐轴 1.2 的格中心比较）复活 ⇒ 真实上界 1.425 与它之间那段位置"
                        "会再次被否掉（= D-375 的缺口形态本身）")

    in_range = method_body(text, "private boolean inPickupRange(ItemEntity item) {")
    if not in_range:
        problems.append("找不到 `inPickupRange`（结构变了 ⇒ 本规则要跟着改）")
    elif shared_inflate not in in_range:
        problems.append("`inPickupRange` 没有复用同一对外扩常量 ⇒ 规划期与执行期的「够得着」各写一份")
    if "inflate(1.0D, 0.5D, 1.0D)" in text:
        problems.append("仍存在写死的 `inflate(1.0D, 0.5D, 1.0D)` ⇒ 外扩量有了第二份定义（会漂移）")

    goal = method_body(text, "private BlockPos pickupGoalFor(ItemEntity item, BlockPos itemCell) {")
    if not goal:
        problems.append("找不到 `pickupGoalFor`（结构变了 ⇒ 本规则要跟着改）")
    else:
        if "best == null ? itemCell" in goal:
            problems.append("`pickupGoalFor` 又退回「物品自身格」= D-375 的静默兜底形态"
                            "（那一格正是「站不住」才要搜索的）")
        if "return best;" not in goal:
            problems.append("`pickupGoalFor` 的返回值不是裸 `best`（找不到就该返回 null ⇒ 调用方如实收尾）")

    norm = method_body(text, "private boolean normalizeAnchor(List<ItemEntity> members) {")
    if not norm:
        problems.append("找不到 `normalizeAnchor`（结构变了 ⇒ 本规则要跟着改）")
    else:
        if "if (goal == null)" not in norm or "return false;" not in norm:
            problems.append("`normalizeAnchor` 在找不到可站格时没有「如实拒绝」"
                            "（`goal == null ⇒ return false`）⇒ 调用方会拿不可站的目标去规划")
        if "BotLog.warn(" not in norm:
            problems.append("找不到可站格时**一行日志都没有**（旧行为）⇒ 现场不可取证")

    if "if (!normalizeAnchor(members)) {" not in text:
        problems.append("规划前没有处理「锚点规范化失败」（`if (!normalizeAnchor(members))`）")
    if "if (!isStandableCell(anchor)) {" not in text:
        problems.append("缺少硬不变式「收集请求的 GoalFoot 必须可站」（`if (!isStandableCell(anchor))`）"
                        "⇒ 站不住的目标 = 要求寻路挖进去")

    if 'DecisionEvents.emit(bot, "PICKUP_DETOUR"' not in text:
        problems.append("决策层信号 `PICKUP_DETOUR` 不存在 ⇒「为捡一件东西在改造地形」对决策层不可见")
    if 'DecisionEvents.emit(bot, "PICKUP_SLOW"' not in text:
        problems.append("决策层信号 `PICKUP_SLOW` 不存在 ⇒ 簇内那 10 秒（真机烧掉 200 tick）对决策层不可见")
    changes = method_body(text, "private int worldChangesInCluster() {")
    if not changes:
        problems.append("找不到 `worldChangesInCluster`（结构变了 ⇒ 本规则要跟着改）")
    elif ("TaskMetrics.snapshot()" not in changes or ".delta(" not in changes
          or ".worldChanges()" not in changes):
        problems.append("`PICKUP_DETOUR` 的判据不是「世界改动运行账增量」"
                        "（`TaskMetrics.snapshot().delta(...).worldChanges()`）"
                        "⇒ 会重演自检实测：真破了 2 格石墙，`detour_events` 仍是 0")
    if "worldChangesBefore = com.dddgn.alice.bot.TaskMetrics.snapshot();" not in text:
        problems.append("换簇时没有重取「世界改动」基线 ⇒ 上一簇（甚至上一步）的改动会算进下一簇")

    fixture = ROOT / "src/main/java/com/dddgn/alice/task/CollectSlotApproachCheckTask.java"
    if not fixture.exists():
        problems.append("夹具 `CollectSlotApproachCheckTask` 不存在 ⇒ D-375 没有判据（只能靠真人踩到）")
    elif ("premiseGoalReachable = CollectDropsTask.withinPickupReach(goal, item);"
          not in code_only(fixture.read_text(encoding="utf-8"))):
        # ⚠️ 判据要钉**有效表达式**，不能只钉"文件里出现过这个名字"：
        # 实测（2026-09-21 注入 ⑧）把正例那条前提换成夹具自写的 `roughReach(...)` 时，
        # 文件里**别处**（起点/最近站格那两条）还留着同名调用 ⇒ 只查"出现过"的门禁**静默绿** ✗
        problems.append("夹具的「真够得着」前提**不是**生产谓词算出来的"
                        "（缺 `premiseGoalReachable = CollectDropsTask.withinPickupReach(goal, item);`）"
                        "⇒ 它用另一套判据自己骗自己（与「可规划即可执行」同一条纪律）")

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
    value = rule_value_is_only_a_cost_component()
    refused = rule_world_refused_is_attributed()
    lock = rule_manual_test_lock_blocks_llm()
    kinds = rule_kind_filter_before_cluster()
    clearance = rule_clearance_never_eats_task_target()
    breakcost = rule_cost_includes_break()
    support = rule_support_and_cluster_order()
    inplace = rule_mine_in_place_before_walk()
    contract = rule_movement_contract_agreement()
    searchbudget = rule_search_budget_is_tick_aware()
    detour = rule_standing_point_detour_bounded()
    scan = rule_scan_advances_every_select()
    writecaps = rule_write_caps_default_open_protection_kept()
    ticksearch = rule_tick_search_account_enforced()
    approachbound = rule_approach_plans_bounded()
    bodyclear = rule_edge_destination_body_clearance()
    collectgoal = rule_collect_goal_standable()
    sweepclearance = rule_height_change_sweep()
    hazardnotgated = rule_hazard_not_task_gated()
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
    for line in value:
        print(f"[D-329·价值只是成本分量] {line}")
    for line in refused:
        print(f"[D-359·世界侧拒绝要归因] {line}")
    for line in lock:
        print(f"[D-360·实测锁要挡LLM] {line}")
    for line in kinds:
        print(f"[D-361·种类分配] {line}")
    for line in clearance:
        print(f"[D-362·清障不吃任务目标] {line}")
    for line in breakcost:
        print(f"[D-363·break进成本] {line}")
    for line in support:
        print(f"[D-364·垫方块与簇顺序] {line}")
    for line in inplace:
        print(f"[D-365·视线内就地挖] {line}")
    for line in contract:
        print(f"[D-366·移动契约一致] {line}")
    for line in searchbudget:
        print(f"[D-369·搜索预算同 tick 量级] {line}")
    for line in detour:
        print(f"[D-370·不许绕远] {line}")
    for line in scan:
        print(f"[D-371·每次选择都推进扫描] {line}")
    for line in writecaps:
        print(f"[D-372·默认不限+权限层保留] {line}")
    for line in ticksearch:
        print(f"[A1·每tick搜索总账] {line}")
    for line in approachbound:
        print(f"[A2·模式B穷举有界] {line}")
    for line in bodyclear:
        print(f"[D-374·目的地整体通行] {line}")
    for line in hazardnotgated:
        print(f"[D-377·危险处理不挂任务] {line}")
    for line in sweepclearance:
        print(f"[D-376·高度变化查过渡空间] {line}")
    for line in collectgoal:
        print(f"[D-375·收集目标可站] {line}")
    ok = (not k4 and not k5 and not s8 and not walk and not np and not risk and not speech
          and not perm and not death and not dmg and not prog and not s10 and not f1
          and not prog_default and not j5 and not r2 and not r2p2 and not r2p3 and not ring
          and not noperm and not loop and not bwg and not d344 and not attr and not s3 and not s5 and not intent and not clusters and not value and not refused and not lock and not kinds and not clearance and not breakcost and not support and not inplace and not contract and not searchbudget and not detour and not scan and not writecaps and not ticksearch and not approachbound and not bodyclear and not collectgoal and not sweepclearance and not hazardnotgated)
    print(f"KERNEL_PREDICATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"工厂谓词漂移={len(k4)} / 死状态={len(k5)} / 死字段复活={len(s8)} / 行走无界={len(walk)} / 失败当进度={len(np)} / 风险画像未接={len(risk)}"
          f" / 编排器步边界={len(r2)} / 步清单={len(r2p2)} / 步边界对齐={len(r2p3)} / 结构化归因={len(attr)} / 搜索受限≠没有={len(s3)} / 扫描记忆无位置={len(s5)} / 意图先于可挖性={len(intent)} / 簇只做几何={len(clusters)} / 价值只是成本分量={len(value)} / 世界侧拒绝要归因={len(refused)} / 实测锁要挡LLM={len(lock)} / 种类分配={len(kinds)} / 清障不吃任务目标={len(clearance)} / break进成本={len(breakcost)} / 垫方块与簇顺序={len(support)} / 视线内就地挖={len(inplace)} / 移动契约一致={len(contract)} / 搜索预算={len(searchbudget)} / 不许绕远={len(detour)} / 扫描推进={len(scan)} / 写上限={len(writecaps)} / 每tick搜索总账={len(ticksearch)} / 模式B穷举有界={len(approachbound)} / 目的地整体通行={len(bodyclear)} / 收集目标可站={len(collectgoal)} / 高度变化查过渡空间={len(sweepclearance)} / 危险处理不挂任务={len(hazardnotgated)}"
          f"（K4-P1/K5-P1/S8-P1/W-P1/NP-P1/S6-P1/F4-P1/R2-P1/R2-P2/R2-P3/M4-P1 —— 见各规则头部的注释）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
