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
    }
    problems = []
    for path, label in targets.items():
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        if "RiskSwitches." + "descendOvershootGuard()" in text:
            problems.append(f"{path.name}（{label}）又直接读全局开关 RiskSwitches.descendOvershootGuard()"
                            f"（S-6：必须读 RiskProfile.of(bot).descendOvershootGuard()）")
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


def main() -> int:
    k4 = rule_k4()
    k5 = rule_k5()
    s8 = rule_s8()
    walk = rule_walk_budget()
    prog = rule_progress_signal()
    risk = rule_risk_profile()
    speech = rule_speech_channel()
    perm = rule_permission_service()
    death = rule_death_keeps_data()
    dmg = rule_damage_observed()
    prog = rule_progress_switch()
    for line in k4:
        print(f"[K4·谓词统一] {line}")
    for line in k5:
        print(f"[K5·状态生产] {line}")
    for line in s8:
        print(f"[S8·死字段] {line}")
    for line in walk:
        print(f"[W·行走预算] {line}")
    for line in prog:
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
    ok = not k4 and not k5 and not s8 and not walk and not prog and not risk and not speech and not perm and not death and not dmg and not prog
    print(f"KERNEL_PREDICATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"工厂谓词漂移={len(k4)} / 死状态={len(k5)} / 死字段复活={len(s8)} / 行走无界={len(walk)} / 失败当进度={len(prog)} / 风险画像未接={len(risk)}"
          f"（K4-P1/K5-P1/S8-P1/W-P1/NP-P1/S6-P1/F4-P1 —— 见各规则头部的注释）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
