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

# ⭐ 2026-09-22：`D-369 §六` 的待办（"预算是否还能更紧（如 50 ms）：等 `[Search] 超 tick 预算`
# 日志积累真实数据再定"）已按真机数据决断 —— 87 次撞线 · 平均 189 ms · 合计 16.4 s ·
# `Can't keep up 42 ticks behind` ⇒ 上限从 250 收到 **60**（= 一个 tick 的量级 + 余量）。
# 反向对照：把 `DEFAULT_MAX_MILLIS` 改回 200 ⇒ 本断言红（卡顿回归必须重新登记理由）。
SEARCH_BUDGET_CEILING_MILLIS = 60
ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "pathing" / "core"

# `A1′`：全仓终态闩锁站点的**人口下限**（= `task/MineTask.java` + `D-410` 统一的 6 处）。
# 低于它 ⇒ `rule_terminal_latch_replays_status` 会红：判据不许在"没有站点"时静默通过。
LATCH_SITES_MIN = 7

# ⭐ P4（2026-09-24）：**tick 负载预算的人口规则** —— 全仓所有"毫秒预算常量"必须要么 ≤ `SEARCH_BUDGET_CEILING_MILLIS`
# （= 一个 tick 的量级），要么在这里**具名登记理由与复核触发**。出处：`docs/OPEN_ITEMS_LEDGER.md §11 P4`
# （点名 3 个当时未受门禁保护的常量：`SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK=400`（= 8 tick）、
# `PathRequest.ESCAPE_MAX_MILLIS=100`、`SurvivalSystem.PRECHECK_MAX_MILLIS=20`）。
# 双向防漂移：登记项必须**真的存在**且**数值一致**（改名/改值不清登记 ⇒ 红）。
TICK_BUDGET_EXEMPTIONS = {
    "DEFAULT_MAX_MILLIS_PER_TICK": (
        400,
        "跨 tick 摊销的**每 tick 切片额度**：搜索按 slice 让出，烧额度的次数由 SearchTickBudget 计账（A1 主判据）。"
        "⚠️ 实测 09-24 客户端轮 mine 循环 ~198 ms/轮、仍在该额度内（= 单 tick 超支 4 倍）。"
        "复核触发：下一次收口客户端测试若仍见 Can't keep up ⇒ 下调到 ≤60 并复跑 CORE 逐步 diff。",
    ),
    "ESCAPE_MAX_MILLIS": (
        100,
        "逃生/脱困请求专用（PathRequest 的逃生半边），单次上限 2 tick；"
        "复核触发：出现一次逃生请求在 tick 线程上造成 Can't keep up ⇒ 收到 ≤60 或改成按 slice 摊销。",
    ),
    # ⚠️ 本项**不是执行额度**，是"一次搜索算不算烧预算"的**分类阈值**（A1 主判据的计量口径）：
    # 规则靠人口扫描才发现它（`docs/OPEN_ITEMS_LEDGER.md §11 P4` 只点名了 3 个）—— 登记在这里
    # 是为了让"毫秒常量"这件事**没有暗处**，而不是说它允许跑 2 个 tick。
    "EXPENSIVE_SEARCH_MILLIS": (
        100,
        "分类阈值：`elapsedMillis ≥ 此值` 才计一次 expensiveSearch（A1 主判据的计量口径），不是执行额度；"
        "复核触发：A1 主判据改成按节点数/切片数判定时，同步本值并在电池复跑 CORE 逐步 diff。",
    ),
    # ⚠️ 本项**完全不是预算**：它是**原版告警阈值**（`MinecraftServer.run`：单 tick 落后 >2000 ms 才打
    # `Can't keep up!`）—— 台架把它写成常量只是为了**把判据的判读口径钉在代码里**
    # （`D-429`：红臂 = 只有"闸门全关 + 历史形态"那格单 tick ≥ 此值；台架实测 2578 ms）。
    "KEEP_UP_WARN_MILLIS": (
        2000,
        "判读口径常量（原版 `Can't keep up!` 的告警阈值 = 单 tick 落后 >2000 ms），不是任何执行额度；"
        "复核触发：若将来改用别的「卡顿判据」（如客户端帧时间），同步本值并重跑 `tick_budget_bench`。",
    ),
}
# 人口下限：低于它 ⇒ 本规则红（判据不许在"扫不到任何常量"时静默通过）。
TICK_BUDGET_SITES_MIN = 4

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



def rule_terminal_latch_replays_status():
    """`A1`（`D-410`，2026-09-23）：**终态闩锁必须回放状态，不许硬编码 `DONE`**。

    形状（`D-175` 的正解 = `task/MineTask.java`）：任务/作业自己存 `Task.Status terminalStatus` 字段，
    `tick()` 开头 `if (terminalStatus != null) return terminalStatus;`，首次非 RUNNING 时写入。

    为什么必须门禁化：违反 `D-178` 的形状（`if (terminated) return Task.Status.DONE;`）会让
    **终态是 FAILED 的任务在下一个 tick 变成 DONE** ⇒ 电池"只按 `status == DONE` 记 PASS"
    ⇒ **失败被记成通过**（`D-408 §二` 的 `CleanupWrappedTask` 是同一族）。全仓曾**同时存在 6 处**。

    两条断言（**带人口**，防空集真 —— `Z4` 的教训）：
      ① 任何 `src/` 文件都不得出现"终态守卫直接返回硬编码 DONE/FAILED"的形状；
      ② 声明了 `Task.Status terminalStatus` 字段的文件，必须同时有回放行；
      ③ 闩锁站点数不得低于 `LATCH_SITES_MIN`（否则判据在"没有站点"时静默通过）。

    ⚠️ **两处必须避开的读错（本规则第一版各踩一次，2026-09-23）**：
      · 注释里**提到旧写法**不算违规 ⇒ 必须连**块注释/javadoc**一起去掉（共享的 `code_only` 只去 `//`）；
      · `terminalStatus` 这个名字**有两处含义**：本规则管的是 `Task.Status terminalStatus` **字段**，
        而 `TaskExecutionRecord.terminalStatus()` 是**另一个**东西（`task/mining/MiningSceneFixture.java`
        只是在调那个方法）⇒ 必须按**字段声明**匹配，不能按"出现过这个词"匹配。
    """
    alice = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"

    def strip_comments(text: str) -> str:
        """去 `//` 行注释 + `/* … */` 块注释（保留行数，便于报行号）。"""
        out, i, n = [], 0, len(text)
        in_block = False
        while i < n:
            if in_block:
                if text.startswith("*/", i):
                    in_block = False
                    i += 2
                else:
                    if text[i] == "\n":
                        out.append("\n")
                    i += 1
                continue
            if text.startswith("/*", i):
                in_block = True
                i += 2
                continue
            if text.startswith("//", i):
                while i < n and text[i] != "\n":
                    i += 1
                continue
            out.append(text[i])
            i += 1
        return "".join(out)

    problems = []
    hardcoded = re.compile(r"if\s*\(\s*(?:terminated|done|finished)\s*\)\s*"
                           r"return\s+Task\.Status\.(?:DONE|FAILED)\s*;")
    # ⚠️ 字段写法有两种：`Task.Status terminalStatus;`（多数）与 `Status terminalStatus;`（`MineTask`
    # 简写）⇒ 第二版只认前者 ⇒ **漏守 `MineTask`**（本规则的正解本身！）。
    field = re.compile(r"private\s+(?:final\s+)?(?:Task\.)?Status\s+terminalStatus\s*;")
    # 回放行的**真实形状**带花括号且跨行（`if (terminalStatus != null) {\n return terminalStatus;\n }`）
    # ⇒ 第一版只认单行 ⇒ 7 个站点全被误报（对着真代码验才发现）。
    replay = re.compile(r"if\s*\(\s*terminalStatus\s*!=\s*null\s*\)\s*\{?\s*"
                        r"return\s+terminalStatus\s*;")
    latch_sites = []
    for path in sorted(alice.rglob("*.java")):
        code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for m in hardcoded.finditer(code):
            line_no = code[:m.start()].count("\n") + 1
            problems.append(f"{path.name}:{line_no} 终态守卫**直接返回硬编码状态**"
                            f"（`{m.group(0).strip()}`）⇒ 终态是 FAILED 时下一 tick 会变 DONE"
                            f"（违反 D-178）；正解见 task/MineTask.java 的 terminalStatus 回放")
        if field.search(code):
            if replay.search(code):
                latch_sites.append(path.name)
            else:
                problems.append(f"{path.name} 声明了 `Task.Status terminalStatus` 字段，"
                                f"但缺 `if (terminalStatus != null) return terminalStatus;` 回放行"
                                f"⇒ 闩锁没接上（终态会被下一 tick 覆盖）")
    if len(latch_sites) < LATCH_SITES_MIN:
        problems.append(f"终态闩锁站点只剩 {len(latch_sites)} 个（< {LATCH_SITES_MIN}）"
                        f"⇒ 本判据退化成空集真：真源={sorted(latch_sites)}"
                        f"（已知站点 = `MineTask` + `D-410` 统一的 6 处 = 7）")
    return problems


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


def rule_mine_job_search_limit_backoff():
    """
    `Y`（2026-09-22 真机卡顿根因）：挖掘作业对 `search_incomplete` 必须**跨 tick 摊销**，
    不许"每 tick 换一个候选再撞一次"（真机 30 s 内 33 次 × 196 ms ⇒ 4-5 TPS + 追补跳帧）。

    断言（改任一处 ⇒ 红）：
    ① `MineJob` 里有冷却常量且 **> 1 tick**（`=0/1` 等于没摊销）；
    ② 有"连续 N 次 ⇒ 如实收工"的上限常量，且**用在** `tick()` 的判定里；
    ③ `tick()` 里存在把 `Phase.SELECT` 挡在冷却之外的调用点（`inSearchLimitCooldown(`）；
    ④ 成功分支会把连续计数清零（否则本作业会被几个难目标误判成"该走开"）。
    """
    problems = []
    job = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "job" / "mine"
           / "MineJob.java").read_text(encoding="utf-8")
    m_cool = re.search(r"SEARCH_LIMIT_COOLDOWN_TICKS\s*=\s*([0-9]+)", job)
    if not m_cool:
        problems.append("找不到 `SEARCH_LIMIT_COOLDOWN_TICKS` ⇒ `search_incomplete` 没有跨 tick 摊销")
    elif int(m_cool.group(1)) <= 1:
        problems.append("`SEARCH_LIMIT_COOLDOWN_TICKS=%s` ≤ 1 ⇒ 等于没有摊销（每 tick 仍会撞墙）"
                        % m_cool.group(1))
    m_max = re.search(r"MAX_CONSECUTIVE_SEARCH_LIMITED\s*=\s*([0-9]+)", job)
    if not m_max:
        problems.append("找不到 `MAX_CONSECUTIVE_SEARCH_LIMITED` ⇒ 连续撞墙没有上限")
    elif "searchLimitedStorm(consecutiveSearchLimited)" not in job:
        problems.append("`searchLimitedStorm(...)` 没有被调用 ⇒ 上限只是摆设（不会真的收工）")
    if "inSearchLimitCooldown(bot.serverLevel().getGameTime()," not in job:
        problems.append("`tick()` 里没有把 `Phase.SELECT` 挡在冷却外的调用点 ⇒ 冷却没生效")
    if "consecutiveSearchLimited = 0;" not in job:
        problems.append("成功分支没有清零连续计数 ⇒ 难目标会把整局判成「该走开」")
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



def rule_tick_load_budget_declared():
    """**P4（2026-09-24）：tick 负载预算必须"声明 + 有界 + 有复核触发"**（尺子线，`docs/OPEN_ITEMS_LEDGER.md §11 P4`）。

    背景：内核把"跨 tick 摊销 / 有界搜索 / 批量扫描"的额度写成毫秒常量，但**并非每个常量都被门禁看住** ——
    `rule_search_budget_is_tick_aware` 只钉了 `CorePathPlanner.DEFAULT_MAX_MILLIS` 那一个点（真机掉刻的直接病灶）。
    P4 要的是**人口口径**：任何毫秒预算都跑不掉 —— 要么 ≤ 一个 tick 的量级，要么**具名登记**理由 + 复核触发。

    断言（改任一处 ⇒ 红）：
    ① 扫描人口 ≥ {@link #TICK_BUDGET_SITES_MIN}（解析崩塌 / 常量被改名藏起来 ⇒ 红，不许静默通过）；
    ② 超过 {@link #SEARCH_BUDGET_CEILING_MILLIS} 的常量必须有登记（否则报出"≈ 独占几个 tick"）；
    ③ 登记表**双向**：登记项必须真的存在、数值必须与代码一致（改值不清登记 ⇒ 红）；
    ④ 每条登记的理由必须 ≥20 字**且写出「复核触发」**（没有复核条件的豁免 = 永久豁免，等于没门禁）。
    """
    problems = []
    found = {}
    pattern = re.compile(r"static\s+final\s+(?:long|int)\s+([A-Z][A-Z0-9_]*(?:MILLIS|_MS)[A-Z0-9_]*)\s*=\s*([0-9_]+)L?\s*;")
    for path in sorted((ROOT / "src" / "main" / "java").rglob("*.java")):
        text = code_only(path.read_text(encoding="utf-8"))
        for m in pattern.finditer(text):
            name = m.group(1)
            value = int(m.group(2).replace("_", ""))
            found.setdefault(name, []).append((value, path))
    print("[P4·tick负载预算] 人口=%d 个毫秒常量（额度 %d / 登记豁免 %d）"
          % (len(found), len(found) - len([n for n in found if n in TICK_BUDGET_EXEMPTIONS]),
             len(TICK_BUDGET_EXEMPTIONS)))
    if len(found) < TICK_BUDGET_SITES_MIN:
        problems.append("只扫到 %d 个毫秒预算常量（下限 %d）⇒ 判据的人口不成立（常量被改名/搬走？同步本规则）"
                        % (len(found), TICK_BUDGET_SITES_MIN))
    for name, sites in sorted(found.items()):
        for value, path in sites:
            if value <= SEARCH_BUDGET_CEILING_MILLIS:
                continue
            if name not in TICK_BUDGET_EXEMPTIONS:
                problems.append("`%s=%d ms`（%s）未登记：超过 %d ms（一个 tick 的量级）≈ 单次可独占 %d 个 tick "
                                "⇒ 要么收到 ≤%d，要么在 `TICK_BUDGET_EXEMPTIONS` 写明理由 + 复核触发"
                                % (name, value, path.name, SEARCH_BUDGET_CEILING_MILLIS,
                                   max(1, value // 50), SEARCH_BUDGET_CEILING_MILLIS))
    for name, (declared, reason) in sorted(TICK_BUDGET_EXEMPTIONS.items()):
        if name not in found:
            problems.append("登记表里的 `%s` 在代码里不存在（改名了？登记与代码必须双向一致）" % name)
            continue
        actual = {v for v, _ in found[name]}
        if declared not in actual:
            problems.append("登记表写 `%s=%d ms`，代码实际是 %s（改值必须同步登记，否则豁免会漂成假账）"
                            % (name, declared, " / ".join(str(v) for v in sorted(actual))))
        if len(reason) < 20 or "复核触发" not in reason:
            problems.append("登记 `%s` 的理由不合格：必须 ≥20 字且写明「复核触发」（没有复核条件的豁免 = 永久豁免）"
                            % name)
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

    # ⚠️ `Z3`（2026-09-23）搬了位置：默认回退**收成一个出处** `effectiveCaps(...)`（原先 6 个读者
    # 各回退各的，`P1-a` 只修好 2 个）⇒ 本臂改为断言"属性在 `effectiveCaps` 里 + 闸门走它"，
    # 语义与 `D-372` 完全相同（默认不限），只是不再散在方法体里。
    effective = code_only(method_body(budget, "private static Caps effectiveCaps("))
    if "CAPS.getOrDefault(scopeId, Caps.UNBOUNDED)" not in effective:
        problems.append("`effectiveCaps(...)` 的默认回退不是 `Caps.UNBOUNDED` ⇒ 默认格数上限又回来了"
                        "（`D-372`：保护区外世界修改放开，只留时间预算防空转）")
    for method in ("public static Verdict consumeBreak(", "public static Verdict consumePlace("):
        body = code_only(method_body(budget, method))
        if "effectiveCaps(" not in body:
            problems.append("`%s` 没走唯一的额度出处 `effectiveCaps(...)` ⇒ 闸门与其它读者不同源"
                            "（`Z3`：同一个量的多个副本就是 `P1-a` 那个真机根因）" % method.split()[3])
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
    ④ 无任务档的「水下自救」判据必须是**状态**（`isEyeInFluid(FluidTags.WATER)`）而**不是余量**
       —— 不许出现 `getAirSupply() <= …` 这类阈值（`D-380`：第九轮客户端实测老阈值要等约 10 秒才动手，
       用户看到的是「没有浮出来」）；同时 `DROWN_PRECURSOR_AIR` **必须仍不在**共享分类表 `classify` 里，
       且**也不许**回到无任务档（它现在只是夹具的判别基准）；
    ⑤ 夹具必须存在且它断言的是**有效表达式**：`hasTask`（无任务前提）+ `FLOAT_UP`（前提判决）
       + `SurvivalFloatTask.AIR_SAFE`（自救成功判据）+ `firstTaskAir > SurvivalSystem.DROWN_PRECURSOR_AIR`
       （**空气还很满就得动** = `D-380` 口径的可红判据）；同时必须有 `BotManager.hasTask` 这个只读读数
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
        problems.append("缺 `DROWN_PRECURSOR_AIR` 常量（`D-380` 之后它是夹具的判别基准：自救必须发生在"
                        "air 仍 > 它的时候）")
    if re.search(r"DROWN_PRECURSOR_AIR[\s\S]{0,120}?return HazardType\.LOW_AIR", sv):
        problems.append("`DROWN_PRECURSOR_AIR` 被写进了**共享分类表** `classify` —— 第一版就是这么写的，"
                        "结果电池步 `survival_exit`（那相位故意 air=5 + 眼在水里）被判成真溺水 ⇒ "
                        "`FLOAT_UP` 分支 `complete(..., SURVIVAL_INTERRUPTED)` **中断了整轮电池**（no_verdict）。"
                        "这一档必须只对**无任务**生效")
    # ⭐ `D-380`：无任务档的判据是**状态**（眼在水里）而不是**余量**（air 还剩多少）。
    # 反向断言：那一档里不许再出现阈值判据（`D-380` 客户端实测：老阈值要等约 10 秒 ⇒ 用户看到「没浮出来」）。
    if "isEyeInFluid(net.minecraft.tags.FluidTags.WATER)" not in handler:
        problems.append("无任务处理里没有「人在水下 ⇒ 上浮」那一档（`isEyeInFluid(FluidTags.WATER)`）"
                        "⇒ 空闲 bot 在水里仍要白等")
    submerged_branch = handler[handler.find("isEyeInFluid(net.minecraft.tags.FluidTags.WATER)"):]
    submerged_branch = submerged_branch[:submerged_branch.find("}")] if "}" in submerged_branch else submerged_branch
    if "getAirSupply()" in submerged_branch or "DROWN_PRECURSOR_AIR" in submerged_branch:
        problems.append("无任务档的「水下自救」又挂上了**空气余量阈值**（`getAirSupply()` / "
                        "`DROWN_PRECURSOR_AIR`）—— `D-380` 已经删掉它：对没有任务的 bot 不存在"
                        "「正常潜水」，判据必须是**状态**（眼在水里），否则会再出现「等 10 秒才浮」"
                        "（第九轮客户端实测就是这样）")

    fx = code_only(fixture.read_text(encoding="utf-8"))
    for token, why in (
            ("BotManager.hasTask(probe)", "夹具没断言「探针 bot 无任务」这个前提"),
            ("SurvivalSystem.Verdict.FLOAT_UP", "夹具没断言前提判决是 FLOAT_UP"),
            ("SurvivalFloatTask.AIR_SAFE", "夹具没断言「自救成功」（空气回到 AIR_SAFE）"),
            ("firstEyeOutAir > SurvivalSystem.DROWN_PRECURSOR_AIR",
             "夹具没断言「头第一次露出水面时空气还很满」（`D-380` 口径；钉有效表达式 "
             "`firstEyeOutAir > SurvivalSystem.DROWN_PRECURSOR_AIR` —— 阈值一旦复活，这个数必然 ≈ 旧阈值）"),
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
    ① 相交本体只能是**一个** `reachesFrom(playerBox, item)`（`item.getBoundingBox()` + 共享外扩常量 +
       `intersects`），且 `withinPickupReach`（建模）与 `inPickupRange`（执行）**都必须调它**
       —— 3-b/`D-381` 起它还被抽成包可见 `static`，夹具可两边都用生产定义断言；
    ② 旧粗判形态 `Math.abs(cell.getX() + 0.5D - item.getX()) <= 1.2D` 不许复活；
    ③ 不许再出现写死的 `inflate(1.0D, 0.5D, 1.0D)`，也不许任一侧自己写 `intersects(`；
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

    # ⭐ 3-b / `D-381`（2026-09-21）：相交**本体**抽成了 `reachesFrom(playerBox, item)`
    # （`withinPickupReach` 用"格中心盒子"、`inPickupRange` 用 bot 的真实盒子，**同一个相交谓词**）
    # ⇒ 断言随之加强：本体只能有一个，两边都**必须调它**，不许再各自写一份相交。
    shared_reach = method_body(text, "static boolean reachesFrom(AABB playerBox, ItemEntity item) {")
    if not shared_reach:
        problems.append("找不到 `CollectDropsTask.reachesFrom`（相交本体没了 ⇒ 规划期与执行期会各写一份"
                        "「够得着」）")
    else:
        if "intersects(" not in shared_reach or "item.getBoundingBox()" not in shared_reach:
            problems.append("`reachesFrom` 不是真实拾取盒（缺 相交 / `item.getBoundingBox()`）"
                            "⇒ 会重演 D-375：真够得着却被规划期否掉")
        if shared_inflate not in shared_reach:
            problems.append("`reachesFrom` 没有用共享外扩常量 ⇒ 与外扩口径会漂移")

    reach = method_body(text, "static boolean withinPickupReach(BlockPos cell, ItemEntity item) {")
    if not reach:
        problems.append("找不到 `CollectDropsTask.withinPickupReach`（结构变了 ⇒ 本规则要跟着改）")
    elif "reachesFrom(" not in reach or "AABB" not in reach:
        problems.append("`withinPickupReach` 没有走「格中心 → 玩家盒 → 共享相交本体 `reachesFrom`」"
                        "⇒ 规划期与执行期的「够得着」各写一份")

    if ("Math.abs(cell.getX() + 0.5D - item.getX())" in text
            or "Math.abs(cell.getZ() + 0.5D - item.getZ())" in text):
        problems.append("旧粗判（逐轴 1.2 的格中心比较）复活 ⇒ 真实上界 1.425 与它之间那段位置"
                        "会再次被否掉（= D-375 的缺口形态本身）")

    in_range = method_body(text, "private boolean inPickupRange(ItemEntity item) {")
    if not in_range:
        problems.append("找不到 `inPickupRange`（结构变了 ⇒ 本规则要跟着改）")
    elif "reachesFrom(bot.getBoundingBox()" not in in_range:
        problems.append("`inPickupRange` 没有复用共享相交本体 `reachesFrom(bot.getBoundingBox(), …)`"
                        "⇒ 规划期与执行期的「够得着」各写一份")
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


def rule_head_blocked_route_closure():
    """`D-378`（2026-09-21）：**`survey/27 §3 #1` 的收口判据** —— 「88 段绕远」是不是那一行谓词造成的。

    勘测侧原文链条（`survey/27-当前核心问题与依赖关系图-20260921.md` §2.3）：
        直挖路需要破入边 → 一行谓词只查脚位、不查头位 → 20% 的破入边没有生成
        → A* 找不到直挖路 ⇒ 只能绕 88 段 → 88 段太贵 ⇒ 撞搜索预算 ⇒ `SEARCH_LIMIT`
    前两段已由 `D-374`（`break_enter_head_blocked` 距离 1 能力 + `edge_completeness` 边集差集）证明；
    **后两段一直没证**（`P3` 复测因真机存档被覆盖 ⇒ 一次真实搜索都没发生却输出 `reproduced=true`）。
    `D-378` 用**当场自建的基岩隧道**收口：同一条请求、同一份几何、同一个预算，只把「旧闸门会拒掉的
    那一类边」滤掉，路径就从 ≤`EXPECTED_DIRECT_MOVEMENTS` 段变成 ≥`DETOUR_MIN_MOVEMENTS` 段。

    断言（改任一处 ⇒ 红）：
    ① 反臂必须是**夹具自带的 provider 包装**（`implements MovementProvider`；过滤条件是
       「`BREAK_AND_ENTER` ∧ `canWalkThrough(level, toFoot())`」；经 `new CorePathPlanner(provider)` 生效）
       —— 不许改生产代码、也不许另写一份近似判据；
    ② 反臂**不许**自己重写生产闸门（夹具里不许出现 `bodyPassable(`）—— 它只能**过滤生成结果**；
    ③ 「滤掉了多少」必须进判据（`dropped > 0`）：为 0 就是「没测到」，
       不是「测到没差别」（`silent-measurement-failure`，`P3` 的教训）；
    ④ 三个读数必须是**有效表达式**（不是标识符）：生产臂 `movements().size() <= EXPECTED_DIRECT_MOVEMENTS`
       且 `headBlockedBreaks == SLIT_COUNT`；旧谓词臂 `movements().size() >= DETOUR_MIN_MOVEMENTS`
       且 `headBlockedBreaks == 0`；紧预算臂 `!plan.reached()` 且 `nodesExpanded() >= BUDGET_TIGHT.maxNodes()`；
    ⑤ 收口判据本体必须在：`post.plan.movements().size() * 4 <= ample.plan.movements().size()`
       与 `post.plan.totalCost() < ample.plan.totalCost()`（后者是**前提**：绕远若反而更便宜，
       那「绕远」就不是被谓词逼的，而是被成本逼的）；
    ⑥ 场景隔离必须自断言：`offPlaneTypes(...)` 的 `.isEmpty()` 进 `check(...)`
       （起终点附近不许有上升／下落／搭柱／放置类边，否则段数不由几何决定）；
    ⑦ 步骤名 `head_blocked_route_closure` 必须注册进 `PathingModule` **并**登记进电池归属表
       （`RegressionBatteryTask` 的 `CURATION`；漏登记 = 构建红）。
    """
    problems = []
    fixture = ROOT / "src/main/java/com/dddgn/alice/task/HeadBlockedRouteClosureCheckTask.java"
    module = ROOT / "src/main/java/com/dddgn/alice/task/check/modules/PathingModule.java"
    battery = ROOT / "src/main/java/com/dddgn/alice/task/RegressionBatteryTask.java"
    step = "head_blocked_route_closure"

    for path in (fixture, module, battery):
        if not path.exists():
            problems.append(f"缺文件：{path.relative_to(ROOT)}")
    if problems:
        return problems

    # ⚠️ 模块级 `code_only` 只剥 `//` 行注释 ⇒ 这里必须**连块注释一起剥**：
    # 夹具的 javadoc 里会**引用**被禁写法（`bodyPassable(`）讲等价性，那是注释不是代码。
    raw_fixture = fixture.read_text(encoding="utf-8")
    code = code_only(re.sub(r"/\*.*?\*/", "", raw_fixture, flags=re.S))

    wrapper = method_body(code, "private void filterLegacy(MovementContext context, BlockPos from,")
    if "implements MovementProvider" not in code or not wrapper:
        problems.append("反臂不是**夹具自带的 provider 包装**（缺 `implements MovementProvider` / "
                        "`filterLegacy`）⇒ 「旧谓词」这一臂没有可控的实现")
    else:
        if "MovementType.BREAK_AND_ENTER" not in wrapper:
            problems.append("反臂的过滤条件没有按**边类型**限定（缺 `MovementType.BREAK_AND_ENTER`）"
                            "⇒ 会误滤别的边，读数不再是「旧边集」")
        if "MovementHelper.canWalkThrough(context.level(), movement.toFoot())" not in wrapper:
            problems.append("反臂没有滤「脚位可通行」那一类（缺 "
                            "`MovementHelper.canWalkThrough(context.level(), movement.toFoot())`）"
                            "⇒ 与旧闸门 `canWalkThrough(level, to)` 不等价")
        if "dropped++" not in wrapper:
            problems.append("反臂不数「滤掉了几条」（缺 `dropped++`）⇒ 分不清「没测到」与「测到没差别」")
    if "bodyPassable(" in code:
        problems.append("夹具里出现了 `bodyPassable(` ⇒ 反臂在**重写生产闸门**，而不是过滤生成结果"
                        "（重写 = 自己骗自己）")
    if "new CorePathPlanner(provider)" not in code:
        problems.append("反臂没有真正生效（缺 `new CorePathPlanner(provider)`）⇒ 两臂其实是同一条路径")

    expression_pins = (
        ("生产臂：直挖路段数上界", "plan.movements().size() <= EXPECTED_DIRECT_MOVEMENTS"),
        ("生产臂：恰好 SLIT_COUNT 段属于旧谓词拒掉的那一类", "headBlockedBreaks == SLIT_COUNT"),
        ("旧谓词臂：段数下界（绕远被量出来）", "plan.movements().size() >= DETOUR_MIN_MOVEMENTS"),
        ("旧谓词臂：不许出现那类边", "headBlockedBreaks == 0"),
        ("紧预算臂：预算真的是绑定点", "plan.nodesExpanded() >= BUDGET_TIGHT.maxNodes()"),
        ("收口判据：修复前/后段数比", "post.plan.movements().size() * 4 <= ample.plan.movements().size()"),
        ("收口前提：直挖路更便宜", "post.plan.totalCost() < ample.plan.totalCost()"),
        ("反臂自证：真的滤掉了边", "dropped > 0"),
        ("场景隔离：不许有平面外的能力（上/下/柱/放）",
         "startOffPlane.isEmpty() && deepOffPlane.isEmpty()"),
    )
    for label, expression in expression_pins:
        if expression not in code:
            problems.append(f"{label} 的断言不见了（缺有效表达式 `{expression}`）⇒ 该判据会静默失效")

    if '"' + step + '"' not in code_only(module.read_text(encoding="utf-8")):
        problems.append(f"步骤名 `{step}` 没注册进 `PathingModule`")
    battery_code = code_only(battery.read_text(encoding="utf-8"))
    if 'Map.entry("' + step + '"' not in battery_code:
        problems.append(f"步骤名 `{step}` 没登记进 `RegressionBatteryTask` 的归属表（`CURATION`）"
                        "⇒ 构建时自校验会红（这是设计：漏登记不许静默漏测）")
    return problems


def rule_break_traverse_footing():
    """`D-379`（2026-09-21 第八轮真机）：**「破坏通行」破掉的中间列是 bot 要踩过去的一格 —— 它必须立得住**。

    真机原文（逐字见 `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` §13.3）：
    ```
    14:21:59.894 走到 632, 64, 95（踩在自己刚放的 632,63,95 上）
    14:22:00.044 [WRITE] break 632, 64, 94   ← BREAK_AND_TRAVERSE from=632,64,95 → to=632,64,93 的「中间格」
    14:22:00.947 维生监测 hazard=WATER_CONTACT pos=632, 62, 94   ← 掉进水里（掉了约 2 格）
    ```
    落点 `632,62,94` = **中间列正下方**、水面 ⇒ bot 是从**中间列**掉下去的（它穿过了 `y=63` ⇒
    中间列在脚位层是个**没有地板的洞**）。而**规划侧与执行侧都不查中间列自己有没有地板**：
    执行器是直着走过去的（`driveTowardTarget`，位移 2 格；`PlanRouteSafety` 也把 `mid`/`mid.above()`
    算作「bot 身体会占据的格子」）⇒ 中间列立不住时，「破坏中间列之后走到 `to`」这个承诺是**假的**。

    断言（改任一处 ⇒ 红）：
    ① 规划侧 `appendBreakAndTraverse` 必须查中间列落脚（`if (!MovementHelper.canWalkOn(level, mid))`）；
    ② 执行侧 `BreakAndTraverseExecutionFactory.validate` 必须查**同一个谓词**（K-4 双向一致），
       拒绝码 `BREAK_AND_TRAVERSE_NO_MID_SUPPORT`（带几何，便于真机归因）；
    ③ 归因计数必须**两侧都记**（`PathingStats.record(code)` + `recordTotal(code)`）
       —— 只记 `COUNTS` 时夹具拿不到增量、"拒绝了但拒绝了多少"就无从取证；
    ④ 夹具必须复用生产谓词（出现 `MovementHelper.canWalkOn(level, mid)`），且三用例的该谓词取值
       恰好是三档（`which == Case.MID_FLOOR_SOLID ? midFooting : !midFooting`）；
    ⑤ 夹具必须有「悬空 ⇒ **不许**生成该边」（`edge == null`）**和**反证「立在地板上 ⇒ **必须**生成」
       （`edge != null`）—— 只有前者 = 永远绿；
    ⑥ 夹具必须断言执行工厂的拒绝码前缀 `BREAK_AND_TRAVERSE_NO_MID_SUPPORT`；
    ⑦ 夹具必须断言两个计数键的增量（`break_traverse_no_mid_support_fluid` / `…_dry`）；
    ⑧ 步骤名 `break_traverse_footing` 必须注册进模块**并**登记进电池归属表。
    """
    problems = []
    provider = ROOT / "src/main/java/com/dddgn/alice/pathing/core/search/SurfaceMovementProvider.java"
    factory = ROOT / "src/main/java/com/dddgn/alice/pathing/core/BreakAndTraverseExecutionFactory.java"
    fixture = ROOT / "src/main/java/com/dddgn/alice/task/BreakTraverseFootingCheckTask.java"
    module = ROOT / "src/main/java/com/dddgn/alice/task/check/modules/PathingModule.java"
    battery = ROOT / "src/main/java/com/dddgn/alice/task/RegressionBatteryTask.java"
    step = "break_traverse_footing"

    for path in (provider, factory, fixture, module, battery):
        if not path.exists():
            problems.append(f"缺文件：{path.relative_to(ROOT)}")
    if problems:
        return problems

    provider_code = code_only(provider.read_text(encoding="utf-8"))
    append_body = method_body(provider_code, "private static void appendBreakAndTraverse(")
    if not append_body:
        problems.append("找不到 `appendBreakAndTraverse`（结构变了 ⇒ 本规则要跟着改）")
    else:
        if "MovementHelper.canWalkOn(level, mid)" not in append_body:
            problems.append("规划侧 `appendBreakAndTraverse` 没查**中间列落脚**"
                            "（缺 `MovementHelper.canWalkOn(level, mid)`）⇒ 会重演 D-379："
                            "破开中间列之后那格没有地板，bot 直接掉下去（真机 = 掉进水里淹死）")
        for key in ("break_traverse_no_mid_support_fluid", "break_traverse_no_mid_support_dry"):
            if key not in append_body:
                problems.append(f"规划侧没有记归因计数 `{key}` ⇒ 「拒了多少 / 拒的是哪一类」无从取证")

    factory_code = code_only(factory.read_text(encoding="utf-8"))
    validate_body = method_body(factory_code, "public ValidationResult validate(")
    if not validate_body:
        problems.append("找不到 `BreakAndTraverseExecutionFactory.validate`（结构变了 ⇒ 本规则要跟着改）")
    else:
        if "MovementHelper.canWalkOn(context.level(), mid)" not in validate_body:
            problems.append("执行侧没有查**同一个谓词**（K-4 双向一致）"
                            "⇒ 会出现「可规划不可执行 / 可执行但计划是假的」")
        if "BREAK_AND_TRAVERSE_NO_MID_SUPPORT" not in validate_body:
            problems.append("执行侧拒绝码不是 `BREAK_AND_TRAVERSE_NO_MID_SUPPORT`（真机归因要靠它）")

    code = code_only(re.sub(r"/\*.*?\*/", "", fixture.read_text(encoding="utf-8"), flags=re.S))
    if "MovementHelper.canWalkOn(level, mid)" not in code:
        problems.append("夹具没有复用生产谓词 `MovementHelper.canWalkOn(level, mid)`"
                        "（自己另写近似判据 = 骗自己）")
    expression_pins = (
        ("夹具前提：三用例的被测谓词取值是三档",
         "which == Case.MID_FLOOR_SOLID ? midFooting : !midFooting"),
        ("夹具：悬空+水 ⇒ 不许生成该边", "edge == null"),
        ("夹具：悬空+浅坑 ⇒ 同样不许生成该边", "edge == null"),
        ("夹具反证：立在地板上 ⇒ 必须生成该边", "edge != null"),
        ("夹具：归因计数（流体）", "fluidDelta == 1 && dryDelta == 0"),
        ("夹具：归因计数（干的）", "dryDelta == 1 && fluidDelta == 0"),
        ("夹具：执行工厂拒绝码前缀", "verdict.failureCode().startsWith(FACTORY_CODE)"),
    )
    for label, expression in expression_pins:
        if expression not in code:
            problems.append(f"{label} 的断言不见了（缺有效表达式 `{expression}`）⇒ 该判据会静默失效")
    if not re.search(r'check\("⭐ ①[\s\S]{0,300}?edge == null\);', code):
        problems.append("夹具缺「悬空+水 ⇒ 不许生成该边」的断言（`edge == null` 在 ① 分支内）")
    if not re.search(r'check\("⭐ ③ 反证[\s\S]{0,300}?edge != null\);', code):
        problems.append("夹具缺反证「立在地板上 ⇒ 必须生成该边」（`edge != null` 在 ③ 分支内）⇒ 只有前者的话永远绿")

    if '"' + step + '"' not in code_only(module.read_text(encoding="utf-8")):
        problems.append(f"步骤名 `{step}` 没注册进 `PathingModule`")
    if 'Map.entry("' + step + '"' not in code_only(battery.read_text(encoding="utf-8")):
        problems.append(f"步骤名 `{step}` 没登记进 `RegressionBatteryTask` 的归属表（`CURATION`）")
    return problems


def rule_break_cost_state_penalty():
    """`D-385`（2026-09-21）：**规划期的挖掘成本必须等于执行侧真值 —— 含 vanilla 的两项状态惩罚**。

    事实（可核，javap 实测 1.20.1 官方映射字节码）：执行侧每 tick 累加
    `BlockState.getDestroyProgress`（`BlockBreakSession.java`），其分子 `Player.getDigSpeed` 里有两项除法
    —— `isEyeInFluid(WATER) && !hasAquaAffinity ⇒ f /= 5.0f`、`!onGround() ⇒ f /= 5.0f`。
    旧 `estimateBreakTicks` 只等于「站在地上 + 眼不在水里」那一档 ⇒ 眼在水里乐观 5×、
    眼在水里且离地（水下挖矿/落体挖）乐观 25× ⇒ 规划器把水下挖掘当陆地速度 ⇒ **该放不放、过度挖**。

    断言（改任一处 ⇒ 红）：
    ① `estimateBreakTicks` 必须**乘上**状态惩罚（调用唯一来源 `stateBreakPenaltyMultiplier`）；
    ② 该惩罚函数必须用 vanilla 的**谓词**（`isEyeInFluid` / `hasAquaAffinity` / `onGround()`）与 `5.0D` 因子；
    ③ **不许长出第二份口径**：源码里同时出现 `hasAquaAffinity` 与 `5.0D` 的文件只允许是 `BlockInteraction.java`；
    ④ 执行侧仍走 vanilla（`BlockBreakSession` 里 `progress += … getDestroyProgress(…)`）——
       否则夹具的「估计 == 真值」判据量的是别的东西；
    ⑤ 夹具必须**与 vanilla 真值**比对（出现 `getDestroyProgress` 与 `1.0D / (double) progress`）；
    ⑥ 夹具的规划器差额必须**锚在 vanilla 真值**上（`(surface.vanillaTicks() - dry.vanillaTicks())`）——
       锚在 estimate 上则两边一起变小、永远绿（自洽≠正确）；
    ⑦ 夹具必须覆盖四个 `(眼在水里, 在地面)` 组合，并断言 vanilla 自己给出的倍数 1/5/25/5（防"量了个幽灵"）；
    ⑧ 步骤名 `mining_water_break_cost` 必须注册进模块**并**登记进电池归属表。
    """
    problems = []
    interaction = ROOT / "src/main/java/com/dddgn/alice/action/BlockInteraction.java"
    session = ROOT / "src/main/java/com/dddgn/alice/action/BlockBreakSession.java"
    fixture = ROOT / "src/main/java/com/dddgn/alice/task/MiningWaterBreakCostCheckTask.java"
    module = ROOT / "src/main/java/com/dddgn/alice/task/check/modules/MiningModule.java"
    battery = ROOT / "src/main/java/com/dddgn/alice/task/RegressionBatteryTask.java"
    step = "mining_water_break_cost"

    for path in (interaction, session, fixture, module, battery):
        if not path.exists():
            problems.append(f"缺文件：{path.relative_to(ROOT)}")
    if problems:
        return problems

    interaction_code = code_only(interaction.read_text(encoding="utf-8"))
    estimate_body = method_body(interaction_code, "public static double estimateBreakTicks(")
    if not estimate_body:
        problems.append("找不到 `estimateBreakTicks`（结构变了 ⇒ 本规则要跟着改）")
    elif "stateBreakPenaltyMultiplier(bot)" not in estimate_body:
        problems.append("`estimateBreakTicks` 没有乘状态惩罚 ⇒ 眼在水里/离地时估计值乐观 5×~25×"
                        "（水下挖矿被算成陆地速度 ⇒ 该放不放）")
    penalty_body = method_body(interaction_code, "private static double stateBreakPenaltyMultiplier(")
    if not penalty_body:
        problems.append("找不到 `stateBreakPenaltyMultiplier`（D-385 的唯一来源）")
    else:
        for token, why in (
                ("isEyeInFluid", "没查「眼在水里」（vanilla 惩罚项 ①）"),
                ("hasAquaAffinity", "没查潮涌能量（有 Aqua Affinity 时 vanilla 不给那 5×）"),
                ("onGround()", "没查「离地」（vanilla 惩罚项 ②）"),
                ("hasSupportBelow", "没查「脚下有没有耐久支撑」——只用 `onGround()` 标志位会让"
                                    "「传送到起点那一 tick」的全图破坏边误罚 5×（CORE `break_course` 实测翻路线）"),
                ("5.0D", "没写 ×5 因子（与 vanilla `f /= 5.0f` 不一致）"),
        ):
            if token not in penalty_body:
                problems.append(f"状态惩罚函数 {why}（缺 `{token}`）")
        if penalty_body.count("5.0D") < 2:
            problems.append("状态惩罚函数只乘了一次 5.0D ⇒ 缺「眼在水里**且**离地 = 25×」那一档")

    # ③ 不许长出第二份口径（判据 = 「Aqua Affinity」与「×/÷ 5.0」这对组合出现在同一个文件里；
    #    夹具里出现 5.0D 只是**断言里的期望倍数**，不构成第二份实现 —— 故看算式而不是看常数）
    #（夹具被排除：那里出现 `vanilla/5.0D` 是**断言里的期望值**，不是第二份实现；
    # 生产侧任何文件长出新口径都会被抓到）
    penalty_factor = re.compile(r"[*/]=?\s*5\.0")
    for path in (ROOT / "src/main/java").rglob("*.java"):
        if "Check" in path.name or "Fixture" in path.name or "Diagnostic" in path.name:
            continue
        text = code_only(path.read_text(encoding="utf-8"))
        if path != interaction and "hasAquaAffinity" in text and penalty_factor.search(text):
            problems.append(f"{path.relative_to(ROOT)} 里也有一份「Aqua Affinity × 5.0」⇒ "
                            "惩罚口径长出了第二份（D-385 要求唯一来源）")

    support_body = method_body(interaction_code, "private static boolean hasSupportBelow(")
    if not support_body:
        problems.append("找不到 `hasSupportBelow`（D-385 的「离地」几何判据）")
    else:
        for token, why in (
                ("MovementHelper.footCell(", "没用统一的脚位格口径（`blockPosition()` 在半砖上不是脚位格，D-226）"),
                ("isSolidForPlacement(", "没查「实心、非流体、有碰撞形状」的支撑（用别的近似谓词会与放置判据分叉）"),
        ):
            if token not in support_body:
                problems.append(f"`hasSupportBelow` {why}（缺 `{token}`）")

    session_code = code_only(session.read_text(encoding="utf-8"))
    if "progress += current.getDestroyProgress(bot, level, pos)" not in session_code:
        problems.append("`BlockBreakSession` 不再逐 tick 累加 vanilla `getDestroyProgress` ⇒ "
                        "执行侧的真值来源变了，夹具的「估计 == 真值」判据会量错东西")

    fixture_code = re.sub(r"/\*.*?\*/", "", fixture.read_text(encoding="utf-8"), flags=re.S)
    for label, expression in (
            ("夹具：与 vanilla 真值比对", "getDestroyProgress(bot, level, MID)"),
            ("夹具：真值 = 进度倒数", "1.0D / (double) progress"),
            ("夹具：规划器差额锚在 vanilla 真值（不是 estimate）",
             "(surface.vanillaTicks() - dry.vanillaTicks())"),
            ("夹具：估计值与期望值的容差断言", "relative(reading.estimate(), expected) <= REL_TOL"),
            ("夹具：**故意偏离**那一档（陈旧标志位）的期望口径", "spec.aliceDeviation()"),
            ("夹具：陈旧标志位下成本必须等于基线（CORE `break_course` 那一幕）",
             "relative(staleFlag.estimate(), dry.estimate()) <= REL_TOL"),
            ("夹具：陈旧标志位那一档的自然标志位是**在地面**（人工置位才造出陈旧 false）",
             "staleFlag.naturalOnGround() && !staleFlag.onGround()"),
            ("夹具：(否,是) 组合", "!dry.eyeInWater() && dry.onGround()"),
            ("夹具：(是,是) 组合", "submergedGround.eyeInWater() && submergedGround.onGround()"),
            ("夹具：(是,否) 组合", "submergedFloat.eyeInWater() && !submergedFloat.onGround()"),
            ("夹具：(否,否) 组合", "!airborne.eyeInWater() && !airborne.onGround()"),
            ("夹具：vanilla 自己就是 ×5（防量幽灵）",
             "nearRatio(submergedGround.vanillaTicks() / baseVanilla, 5.0D)"),
            ("夹具：vanilla 自己就是 ×25（防量幽灵）",
             "nearRatio(submergedFloat.vanillaTicks() / baseVanilla, 25.0D)"),
            ("夹具：生产边生成器被真的问过", "new SurfaceMovementProvider().appendCandidates(context, FROM, out)"),
            ("夹具：陆地挖仍比放便宜（反证：修复不许把陆地也搞贵）",
             "dryCost < CostModel.PLACE_ONE_BLOCK_COST"),
            ("夹具：水里挖已比放贵", "wetCost > CostModel.PLACE_ONE_BLOCK_COST"),
    ):
        if expression not in fixture_code:
            problems.append(f"{label} 的断言不见了（缺有效表达式 `{expression}`）⇒ 该判据会静默失效")

    if '"' + step + '"' not in code_only(module.read_text(encoding="utf-8")):
        problems.append(f"步骤名 `{step}` 没注册进 `MiningModule`")
    if 'Map.entry("' + step + '"' not in code_only(battery.read_text(encoding="utf-8")):
        problems.append(f"步骤名 `{step}` 没登记进 `RegressionBatteryTask` 的归属表（`CURATION`）")
    return problems


# ⭐⭐ 尺子 2「准入来源单一」（`D-395` P1；形状来自 `survey/28 §6.5` + `D-394` 教训）
#
# 病灶（`D-394`）：规划侧 `MovementHelper.canAscend` 是布尔、执行侧 `AscendExecutionFactory`
# **另一份** ⇒ 两份必然漂移 ⇒ 「规划必出边、执行必拒、重规划又算出同一条边」= **确定性死循环**；
# 而 `rule_k4` 只做**字符串包含**（本脚本 39/50 条规则都是这一档）⇒ 该缺陷**静默 6 天**、门禁全程绿。
#
# 本规则把"执行侧独有准入"变成**可数的东西**（`survey/28 §6.6#5`：不许用「复杂度」这种形容词）：
#   ① 执行工厂里出现的**每一个**拒绝码必须在本表里**分类**（新增码 ⇒ **红**）；
#   ② 类别 `CAPABILITY`（能力类 = 决定"这条边该不该存在"）的**必须指名规划侧出处**；
#   ③ 指名的出处必须**在仓库里真实存在**（防编造 —— 写个不存在的符号 ⇒ **红**）；
#   ④ 未指名的能力类码**只许减少**：`CAPABILITY_UNRESOLVED_BUDGET` 是"冻结上限"，涨 ⇒ **红**。
#
# ⚠️ 反漂移纪律（`D-395`）：本规则是**尺子**、不是能力进展；尺度是它必须能"**注入即变红**"。
# ⚠️ 把 27 个能力类码逐个"指名"是 **P2（Movement 审查切片）** 的活，一片解决一两个；
#    本规则现在的作用是**阻止新增漂移**，不是一次还清。
EXECUTOR_REFUSAL_CLASSES = {
    "ASCEND_FALLING_BLOCK_ABOVE": ("CAPABILITY", "-"),
    "ASCEND_FROM_CLIMBABLE": ("CAPABILITY", "-"),
    "ASCEND_INVALID_GEOMETRY": ("META", "-"),
    "ASCEND_INVALID_PRECONDITION": ("META", "-"),
    "ASCEND_MISSING_CONTEXT": ("META", "-"),
    "ASCEND_NO_HEADROOM": ("CAPABILITY", "MovementHelper.canAscend"),
    "ASCEND_STALE_START": ("META", "-"),
    "ASCEND_UNSUPPORTED_SPEC": ("META", "-"),
    "BREAK_AND_ENTER_BLOCK_NOT_BREAKABLE": ("TIMING", "-"),
    "BREAK_AND_ENTER_DESTINATION_CLEAR": ("CAPABILITY", "-"),
    "BREAK_AND_ENTER_INVALID_GEOMETRY": ("META", "-"),
    "BREAK_AND_ENTER_MISSING_CONTEXT": ("META", "-"),
    "BREAK_AND_ENTER_NO_LANDING_SUPPORT": ("CAPABILITY", "-"),
    "BREAK_AND_ENTER_STALE_START": ("META", "-"),
    "BREAK_AND_ENTER_UNSUPPORTED_SPEC": ("META", "-"),
    "BREAK_AND_TRAVERSE_INVALID_GEOMETRY": ("META", "-"),
    "BREAK_AND_TRAVERSE_MISSING_CONTEXT": ("META", "-"),
    "BREAK_AND_TRAVERSE_NO_MID_SUPPORT": ("CAPABILITY", "MovementHelper.canWalkOn"),
    "BREAK_AND_TRAVERSE_NOTHING_TO_BREAK": ("CAPABILITY", "-"),
    "BREAK_AND_TRAVERSE_NO_SUPPORT": ("CAPABILITY", "-"),
    "BREAK_AND_TRAVERSE_STALE_START": ("META", "-"),
    "BREAK_AND_TRAVERSE_UNSUPPORTED_SPEC": ("META", "-"),
    "BREAK_BLOCK_PROTECTED": ("CAPABILITY", "-"),
    "BREAK_BLOCK_UNBREAKABLE": ("CAPABILITY", "-"),
    "DESCEND_INVALID_GEOMETRY": ("META", "-"),
    "DESCEND_INVALID_PRECONDITION": ("META", "-"),
    "DESCEND_MISSING_CONTEXT": ("META", "-"),
    "DESCEND_REJECTED_LANDING_HAZARD": ("CAPABILITY", "-"),
    "DESCEND_REJECTED_OVERSHOOT_CLIFF": ("CAPABILITY", "-"),
    "DESCEND_STALE_START": ("META", "-"),
    "DESCEND_UNSUPPORTED_SPEC": ("META", "-"),
    "DIAGONAL_INVALID_GEOMETRY": ("META", "-"),
    "DIAGONAL_INVALID_PRECONDITION": ("META", "-"),
    "DIAGONAL_MISSING_CONTEXT": ("META", "-"),
    "DIAGONAL_STALE_START": ("META", "-"),
    "DIAGONAL_UNSUPPORTED_SPEC": ("META", "-"),
    "DOWNWARD_BLOCK_NOT_BREAKABLE": ("TIMING", "-"),
    "DOWNWARD_INVALID_GEOMETRY": ("META", "-"),
    "DOWNWARD_INVALID_PRECONDITION": ("META", "-"),
    "DOWNWARD_MISSING_CONTEXT": ("META", "-"),
    "DOWNWARD_STALE_START": ("META", "-"),
    "DOWNWARD_UNSUPPORTED_SPEC": ("META", "-"),
    "FALL_COLUMN_BLOCKED": ("CAPABILITY", "MovementHelper.canWalkThrough"),
    "FALL_EDGE_BLOCKED": ("CAPABILITY", "MovementHelper.bodyPassable"),
    "FALL_INVALID_GEOMETRY": ("META", "-"),
    "FALL_LANDING_BOTTOM_SLAB": ("CAPABILITY", "MovementHelper.isBottomSlab"),
    "FALL_LANDING_FLUID": ("CAPABILITY", "-"),
    "FALL_LANDING_INVALID": ("CAPABILITY", "MovementHelper.canStandCentered"),
    "FALL_MISSING_CONTEXT": ("META", "-"),
    "FALL_NOT_ON_GROUND": ("TIMING", "-"),
    "FALL_NOT_RECOVERABLE_HEADROOM": ("CAPABILITY", "MovementHelper.canWalkThrough"),
    # ⚠️ 下面两个码**有意留未指名**（2026-09-24 `P2` Fall 片）：
    # · `FALL_NOT_RECOVERABLE_NO_BLOCKS` = "背包里有没有 ≥drop 个一次性方块"（`countThrowaway`）——
    #   与 `PLACE_RESOURCE_UNAVAILABLE` 是**同一档事实**（执行期库存），两侧都查但都不是移动谓词；
    # · `FALL_LANDING_FLUID` = 两侧都是内联 `getFluidState(to).isEmpty()`（**任意流体**，含岩浆）——
    #   `MovementHelper.isWater` 只认水，拿它当出处是**错的**（比留债更糟）。
    "FALL_NOT_RECOVERABLE_NO_BLOCKS": ("CAPABILITY", "-"),
    "FALL_NOT_RECOVERABLE_NO_FACE": ("CAPABILITY", "BlockInteraction.hasPlacementFace"),
    "FALL_STALE_START": ("META", "-"),
    "FALL_UNSUPPORTED_SPEC": ("META", "-"),
    "PILLAR_HEAD_BLOCKED": ("CAPABILITY", "MovementHelper.bodyPassable"),
    "PILLAR_INVALID_GEOMETRY": ("META", "-"),
    "PILLAR_MISSING_CONTEXT": ("META", "-"),
    "PILLAR_NOT_ON_GROUND": ("TIMING", "-"),
    "PILLAR_PLACE_OCCUPIED": ("CAPABILITY", "MovementHelper.canWalkThrough"),
    "PILLAR_STALE_START": ("META", "-"),
    "PILLAR_UNSUPPORTED_SPEC": ("META", "-"),
    # ⚠️ `PLACE_RESOURCE_UNAVAILABLE` **有意留未指名**（2026-09-24）：它是"背包里有没有可放方块"
    # —— 两侧确实都查 `BlockInteraction.findPlaceableSlot`，但那是**执行期库存事实**，
    # 不是"规划侧决定这条边该不该存在"的移动谓词 ⇒ 硬凑一个出处等于编造（宁可留在债上）。
    "PLACE_NO_VALID_FACE": ("CAPABILITY", "BlockInteraction.hasPlacementFace"),
    "PLACE_RESOURCE_UNAVAILABLE": ("CAPABILITY", "-"),
    "PLACE_STEP_AND_TRAVERSE_INVALID_GEOMETRY": ("META", "-"),
    "PLACE_STEP_AND_TRAVERSE_MISSING_CONTEXT": ("META", "-"),
    "PLACE_STEP_AND_TRAVERSE_NO_SWEEP": ("CAPABILITY", "MovementHelper.canSweepPlayer"),
    "PLACE_STEP_AND_TRAVERSE_PLACE_OCCUPIED": ("CAPABILITY", "MovementHelper.canWalkThrough"),
    "PLACE_STEP_AND_TRAVERSE_STALE_START": ("META", "-"),
    "PLACE_STEP_AND_TRAVERSE_SUPPORT_EXISTS": ("CAPABILITY", "MovementHelper.canWalkOn"),
    "PLACE_STEP_AND_TRAVERSE_TARGET_BLOCKED": ("CAPABILITY", "MovementHelper.bodyPassable"),
    "PLACE_STEP_AND_TRAVERSE_UNSUPPORTED_SPEC": ("META", "-"),
    "TRAVERSE_INVALID_PRECONDITION": ("META", "-"),
    "TRAVERSE_MISSING_CONTEXT": ("META", "-"),
    "TRAVERSE_STALE_START": ("META", "-"),
    "TRAVERSE_UNSUPPORTED_SPEC": ("META", "-"),
}
# 执行侧准入码的**人口下限**（`P2` Traverse 片，2026-09-24）：规则**看得见**的码不得少于这个数。
# 为什么需要它：正则漏一种形态（`describe` 转发 / 字面量 + 诊断串拼接）时会**静默少扫**，
# 而"少扫"在读数上与"没新增"长得一样 ⇒ 用一个人口下限把"正则退化"变成红的。
REFUSAL_CODES_MIN = 76

# 未指名的能力类码上限（**双向**：必须等于当前实际值 —— 每解决一个就把它改小；
# 新加未指名的能力类码 ⇒ 实际值涨 ⇒ 红。2026-09-24 `P2` Diagonal 切片：26 → **25**
# （`DIAGONAL_SIDE_COLLISION` 退役：它与 `canTraverse` 内部那段逐格相同 ⇒ 不可达死码，已删）
# 2026-09-24 `P2` Traverse 片：25 → **21**（`P2` 的"逐个指名"第 2 批）
# 2026-09-24 `P2` Fall 片：19 → **13**（`FALL_{COLUMN_BLOCKED,EDGE_BLOCKED,LANDING_BOTTOM_SLAB,LANDING_INVALID,NOT_RECOVERABLE_HEADROOM,NOT_RECOVERABLE_NO_FACE}`；
# 另两个码有意留债，理由见各自条目）
# 2026-09-24 `P2` Pillar 片：21 → **19**（`PILLAR_HEAD_BLOCKED` → `MovementHelper.bodyPassable`、
# `PILLAR_PLACE_OCCUPIED` → `MovementHelper.canWalkThrough`；同时两处手搓净空收进 `bodyPassable`）
# —— 指名 4 个：`PLACE_STEP_AND_TRAVERSE_{TARGET_BLOCKED,SUPPORT_EXISTS,PLACE_OCCUPIED}` + `PLACE_NO_VALID_FACE`
# （出处见各自条目；`PLACE_RESOURCE_UNAVAILABLE` 有意留债，理由见上）。
CAPABILITY_UNRESOLVED_BUDGET = 13


def rule_k4_capability_provenance():
    """尺子 2：执行侧独有准入必须分类；能力类必须指名规划侧出处（且出处真实存在）。"""
    violations = []
    # ⚠️ 2026-09-24（`P2` Traverse 片）：**必须吃掉"字面量 + 诊断串"的拼接形态** ——
    # `invalid("CODE@" + "from=" + …)`（诊断串换行拼接也算）。原来的 `"([A-Z_]+)"` 要求**闭引号紧跟大写**
    # ⇒ 这类码**整条看不见**（实测漏了 2 个：`PLACE_STEP_AND_TRAVERSE_NO_SWEEP` 与
    # `BREAK_AND_TRAVERSE_NO_MID_SUPPORT`）。`D-396` 登记过第二种形态（经 `describe` 转发），这是第三种；
    # 防复发靠下面的人口下限 `REFUSAL_CODES_MIN`（正则退回严格 ⇒ 人口掉下去 ⇒ 红）。
    pattern = re.compile(r'invalid\(\s*(?:describe\(\s*)?"([A-Z_]+)')
    seen = {}
    for path in sorted(CORE.glob("*ExecutionFactory.java")):
        if path.name == "MovementExecutionFactory.java":
            continue   # 接口文件
        for code in set(pattern.findall(path.read_text(encoding="utf-8"))):
            seen.setdefault(code, path.name)
    # ① 新增码必须先分类
    for code, where in sorted(seen.items()):
        if code not in EXECUTOR_REFUSAL_CLASSES:
            violations.append(f"新增执行侧准入码 {code}（{where}）未分类 ⇒ 加进 "
                              "EXECUTOR_REFUSAL_CLASSES 并判定 CAPABILITY/TIMING/META")
    # ②③ 能力类必须指名规划侧出处，且出处必须真实存在
    unresolved = 0
    for code, (cls, site) in sorted(EXECUTOR_REFUSAL_CLASSES.items()):
        if cls != "CAPABILITY":
            continue
        if site in ("-", "", "UNRESOLVED"):
            if code in seen:
                unresolved += 1
            continue
        name = site.split(".")[-1].rstrip("()")
        if not grep_symbol_exists(name):
            violations.append(f"{code} 指名的规划侧出处 {site} 在仓库里不存在（防编造：出处必须可 grep）")
    # ③b 人口下限（`P2` Traverse 片）：防"正则退回严格 ⇒ 拼接形态的码又看不见"
    if len(seen) < REFUSAL_CODES_MIN:
        violations.append(f"扫到的执行侧准入码只有 {len(seen)} 个，低于人口下限 {REFUSAL_CODES_MIN}"
                          "（怀疑正则退化：拼接/转发的码又漏了 —— `D-396` 与 `P2` 各踩过一次）")
    # ④ 未指名数**双向**钉死（涨 = 新增漂移；降而不改上限 = 进度没被登记 ⇒ 上限会变成假读数）
    if unresolved > CAPABILITY_UNRESOLVED_BUDGET:
        violations.append(f"未指名的能力类准入码从 {CAPABILITY_UNRESOLVED_BUDGET} 涨到 {unresolved}"
                          "（只许减少；新加的能力类码必须同时指名规划侧出处）")
    elif unresolved < CAPABILITY_UNRESOLVED_BUDGET:
        violations.append(f"未指名的能力类准入码已经降到 {unresolved}，但 "
                          f"`CAPABILITY_UNRESOLVED_BUDGET` 还写着 {CAPABILITY_UNRESOLVED_BUDGET}"
                          "（解决一个就把它改小：上限是**读数**，不是「以后再说」的额度）")
    rule_k4_capability_provenance.unresolved = unresolved
    rule_k4_capability_provenance.total = len(seen)
    rule_k4_capability_provenance.scanned = len(EXECUTOR_REFUSAL_CLASSES)
    return violations


def rule_ledger_closure_zone_scoped():
    """`Z2`（2026-09-23）：**"账本收工了没有"只许一个口径，而且必须带人口**。

    事实（为什么必须门禁化，不是注释）：`Z1` 让 `recordPlacement` **在区外不记账** ⇒
    "**用账本证明我没写世界 / 没留我方方块**"的那一族判据在野外**人口为 0** ⇒ 空集让它们恒真。
    CORE 实测（`run/headless-logs/20260923-140625-core.log`）：`[Ledger] place` **8 条全来自
    自己认领了区块的 `scaffold` 步**、其余 **13 次放置全是 `skip``；同轮
    `[Recover] residues=0（本进程内没有出现「我方方块未收回」）` 因此是**空读数**，不是"世界很干净"。
    这类失败**不报错**（本项目纪律：假绿比假红危险）⇒ 只能靠门禁。

    四条，各有一条注入臂（改任一处 ⇒ 变红）：
    ① **闭合点只许用 `closure(...)`**：电池 `endStep` / 编排器终态 / 生产 `clearTask` /
       恢复入口，不得再拿裸 `pendingTemporary(` / `pendingForOwner(` 当"待收义务"的口径
       （裸视图含区外条目 ⇒ 拿它判红就是拿无主区域的事判我方的错，`D-398` R1/R2）；
    ② **区内判据只有一个出处**：`closure` 的 `inZone` 必须来自 `pendingTemporaryProtected`，
       而后者必须问 `ProtectionZones.isProtected`（不许各写一遍 ⇒ 消费漏接一处就是 D-338 那类事故）；
    ③ **空集必须可见**：闭合点要印人口（`Closure.describe()`），否则「账本空」=「世界干净」这个
       误读会静默复活（`Z2` 的唯一产出就是让这个误读**看得见**）；
    ④ ⭐ `RC1`（2026-09-24）：**对账路径不许替未加载区块开图** —— `dropStale` 读方块之前必须先判
       `isLoaded`（未加载 ⇒ 不读、不销、留待下次）。否则「已加载才碰」这条保留条件会被上游这一读
       卸掉力：`pickNext` 的 `chunk_not_loaded` 变成**不可达**分支（实测见臂④代码注释）。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    ledger = base / "ledger/WorldModLedger.java"
    battery = base / "task/RegressionBatteryTask.java"
    harness = base / "task/check/CheckHarness.java"
    manager = base / "bot/BotManager.java"
    item = base / "item/RestoreCheckItem.java"

    def strip_block_comments(text: str) -> str:
        """去掉 `/* … */` 块注释（`code_only` 只去 `//`）—— 本规则查的是**代码**，
        文档里提到旧写法（如 `pendingTemporary(本步 scope)`）不算违规。"""
        return re.sub(r"/\*.*?\*/", "", text, flags=re.S)

    def code(path):
        if not path.exists():
            return None
        return code_only(strip_block_comments(path.read_text(encoding="utf-8")))

    problems = []
    led = code(ledger)
    bat = code(battery)
    har = code(harness)
    man = code(manager)
    itm = code(item)
    for text, name in ((led, ledger.name), (bat, battery.name), (har, harness.name),
                       (man, manager.name), (itm, item.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则）")
    if problems:
        return problems

    # ---- 臂① 闭合点 / 义务入口只许用 zone-aware 视图 ----
    if "pendingTemporary(" in bat:
        problems.append("电池里还在用裸 `pendingTemporary(` 当待收口径 ⇒ 区外条目会参与判决"
                        "（`D-398` R1/R2：区外不负任何责任）—— 应收窄到 `WorldModLedger.closure(...)`")
    if "WorldModLedger.closure(" not in method_body(bat, "private void endStep()"):
        problems.append("电池 `endStep()` 没有用 `WorldModLedger.closure(...)` 做闭合读数"
                        "（那是唯一的「该收工了吗」口径，见 `Z2`）")
    if "pendingForOwner(" in har:
        problems.append("编排器里还在用 `pendingForOwner(`（**跨 scope 的 owner 口径**）判泄漏 ⇒ "
                        "别的步的遗留会误伤本步（D-298 那类假红）⇒ 应为「本步作用域 + 保护区内」")
    if "WorldModLedger.closure(" not in method_body(har, "private void tick()"):
        problems.append("编排器终态判据没有用 `WorldModLedger.closure(...)`（口径必须与电池 `endStep` 同源）")
    if "pendingTemporary(" in man:
        problems.append("`BotManager` 里还有裸 `pendingTemporary(` ⇒ 生产侧（收尾/恢复入口）的"
                        "义务口径没跟上 `D-398`（区外条目会出现在「仍有多少未拆除」里）")
    if "WorldModLedger.closure(" not in method_body(man, "void clearTask()"):
        problems.append("生产收尾 `clearTask()` 没用 `WorldModLedger.closure(...)` ⇒ 它报的"
                        "「仍有 N 条未拆除」会把区外条目算进来（且看不出人口）")
    if "pendingTemporaryProtected(" not in method_body(man, "assignRestore("):
        problems.append("恢复入口 `assignRestore` 没用区内视图 ⇒ 与 `RestoreScopeTask` 的取件口径"
                        "不一致（症状：说去恢复，到了什么都不做）")
    if "pendingTemporary(" in itm or "pendingTemporaryProtected(" not in itm:
        problems.append("`RestoreCheckItem` 的入口口径不是区内视图（`D-398` R2：区外一定不恢复）")

    # ---- 臂② 区内判据只有一个出处 ----
    closure_body = method_body(led, "public static Closure closure(")
    if "pendingTemporaryProtected(" not in closure_body:
        problems.append("`closure(...)` 的 `inZone` 不是取自 `pendingTemporaryProtected` ⇒ "
                        "区内判据出现了第二个实现（必然漂移）")
    # ⚠️ 人口差值的**基准必须是窗口起点**：传 `Population.ZERO` 会静默退回"自服务器启动累计"，
    # 于是"本步期间发生了什么"这个读数就永远是对的假象（`silent-measurement-failure`）。
    if "populationBaseline(" not in method_body(bat, "private void endStep()") \
            or "stepPopulationBaseline" not in method_body(bat, "private void endStep()"):
        problems.append("电池 `endStep()` 没有用**步窗口基线**（`populationBaseline(...)` → "
                        "`stepPopulationBaseline`）⇒ 人口差值会退化成「自启动累计」（读起来像「本步」，其实是全局）")
    if "populationBaseline(" not in har or "stepPopulationBaseline" not in har:
        problems.append("编排器没有维护步窗口基线（`populationBaseline(...)` / `stepPopulationBaseline`）⇒ 同上")
    if "populationBaseline(" not in man or "ledgerPopulationBaseline" not in man:
        problems.append("生产 `BotManager` 没有维护任务窗口基线（`populationBaseline(...)` / "
                        "`ledgerPopulationBaseline`）⇒ 收尾告警里的人口是全局累计，不是本任务")
    # ⚠️ `recorded` 计数器是"<空是哪一种空>"的**唯一**依据 ⇒ 谁忘了递增，读数就会把
    # "写了又收干净"误报成"压根没写"（又一次静默失败）。
    if "recorded++" not in method_body(led, "public static void recordPlacement("):
        problems.append("`recordPlacement` 没有递增 `recorded` 计数器 ⇒ `Closure.recordedSince` 恒 0 "
                        "⇒ 会把「写了又收干净」误报成「压根没写」")
    protected_body = method_body(led, "public static List<Entry> pendingTemporaryProtected(")
    if "ProtectionZones.isProtected" not in protected_body:
        problems.append("`pendingTemporaryProtected` 不再问 `ProtectionZones.isProtected` ⇒ "
                        "保护区判据被绕开（`ProtectionZones` 是 `D-398` 的唯一判据入口）")

    # ---- 臂③ 空集必须可见 ----
    # ⚠️ 必须钉**人口行本身**：`endStep` 的"泄漏判红"分支里也有 `closure.describe()` ⇒
    # 只查"该方法里出现过 describe()"会被它满足（注入实测没红，本会话第 7 次「判据太弱」）。
    bat_close = method_body(bat, "private void endStep()")
    if "[Ledger] 闭合 step=" not in bat_close or "closure.describe()" not in bat_close:
        problems.append("电池 `endStep()` 少了**无条件**的人口行（`[Ledger] 闭合 step=… closure.describe()`）"
                        "⇒ `Z1` 之后「账本空」会被读成「没写世界」（假绿，见 `Z2`）；"
                        "判红分支里的那句不算（它只在出错时才印）")
    har_close = method_body(har, "private void tick()")
    if "[Ledger] 闭合 module=" not in har_close or "closure.describe()" not in har_close:
        problems.append("编排器终态少了无条件的人口行（`[Ledger] 闭合 module=… closure.describe()`）⇒ 同上")
    if "reportLedgerPopulation();" not in method_body(bat, "private Status finish()"):
        problems.append("电池每轮的**人口汇总行**没有被调用（`finish()` 里少了 `reportLedgerPopulation()`）"
                        "⇒ 报告里看不出「账本样本」有多大")
    if "wildSkippedSince" not in led or "recordedSince" not in led \
            or "public String describe()" not in led:
        problems.append("`Closure` 记录不再带人口（`wildSkippedSince` / `recordedSince` / `describe()`）⇒ "
                        "「空」的三种含义（收干净了/全在区外/真的没写）又分不开了")

    # ---- 臂④ `RC1`：账本**对账路径不许替未加载区块开图** ----
    # 实测逼出来的（不是读码猜的）：`RestoreScopeTask.pickNext` 的 `chunk_not_loaded` 保留条件在没有
    # 本守卫时**到不了** —— `buildQueue` **开头**就调 `dropStale`，它对区内条目裸读方块 ⇒ 未加载区块被
    # **强制同步加载**（把对账变成开图），条目还被当"幽灵"销掉；注入实测 = 关掉守卫 ⇒
    # 臂③红（`nothing_to_restore`，`run/headless-logs/20260924-090447-*`）。
    stale_body = method_body(led, "public static int dropStale(")
    read_at = stale_body.find("getBlockState(entry.pos())")
    if read_at < 0:
        problems.append("`dropStale` 里找不到对账读方块（`getBlockState(entry.pos())`）⇒ "
                        "改名/搬迁了？本规则的臂④要跟着同步（否则守卫会静默消失）")
    elif "!level.isLoaded(entry.pos())" not in stale_body[:read_at]:
        problems.append("`dropStale` 在**读方块之前**没有「未加载 ⇒ 不读、不销」守卫 ⇒ "
                        "`level.getBlockState(未加载区块)` 会**强制同步加载**（对账变成开图），"
                        "条目还会被当幽灵销掉 ⇒ `RestoreScopeTask.pickNext` 的 `chunk_not_loaded` "
                        "分支**到不了**（`RC1` 实测：run/headless-logs/20260924-085903-*）")
    return problems


def rule_lossy_write_accounted():
    """`RC3`（2026-09-24）：**不可逆写入必须如实记账，而且两条破坏路径都要记**。

    <h3>为什么（裁定 + 今天真的会发生，不是推测）</h3>
    `docs/plans/2026-09-22-回收方案.md` §4.1 C 裁定：容器内容 / 流体 / 方块实体副作用
    **明确不做逐 item 还原，但必须如实记账**（可查计数/日志），且"不许假装可逆"。
    而今天 Alice **能**破这些方块：`BlockBreakSafety.clearingRefusal` 只在**清障**策略下拒
    `hasBlockEntity()`（`D-095`），明确目标策略（`EXPECTED_TARGET`/`DESCEND_FOOT`/`BULK_EDIT`）
    照挖不误 ⇒ 没有记账时，"箱子里的东西连同箱子一起没了"**完全静默**。

    <h3>五条臂（各有一条注入）</h3>
    ① **唯一判据入口** `WorldModLedger.lossyOf`：容器 → 方块实体 → 流体（顺序即优先级）；
    ② **两条真的改世界的路都要记**：`BlockBreakSession`（按 tick 的会话：挖矿/破入/下落都走它）
       与 `BlockInteraction.breakForBulkEdit`（`level.destroyBlock` 批量）；
    ③ **计数必须进闭合人口**（`Closure`）：否则 `lossy=+0` 分不清"没遇上带数据的方块"与"不在看"；
    ④ `anythingHappened()` 必须把不可逆算作"发生过" —— 破箱子**不产生任何回收义务**
       （`recorded=0`）⇒ 漏了它，"账本空"会被读成"本窗口没写过世界"（正是要防的谎）；
    ⑤ **不许静默**：每次都要有 `[Ledger] cannot_reclaim` 行 + 一个可读的人口差值。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    ledger = base / "ledger/WorldModLedger.java"
    session = base / "action/BlockBreakSession.java"
    interact = base / "action/BlockInteraction.java"

    def code(path):
        if not path.exists():
            return None
        return code_only(re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S))

    problems = []
    led = code(ledger)
    ses = code(session)
    itr = code(interact)
    for text, name in ((led, ledger.name), (ses, session.name), (itr, interact.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则 `RC3`）")
    if problems:
        return problems

    # ---- 臂① 唯一判据入口 + 优先级顺序 ----
    classify = method_body(led, "public static Lossy lossyOf(")
    if "enum Lossy" not in led:
        problems.append("`WorldModLedger` 里没有 `Lossy` 族枚举 ⇒ `RC3` 的「不可逆」没有判据入口")
    for needle, why in (("ContainerSemantics.of(", "容器（内容拿不回来）"),
                        ("hasBlockEntity()", "其余方块实体（NBT 丢失）"),
                        ("getFluidState().isEmpty()", "流体本身")):
        if needle not in classify:
            problems.append(f"`lossyOf` 少了 {why} 的判据（`{needle}`）⇒ 那一族不会被记账")
    idx = [classify.find(n) for n in ("ContainerSemantics.of(", "hasBlockEntity()",
                                      "getFluidState().isEmpty()")]
    if all(i >= 0 for i in idx) and not (idx[0] < idx[1] < idx[2]):
        problems.append("`lossyOf` 的判定顺序变了（必须 容器 → 方块实体 → 流体）："
                        "顺序即优先级，换了会把 `container_contents` 误报成 `block_entity`")

    # ---- 臂② 两条破坏路径都要记 ----
    if "recordLossyWrite(" not in ses:
        problems.append("`BlockBreakSession` 破成功后**没有**调用 `WorldModLedger.recordLossyWrite(` ⇒ "
                        "挖矿/破入/下落这条路径上「破掉带数据的方块」会静默（`RC3` 的记账等于没落地）")
    bulk = method_body(itr, "public static boolean breakForBulkEdit(")
    if "recordLossyWrite(" not in bulk:
        problems.append("`BlockInteraction.breakForBulkEdit`（`level.destroyBlock` 批量路）没有记账 ⇒ "
                        "同一件事在第二条路径上仍然静默")

    # ---- 臂③④⑤ 人口可见 + 不许把不可逆读成"没发生" + 不静默 ----
    clos = method_body(led, "public record Closure(")
    if "lossyWritesSince" not in clos or "lossyRefusalsSince" not in clos:
        problems.append("`Closure` 不再带不可逆计数（`lossyWritesSince` / `lossyRefusalsSince`）⇒ "
                        "每个闭合点的读数里看不到「弄丢了东西」，`lossy=+0` 也就无法解释")
    elif "lossy=+" not in clos:
        problems.append("`Closure.describe()` 没有把不可逆计数印出来 ⇒ 人口不可见（`Z2` 的同一条教训）")
    ah = method_body(led, "public boolean anythingHappened()")
    if "lossyWritesSince" not in ah:
        problems.append("`Closure.anythingHappened()` 没把不可逆算作「发生过」 ⇒ 破了箱子却报"
                        "「本窗口没写过世界」（把不可逆说成没发生）")
    rec = method_body(led, "public static void recordLossyWrite(")
    if "[Ledger] cannot_reclaim" not in rec:
        problems.append("`recordLossyWrite` 没有逐条 `[Ledger] cannot_reclaim` 日志 ⇒ "
                        "计数在、明细不在（裁定要的是「可查计数/日志」，两者都要）")
    return problems


def rule_kill_drop_attributed():
    """`A3`（2026-09-24）：**击杀产物必须归我方，而且只归"我们打死的"**。

    <h3>为什么（`survey/29 §2.1`，夹具先复现过，不是推测）</h3>
    `ScopeBuffer` 原先只按 `BlockEvent.BreakEvent` 配对归属 ⇒ **击杀不产生破坏事件** ⇒
    击杀产物没有任何来源 ⇒ `DropPolicy.effectiveProvenance` 落 `FOREIGN` ⇒ `PickupGate` 在**被动路径**上
    直接拦下（`drop.foreign = ASK`）⇒ "自己杀的牛，肉捡不起来"，且只有一行节流日志。
    实测（夹具红线版，`run/headless-logs/20260924-105859-*`）：`playerAttack(bot)` 打死牛 ⇒
    `provA=[FOREIGN, FOREIGN]` · `passiveA=false` · `pickedA=0`。

    <h3>四条臂（各有一条注入）</h3>
    ① **通道存在 + 只认我方**：`LivingDeathEvent` 订阅里 killer 必须是**玩家**（1.20.1 的
       `getKillCredit()` 返回 `LivingEntity`，不判玩家就会把"生物互殴"算成我方），
       并且必须与本作用域 owner 比对（别人打的**不许**默认领）；
    ② **归类在入队时解析、且击杀优先于间接松窗**（`pendingEntry` 里 `matchKillOrigin` 存在且
       位置在 `matchIndirectOrigin` 之前）—— 顺序换了就会把"确证的死因"降级成 60 tick 兜底；
    ③ **策略表同源**：`DropPolicy` 的 `OURS_KILL` 必须映射到具名能力，且 `PermissionGate.DEFAULTS`
       里登记为 `AUTO`（漏登记 ⇒ 默认退化 `ASK` ⇒ 又变回"捡不起来"，且**静默**）；
    ④ **通道单一**：`OURS_KILL` 只许在 `ScopeBuffer`（生产处）与 `DropPolicy`（定义/映射）出现
       ⇒ 防止在别处（如破坏路径）手工伪造"击杀归属"。

    <h3>注入即红（2026-09-24 逐条单独开火，均已实测）</h3>
    A 去掉 owner 比对；B 去掉 killer 的"是不是玩家"判定；C 删 `pendingEntry` 的击杀分支；
    D 把击杀分支挪到间接松窗之后；E 删 `PermissionGate.DEFAULTS` 里的 `CAP_OURS_KILL` 行；
    F 在 `BlockBreakSession` 里塞一处 `OURS_KILL`。每条都让本规则变红并指名。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    scope = base / "perception/ScopeBuffer.java"
    policy = base / "decision/DropPolicy.java"
    gate = base / "decision/PermissionGate.java"

    def code(path):
        if not path.exists():
            return None
        # 只去 `//` 行注释（与共享 `code_only` 同口径）；文本里的 `OURS_KILL` 提及不算违规
        return code_only(path.read_text(encoding="utf-8"))

    problems = []
    sc = code(scope)
    po = code(policy)
    ga = code(gate)
    for text, name in ((sc, scope.name), (po, policy.name), (ga, gate.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则 `A3`）")
    if problems:
        return problems

    # ---- 臂① 通道存在 + 只认我方（玩家 + owner 比对） ----
    if "LivingDeathEvent" not in sc:
        problems.append("`ScopeBuffer` 不再订阅 `LivingDeathEvent` ⇒ 击杀产物又没有任何归属来源"
                        "（`A3` 的缺口原样回来：杀牛产物落 `FOREIGN` ⇒ 捡不起来且静默）")
    death = method_body(sc, "public static void onLivingDeath(")
    if not death:
        problems.append("`ScopeBuffer.onLivingDeath` 不见了（`A3` 的击杀登记入口）⇒ 门禁钉不住通道")
    else:
        if "ownerUuid.equals(killer)" not in death:
            problems.append("`onLivingDeath` 里没有「killer 必须等于本作用域 owner」的比对 ⇒ "
                            "**别人在同一片地杀的东西会被默认领成我方的**（`A3` 臂②就是钉这个）")
        credit = method_body(sc, "private static java.util.UUID creditedKiller(")
        # ⚠️ **判据要精确到"哪一处 instanceof"**：本方法里有两处玩家判定（credit 与伤害来源），
        # 只查"文件里有没有 instanceof Player"是**假绿** —— 注入 B 第一版实测**没红**（第二处还在）。
        # 要求：**第一处** `instanceof Player` 必须落在 `getKillCredit()` 与 `getSource()` **之间**。
        credit_at = credit.find("getKillCredit()")
        first_check = credit.find("instanceof net.minecraft.world.entity.player.Player")
        source_at = credit.find("getSource()")
        if credit_at < 0 or first_check < 0 or first_check < credit_at \
                or (source_at >= 0 and first_check > source_at):
            problems.append("`creditedKiller` 没有把**击杀credit**再判一次是不是玩家 ⇒ 1.20.1 的 "
                            "`getKillCredit()` 会返回 `lastHurtByMob`（`LivingEntity`）⇒ "
                            "生物互殴/自然死亡会被算成我方击杀（⚠️ 判据盯**第一处** instanceof："
                            "第二处是伤害来源，只看「有没有」会假绿 —— 注入 B 第一版实测）")
    # ---- 臂② 入队时解析 + 击杀优先于间接松窗 ----
    pending = method_body(sc, "private PendingItem pendingEntry(")
    if "matchKillOrigin(" not in pending:
        problems.append("`pendingEntry` 里没有击杀配对分支（`matchKillOrigin`）⇒ 登记通道在，"
                        "但**归属解析**没接上（产物照旧落 FOREIGN）")
    elif "matchIndirectOrigin(" in pending and pending.find("matchKillOrigin(") > pending.find("matchIndirectOrigin("):
        problems.append("`pendingEntry` 里击杀配对排在**间接松窗之后** ⇒ 两者同时命中时会把"
                        "「确证的死因」降级成 60 tick/4 格的兜底归属（优先级：破坏直配 → 击杀 → 间接）")
    if "private BlockPos matchKillOrigin(" not in sc:
        problems.append("`ScopeBuffer.matchKillOrigin` 不见了（击杀配对的唯一出处）")
    # ---- 臂③ 策略表同源 ----
    if "OURS_KILL" not in po:
        problems.append("`DropPolicy.Provenance` 里没有 `OURS_KILL` ⇒ 击杀产物只能并进其它档"
                        "（玩家就没法单独把这一档调紧）")
    if "case OURS_KILL -> CAP_OURS_KILL" not in po:
        problems.append("`DropPolicy.capability` 没有把 `OURS_KILL` 映射到具名能力"
                        "（`DropPolicy.CAP_OURS_KILL`）⇒ 权限表里没有可配的开关")
    if "DEFAULTS.put(DropPolicy.CAP_OURS_KILL, Policy.AUTO)" not in ga:
        problems.append("`PermissionGate.DEFAULTS` 没把 `drop.ours_kill` 登记为 `AUTO` ⇒ "
                        "默认落到 `policy()` 的兜底 `ASK` ⇒ 我方击杀产物**又被拦下**（回到缺口的症状，"
                        "而且看不出是配置漏了）")
    # ---- 臂④ 通道单一 ----
    # 只许 `ScopeBuffer` 产生、`DropPolicy` 定义/映射；**自检夹具除外** —— 夹具读这个枚举值正是
    # 它的判据（`*CheckTask`/`*ProbeTask`，与 `tools/fixture-hygiene.py` 的命名口径同源）。
    fixture_name = re.compile(r"(Check|Probe)\w*Task\.java$")
    allowed = {"perception/ScopeBuffer.java", "decision/DropPolicy.java"}
    for path in sorted((base / "perception").rglob("*.java")) + sorted((base / "action").rglob("*.java")) \
            + sorted((base / "task").rglob("*.java")) + sorted((base / "job").rglob("*.java")):
        rel = path.relative_to(base).as_posix()
        if fixture_name.search(path.name):
            continue
        text = code(path)
        if text and "OURS_KILL" in text and rel not in allowed:
            problems.append(f"`{rel}` 也在产生/引用 `OURS_KILL` ⇒ 击杀归属通道不再单一"
                            f"（只许 `ScopeBuffer` 产生、`DropPolicy` 定义/映射；夹具除外）")
    return problems


def rule_battery_nonpass_steps_listed():
    """`P7`（2026-09-24）：**非 PASS 的步必须逐条单列一行**（0 条时也要印计数）。

    <h3>为什么（不是洁癖，是实测过的误读）</h3>
    汇总行把 40+ 步挤成**一行**（`clear_retry=PASS write_budget=PASS … lumber_job=FAIL …`）。
    连红 35 轮的 `lumber_job` 就坐在那一行中间 ⇒ 读者形成「唯一失败 = 那个已知的 X」的预期，
    **新失败**只要不是最后一步，就会被「看起来还是老样子」盖掉（真机轮次上实测发生过）。
    ⇒ 任何 `!= PASS` 的步必须**自己占一行**并带明细（失败码/跳过理由/ticks）；
    **0 条时也印** —— 「全绿」必须来自**判据计数**，不是来自「我没看见那一行」（`Z4` 的同一条教训）。

    <h3>四条臂（各有一条注入）</h3>
    ① `finish()` 里必须调用 `reportNonPassSteps()`（注入 A：删调用 ⇒ 红）；
    ② 实现必须**遍历步骤表**并取实跑值（`for (Step step : steps)` + `results.getOrDefault`）；
    ③ **不许**用「非空才印」的守卫把 0 条那一行藏起来（注入 B：包进 `if (!nonPass.isEmpty())` ⇒ 红）；
    ④ 必须带 `details` 明细，且 FAIL 走 `BotLog.warn`（注入 C：去掉 details ⇒ 红）。
    """
    bat_path = ROOT / "src/main/java/com/dddgn/alice/task/RegressionBatteryTask.java"
    if not bat_path.exists():
        return [f"{bat_path.name} 不存在（改名？同步本规则 `P7`）"]
    text = code_only(bat_path.read_text(encoding="utf-8"))
    problems = []

    # ---- 臂① 调用点 ----
    finish = method_body(text, "private Status finish()")
    if "reportNonPassSteps();" not in finish:
        problems.append("`RegressionBatteryTask.finish()` 没有调用 `reportNonPassSteps()` ⇒ "
                        "非 PASS 的步又只剩汇总行里那一段（新失败会被「已知红的那个」盖掉，`P7`）")

    # ---- 臂②③④ 实现 ----
    body = method_body(text, "private void reportNonPassSteps(")
    if not body:
        problems.append("`reportNonPassSteps()` 不见了（`P7` 的判据入口）")
        return problems
    if "for (Step step : steps)" not in body or "results.getOrDefault" not in body:
        problems.append("`reportNonPassSteps()` 没有遍历**步骤表**取实跑值（`for (Step step : steps)` + "
                        "`results.getOrDefault(…)`）⇒ 它印的就不是「本步清单」的判决")
    if '非 PASS 步（' not in body or "nonPass.size()" not in body:
        problems.append("`reportNonPassSteps()` 丢了**计数行**（`非 PASS 步（N 条）`）⇒ "
                        "「全绿」只能靠「没看见那行」推断（`Z4`：看不见 ≠ 不存在）")
    for guard in ("if (!nonPass.isEmpty())", "if (nonPass.size() > 0", "if (!nonPass.isEmpty()"):
        if guard in body:
            problems.append(f"`reportNonPassSteps()` 用 `{guard}` 把计数行藏起来了 ⇒ "
                            f"0 条时那一行不印，「本轮没有非 PASS 步」变成**看不见**而不是**判据为 0**")
    if "details.get(" not in body:
        problems.append("`reportNonPassSteps()` 没带 `details` 明细 ⇒ 只报步名不报**为什么**"
                        "（失败码/跳过理由/ticks 都在 `details` 里）")
    if "BotLog.warn(" not in body:
        problems.append("`reportNonPassSteps()` 里 FAIL 没有走 `BotLog.warn` ⇒ 扫日志时红的和灰的混在一起"
                        "（本块的用途就是「一眼看见非 PASS」）")
    return problems


def rule_job_area_grant_scoped():
    """`P3`（2026-09-24）：**作业级收集授权必须"范围内 + 只认本作业产物"，且只在内存、随作业收掉**。

    <h3>为什么</h3>
    用户 2026-09-22 裁定的出口是给 mine 作业一条「本作业声明范围内」的收集授权，用来救"自己挖出来的落物
    没配上破坏事件 ⇒ 永远捡不起来"。但 `GRANTED_AREA` 的策略是 `AUTO` 且**被动吸附也放行** ⇒
    若只按**坐标**放行，等于把"这片地上的东西我都准你捡"（玩家授权的语义）悄悄搬给作业，
    作业范围内的**玩家丢的东西**会被一起吸走。⇒ 产物过滤是判据的一部分，不是附带说明。
    另外两条结构性约束：授权**只在内存**（作业无权把它持久化成玩家授权，`D-344` 同口径）、
    **签发与撤销成对**（权限窗口 = 作业时长，TTL 只是兜底）。

    <h3>四条臂（各有一条注入）</h3>
    ① `DropPolicy.effectiveProvenance` 必须咨询 `CollectGrants.coveringJobScoped(...)` **并传入物品**
       （只传坐标 = 放宽面，注入 A 去掉咨询 / 注入 B 去掉物品参数 ⇒ 红）；
    ② 查询内部必须**同时**判 `contains(` 与 `acceptsItem(`（注入 C 去掉产物判定 ⇒ 红）；
    ③ 签发文件的 `addJobScoped(` 必须与 `revokeJobScoped(` **成对**（注入 D 删撤销 ⇒ 红）；
    ④ 作业级授权**不许**出现在持久化路径（`JobGrant` 与 `GrantsData` 不许同处一个 `save(`/`load(` 方法；
       注入 E 把 JobGrant 塞进 `save(` ⇒ 红）。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    policy = base / "decision/DropPolicy.java"
    grants = base / "decision/CollectGrants.java"
    minejob = base / "job/mine/MineJob.java"

    def code(path):
        return code_only(path.read_text(encoding="utf-8")) if path.exists() else None

    problems = []
    po = code(policy)
    gr = code(grants)
    mj = code(minejob)
    for text, name in ((po, policy.name), (gr, grants.name), (mj, minejob.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则 `P3`）")
    if problems:
        return problems

    # ---- 臂① 单一判定入口必须真的咨询"作业级授权"（且带物品） ----
    eff = method_body(po, "public static Provenance effectiveProvenance(")
    if "coveringJobScoped(" not in eff:
        problems.append("`DropPolicy.effectiveProvenance` 没有咨询**作业级**授权（`CollectGrants.coveringJobScoped(`）"
                        "⇒ 作业范围内的落物照旧按 `FOREIGN` 处理（自己挖出来的东西捡不起来，`P3` 的缺口原样）")
    elif "item.getItem()" not in eff:
        problems.append("`effectiveProvenance` 调 `coveringJobScoped(...)` 时**没有传物品** ⇒ "
                        "只按坐标放行 = 把「整片都准捡」（玩家授权语义）搬给作业（产物过滤失效）")

    # ---- 臂② 查询内部必须同时判范围与产物 ----
    query = method_body(gr, "public static JobGrant coveringJobScoped(")
    if not query:
        problems.append("`CollectGrants.coveringJobScoped(...)` 不见了（`P3` 的查询唯一入口）")
    else:
        if "contains(pos)" not in query:
            problems.append("`coveringJobScoped` 没有判**范围**（`grant.contains(pos)`）⇒ 作业授权会出圈")
        if "acceptsItem(" not in query:
            problems.append("`coveringJobScoped` 没有判**产物**（`grant.acceptsItem(...)`）⇒ "
                            "作业范围内的玩家丢的东西会被算成 `GRANTED_AREA`（被动吸附一起吸走）")

    # ---- 臂③ 签发/撤销成对 ----
    if "addJobScoped(" in mj and "revokeJobScoped(" not in mj:
        problems.append("`MineJob` 签了作业级授权却没有**撤销**（`revokeJobScoped(`）⇒ 权限窗口退化成 TTL，"
                        "`finish()` 之后一段时间里它仍在放行")

    # ---- 臂④ 作业级授权不许持久化 ----
    if "JobGrant" in gr:
        for signature in ("private static GrantsData load(", "public CompoundTag save("):
            body = method_body(gr, signature)
            if "JobGrant" in body:
                problems.append(f"`CollectGrants` 的持久化路径 `{signature}` 里出现了 `JobGrant` ⇒ "
                                f"作业级授权被写成玩家级持久授权（`D-344`：作业不能悄悄改玩家的持久授权）")
    return problems


def rule_stale_proof_replan():
    """`PL-1` 切片 1（2026-09-24 实测改道）：**"无解"是当时的证明，邻域变了就必须重评（有界）**。

    <h3>为什么（全部是实测，不是推断）</h3>
    夹具 `mine_vein_propagation`：30 格矿脉只挖到 12，剩 18 格全是 `found_but_unminable`；
    作业终态后把每一格**重新规划**一遍（只读探针）：**17/18 现在都有方案**（9 `CURRENT` + 8 `TUNNEL`）、
    剩 1 格是暂时性的 `search_incomplete`。⇒ 卡点是"**失败那一刻的证明被当成了永久事实**"
    （`P1-b`/`D-388` 的同一家族：`SEARCH_LIMIT ≠ UNREACHABLE` 的姊妹条款
    "当时无解 ≠ 永远无解"）。真机同病：`336,62,190 从未被挖却已 already_attempted`。

    <h3>六条臂（各有一条注入）</h3>
    ① 过滤处必须真的**问**"这份证明过期了吗"（注入 A：`withoutAttempted` 不再调 `proofExpired(` ⇒ 红）；
    ② 过期判据必须是"**记录 vs 当前**比较"（注入 B：把比较换成 `return true` ⇒ 红）；
    ③ 必须有**上限**（注入 C：删掉 `MAX_STALE_PROOF_RETRIES` 判断 ⇒ 红）；
    ④ 证明必须在**失败时**记录、且**硬拒绝不记**（注入 D：删 `proofWitness.put(` ⇒ 红；
       注入 E：去掉 `isHardTargetRefusal(` 守卫 ⇒ 红 —— 换邻域也改变不了"这格不许碰"，`D-323`）；
    ⑤ **通过性判据同源**：邻域位串必须用 `MovementHelper.canWalkThrough`（注入 F：换成 `isAir()` ⇒ 红）；
    ⑥ **harness 单项预算不许抢在夹具自己的预算之前**（注入 G：把 `mine_vein_propagation` 的步预算改回
       `2800` ⇒ 红）。判据：harness 预算是**兜底**，夹具内部的 `BUDGET_TICKS` 才是判据 ——
       实测就发生过"能力真的赢了（`mined` 12→19）但读数根本没机会打印"。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    minejob = base / "job/mine/MineJob.java"
    minetask = base / "task/MineTask.java"
    module = base / "task/check/modules/MiningModule.java"
    fixture = base / "task/MineVeinPropagationCheckTask.java"

    def code(path):
        return code_only(path.read_text(encoding="utf-8")) if path.exists() else None

    problems = []
    mj = code(minejob)
    mt = code(minetask)
    mod = code(module)
    fx = code(fixture)
    for text, name in ((mj, minejob.name), (mt, minetask.name), (mod, module.name), (fx, fixture.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则 `PL-1`）")
    if problems:
        return problems

    # ---- 臂①②③：过滤 → 过期判据 → 上限 ----
    filter_body = method_body(mj, "private CandidateSet withoutAttempted(")
    expired_body = method_body(mj, "private boolean proofExpired(")
    if "proofExpired(" not in filter_body:
        problems.append("`MineJob.withoutAttempted` 不再问「这份证明过期了吗」（`proofExpired(`）⇒ "
                        "`already_attempted` 又变回**永久了结**（`PL-1` 的缺口原样：夹具 18 格孤儿只剩 12/30 就是这个）")
    if not expired_body:
        problems.append("`MineJob.proofExpired(...)` 不见了（过期判据的唯一入口）")
    else:
        if "proofWitness.get(" not in expired_body:
            problems.append("`proofExpired` 没有读**当时记下的**证明（`proofWitness.get(`）⇒ "
                            "判据不是「记录 vs 当前」，而是凭空放行")
        if ".equals(" not in expired_body:
            problems.append("`proofExpired` 没有**比较**（`.equals(`）记录与当前邻域 ⇒ 变成无条件重评")
        if "MAX_STALE_PROOF_RETRIES" not in expired_body:
            problems.append("`proofExpired` 没有**上限**（`MAX_STALE_PROOF_RETRIES`）⇒ 邻域反复变化时无限重试（空转）")

    # ---- 臂④：失败时记录 + 硬拒绝不记 ----
    mine_body = method_body(mj, "private Task.Status mine()")
    if "proofWitness.put(" not in mine_body:
        problems.append("失败分支没有记**这一刻的邻域证明**（`proofWitness.put(`）⇒ 没有证明可过期（重评永不触发）")
    if "isHardTargetRefusal(" not in mine_body:
        problems.append("失败分支没有排除**硬拒绝**（`isHardTargetRefusal(`）⇒ 保护区/不可破坏/流体风险的目标"
                        "会在邻域变化后被反复重评（`D-323`：被拒这件事换站位/换邻域都不会变）")
    if "public static boolean isHardTargetRefusal(" not in mt:
        problems.append("`MineTask.isHardTargetRefusal` 不是 public ⇒ 作业层要么用不了它、要么自己写**第二份**拒绝清单"
                        "（`J-6`：名单只能有一处定义）")

    # ---- 臂⑤：通过性判据同源 ----
    witness_body = method_body(mj, "static String neighbourhoodWitness(")
    if not witness_body:
        problems.append("`MineJob.neighbourhoodWitness(...)` 不见了（邻域证明的唯一生成处）")
    elif "MovementHelper" not in witness_body or "canWalkThrough(" not in witness_body:
        problems.append("邻域证明没有用规划侧同一份通过性判据（`MovementHelper.canWalkThrough`）⇒ "
                        "自己造第二份「能不能穿」的真相（`K-4`：可规划即可执行）")

    # ---- 臂⑥：harness 单项预算必须比夹具自己的预算宽 ----
    budget_match = re.search(r'CheckStep\.of\("mine_vein_propagation".*?,\s*(\d+)\)\)', mod, re.S)
    fixture_budget = re.search(r"BUDGET_TICKS\s*=\s*(\d+)", fx)
    if not budget_match:
        problems.append("`MiningModule` 里找不到 `mine_vein_propagation` 步骤的**单项预算**（解析失败？）")
    elif not fixture_budget:
        problems.append("夹具里找不到 `BUDGET_TICKS`（判据口径不明）")
    elif int(budget_match.group(1)) <= int(fixture_budget.group(1)):
        problems.append(f"`mine_vein_propagation` 的 harness 单项预算（{budget_match.group(1)}）"
                        f"不大于夹具自己的 `BUDGET_TICKS`（{fixture_budget.group(1)}）⇒ harness 会**抢先**判 TIMEOUT，"
                        f"夹具内部的预算与读数永远轮不到（2026-09-24 实测：能力已到 `mined` ~19 却连 SUMMARY 都没打印）")
    return problems


def rule_diagonal_side_single_source():
    """`P2`/`D-425`（2026-09-24，**Diagonal 切片**）：**对角两侧格的准入只能有一处判据**。

    <h3>事实（读码可核，不是推断）</h3>
    `DiagonalExecutionFactory.validate` 原先**先**调规划侧共享谓词 `MovementHelper.canTraverse`，
    **再**手搓一份"两侧格 + 各自 `above()` 必须可穿过"并给出 `DIAGONAL_SIDE_COLLISION`。
    两处算的是**同一批格子、同一个 `canWalkThrough`**（`canTraverse` 的 `|dx|=|dz|=1` 分支用
    `(from.x+dx, from.y, from.z)`；执行侧用 `(to.x, from.y, from.z)`，`|dx|=1` 时相等）⇒
    侧格被堵时**第一句就返回** `DIAGONAL_INVALID_PRECONDITION` ⇒ 那个码**永远不可达**（死码），
    而 `canTraverse` 还多查玩家扫掠 ⇒ 重复判定是它的**真子集**。
    对照 Baritone `movements/MovementDiagonal.java:194-195`（`pb0`/`pb2`）与 `:220-223`
    （`getMiningDurationTicks`）：Baritone 把侧格当**可挖（成本化）**，Alice 走 `D-076` 纯通行
    ⇒ 那处差异**有意保留**（登记在 `D-425`）；但"判据只许有一处"是 `K4-P1`，死码是 `K5` 同族。

    <h3>五条臂（各有一条注入）</h3>
    ① 执行工厂的 `validate` **必须**仍引用 `canTraverse`（单源还在）；
    ② 执行工厂的 `validate` **不许**出现 `canWalkThrough(`（不许手搓侧格判定 ⇒ 注入 A 复活重复判定 ⇒ 红）；
    ③ ①的对称面：`MovementHelper.canTraverse` 体内**必须**仍有对角侧格那 4 项检查
       （否则删掉执行侧那份、规划侧也没了 ⇒ 侧格变成可穿墙 ⇒ 注入 B 删掉规划侧检查 ⇒ 红）；
    ④ 死码 `DIAGONAL_SIDE_COLLISION` **不许在生产路径（`pathing/` 的代码，注释不算）复活**
       （注入 C 写回该字面量 ⇒ 红；夹具 `task/` 里提它的名字是**判据本身**，故不在扫描范围内）；
    ⑤ 夹具必须留着那条契约：`place_step_diagonal` 里要有 `SIDE` 用例 + `sideCollisionContract(`
       + 断言码是 `DIAGONAL_INVALID_PRECONDITION`（注入 D 删掉夹具用例 ⇒ 红 —— 判据不许被悄悄撤掉）。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    factory = base / "pathing/core/DiagonalExecutionFactory.java"
    helper = base / "pathing/MovementHelper.java"
    fixture = base / "task/PlaceStepDiagonalCheckTask.java"

    def code(path):
        return code_only(path.read_text(encoding="utf-8")) if path.exists() else None

    problems = []
    fac = code(factory)
    hel = code(helper)
    fix = code(fixture)
    for text, name in ((fac, factory.name), (hel, helper.name), (fix, fixture.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则 `P2`/`D-425`）")
    if problems:
        return problems

    validate_body = method_body(fac, "public ValidationResult validate(")
    if not validate_body:
        problems.append("`DiagonalExecutionFactory.validate(...)` 不见了（本规则的主对象）")
    else:
        if "canTraverse(" not in validate_body:
            problems.append("`DiagonalExecutionFactory.validate` 不再引用规划侧共享谓词 `canTraverse(` ⇒ "
                            "对角准入失去唯一来源（`K4-P1`）")
        if "canWalkThrough(" in validate_body:
            problems.append("`DiagonalExecutionFactory.validate` 又出现手搓的 `canWalkThrough(` ⇒ "
                            "与 `canTraverse` 重复的侧格判定回来了（`D-425` 删掉的就是这一段）")

    traverse_body = method_body(hel, "public static boolean canTraverse(")
    if not traverse_body:
        problems.append("`MovementHelper.canTraverse(...)` 不见了（共享谓词）")
    elif ("sideX" not in traverse_body or "sideZ" not in traverse_body
          or traverse_body.count("canWalkThrough(") < 4):
        problems.append("`MovementHelper.canTraverse` 体内缺少对角侧格的 4 项检查"
                        "（`sideX`/`sideZ` 及各自 `.above()`）⇒ 侧格变成可穿墙（对角穿角）")

    # ④ 死码不许在**生产路径**（`pathing/`）复活；夹具在 `task/` 里可以提它的名字（判据要断言"码不是它"）
    for path in sorted((base / "pathing").rglob("*.java")):
        raw = path.read_text(encoding="utf-8")
        # 先剥块注释（`/* … */`）再剥行注释：判据只看代码，注释里提它是正常的（本规则的说明就提了）
        hit = code_only(re.sub(r"/\*.*?\*/", "", raw, flags=re.S))
        if "DIAGONAL_SIDE_COLLISION" in hit:
            problems.append(f"{path.relative_to(ROOT)} 里复活了死码 `DIAGONAL_SIDE_COLLISION` ⇒ "
                            "它在 `D-425` 里已被证明不可达（执行侧先调 `canTraverse`）并退役")

    # ⚠️ 判据要**咬断言表达式本身**，不能只咬方法名/字面量：第一版只查 `sideCollisionContract(`
    # 与 `DIAGONAL_INVALID_PRECONDITION` ⇒ 把契约掏空（断言恒真）也能过（注入 D 第一次**没红**，已修）。
    if "sideCollisionContract(" not in fix:
        problems.append("夹具 `PlaceStepDiagonalCheckTask` 缺少对角两侧格的契约 `sideCollisionContract(` ⇒ "
                        "删掉判据不会被发现")
    elif '"DIAGONAL_INVALID_PRECONDITION".equals(code)' not in fix:
        problems.append("夹具的对角侧格契约**没有真的断言拒绝码**"
                        "（缺 `\"DIAGONAL_INVALID_PRECONDITION\".equals(code)`）⇒ 契约可以被掏空成恒真")
    return problems


# `P2` Traverse 片（2026-09-24）：**"搭石/破通"这一族（Baritone 的 `MovementTraverse` 在 Alice 被拆成
# `TRAVERSE` + `PLACE_STEP_AND_TRAVERSE` + `BREAK_AND_TRAVERSE`）的规划侧/执行侧必须查同一批谓词**。
# 为什么需要门禁：`D-376` 的事故形态就是"一侧查了、另一侧没查"（规划产出物理上过不去的段 ⇒ 真机顶着
# 格边界原地走 222 tick ×2）；`D-379` 是同一形态的另一处（中间列立不住 ⇒ 从中间列掉进水里）。
PLACE_STEP_SHARED_PREDICATES = (
    "bodyPassable(",        # 目的地整体通行（脚位 + 头位）
    "canWalkOn(",          # 目的地支撑 / 中间列支撑
    "canSweepPlayer(",     # 过过渡空间（`D-376` 的扫掠盒；`NO_SWEEP` 码就来自它）
    "canWalkThrough(",     # 放置位可替换 / 目标列可穿
    "hasPlacementFace(",   # 有可用放置面（`PLACE_NO_VALID_FACE`）
    "findPlaceableSlot(",  # 有可放材料（`PLACE_RESOURCE_UNAVAILABLE`，两侧都查；出处**有意留债**）
)
# 人口下限：低于它 ⇒ 红（防"删掉表项让规则静默失效" —— `Z4` 的空集教训）
PLACE_STEP_SHARED_PREDICATES_MIN = 6

# `P2` Pillar 片（2026-09-24）：`PILLAR` 的**起跳门控**（`PILLAR_NOT_ON_GROUND`）只许对
# "脚位不是水"那支成立（`D-243`：水里按住跳跃即上浮；`D-244` 的水柱支全程 `onGround` 恒假），
# 且目标格净空必须用**规划侧的同一个谓词** `bodyPassable`（两侧不许各自手搓 `canWalkThrough`）。
PILLAR_SHARED_PREDICATES = (
    "bodyPassable(",        # 目的地整体通行（脚位 + 头位）—— `appendPillar` 的准入就是它
    "canWalkThrough(",      # 放置位（`from`）必须可替换
    "hasPlacementFace(",    # 有可用放置面（`PLACE_NO_VALID_FACE`）
    "findPlaceableSlot(",   # 有可放材料（`PLACE_RESOURCE_UNAVAILABLE`；出处**有意留债**）
)
PILLAR_SHARED_PREDICATES_MIN = 4

# `P2` Fall 片（2026-09-24）：`FALL` 的三个执行/规划现场（规划侧 `appendFall` + `fallRecoverable`、
# 执行运行时 `preconditionsHold`、执行准入 `validate`）必须查**同一批谓词**。
# 为什么：同一判据写三份 ⇒ 必然漂移（`D-374` 的"只查脚位不查头位"就是手搓出来的）。
# ⚠️ 两组分开：**回收守卫只在规划侧与准入侧**（运行时不需要 —— 那是"这条边该不该存在"的事实，
#    计划期已定），硬要求三处齐备会假红。
FALL_LANDING_PREDICATES = (
    "bodyPassable(",       # 走离边缘（脚位 + 头位）—— 三处同源
    "canStandCentered(",   # 落点可站（`canWalkOn` + 脚位/头位可穿）—— 三处同源（K-4/D-167）
    "isBottomSlab(",       # 落点支撑不是底部半砖
)
FALL_LANDING_PREDICATES_MIN = 3
FALL_RECOVERY_PREDICATES = (
    "canWalkThrough(",     # 落点上方 drop+1 格的净空（PILLAR 回程头位）
    "hasPlacementFace(",   # 落点旁有可用放置面
    "countThrowaway(",     # 有 ≥drop 个一次性方块
)
FALL_RECOVERY_PREDICATES_MIN = 3
# 手工把 `bodyPassable` / `canStandCentered` 拆开的**具体写法**（三处都不许再出现）。
FALL_HANDROLLED_CLEARANCE = (
    "canWalkThrough(context.level(), edge)",
    "canWalkThrough(context.level(), edge.above())",
    "canWalkThrough(level, edge)",
    "canWalkThrough(level, edge.above())",
)
FALL_HANDROLLED_LANDING = (
    "canWalkOn(context.level(), to)",
    "canWalkThrough(context.level(), to)",
    "canWalkThrough(context.level(), to.above())",
)


def rule_fall_landing_parity():
    """`P2` Fall 片（2026-09-24）：**`FALL` 的边缘/落点/回收三层判据必须三处同源**。

    <h3>为什么（都是既有形态，不是假想）</h3>
    ① **走离边缘**：规划侧 `appendFall` 用 `bodyPassable(edge)`，而执行侧两处**各自手搓**了
       `canWalkThrough(edge) && canWalkThrough(edge.above())` —— 逐字等价，但那是 `D-374` 事故的
       形状（手搓那份漏了头位 ⇒ "脚位空、头位实"的目的地被静默砍掉）；
    ② **落点可站**：规划侧 `appendFall` 与执行运行时 `preconditionsHold()` 用的是
       `canStandCentered(to)`（`MovementHelper:207-211` = `canWalkOn` + 脚位/头位可穿，K-4/D-167 的
       唯一定义），**只有执行准入 `FallExecutionFactory.validate` 手搓了同样三句** ⇒ 三处同源；
    ③ **回收守卫**（`drop` 格能否用 PILLAR 回来）：规划侧有具名谓词 `fallRecoverable(...)`，
       执行准入把同样三条（净空 / 放置面 / 一次性方块 ≥ drop）又抄了一遍 ⇒ 两侧必须**同步**，
       否则会回到 `D-376`/`D-379` 的形态（规划产出执行必拒的边 ⇒ 确定性重规划循环）。

    <h3>四条臂（各有一条注入，全部单独开火变红）</h3>
    ① 边缘判据：三处都必须有 `bodyPassable(`，且都不许出现手搓形态
       （注入 A 准入侧手搓 ⇒ 红；注入 B 运行期手搓 ⇒ 红）；
    ② 落点判据：三处都必须有 `canStandCentered(`，且都不许出现手搓的三句
       （注入 C 准入侧手搓 ⇒ 红；注入 D 规划侧改成 `canWalkOn` ⇒ 红）；
    ③ 回收守卫：规划侧 `fallRecoverable(...)` 与执行准入都必须含
       `FALL_RECOVERY_PREDICATES` 三条（注入 E 规划侧丢 `hasPlacementFace` ⇒ 红；
       注入 F 准入侧丢 `countThrowaway` ⇒ 红）；
    ④ 两张表的人口下限（注入 G 删表项 ⇒ 红）。

    <h3>⚠️ 为什么"回收守卫"只做**同步门禁**、不做代码单源</h3>
    三条谓词在两侧逐字相同，本可以抽成一个共享谓词；但三个**拒绝码**必须留在
    `*ExecutionFactory.java` 里 —— `rule_k4_capability_provenance` 的人口下限
    （`REFUSAL_CODES_MIN`）就是按"扫 `*ExecutionFactory.java`"立的，把码搬进 `MovementHelper`
    会让**人口掉下去**，而那与"正则退化"在读数上**长得一样** ⇒ 那才是真的把告警弄瞎。
    所以这里选**同步门禁**（`D-426` 的 `rule_place_step_parity` 同形），并把这个取舍写进 `D-428`。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    provider = base / "pathing/core/search/SurfaceMovementProvider.java"
    factory = base / "pathing/core/FallExecutionFactory.java"
    execution = base / "pathing/core/FallExecution.java"
    problems = []
    for path in (provider, factory, execution):
        if not path.exists():
            problems.append(f"{path.name} 不存在（改名？同步本规则 `D-428`）")
    if problems:
        return problems

    prov = code_only(provider.read_text(encoding="utf-8"))
    fac = code_only(factory.read_text(encoding="utf-8"))
    exe = code_only(execution.read_text(encoding="utf-8"))

    # 三个"现场"：规划侧边生成 + 执行准入 + 执行运行时
    sites = (
        (provider, prov, "规划侧边生成", "private static void appendFall("),
        (factory, fac, "执行侧准入", "public ValidationResult validate("),
        (execution, exe, "执行侧运行时", "private boolean preconditionsHold("),
    )
    for path, text, side, signature in sites:
        body = method_body(text, signature)
        if not body:
            problems.append(f"{path.name} 里找不到{side}的那个方法（{signature}）⇒ 本规则的锚点失效")
            continue
        for predicate in FALL_LANDING_PREDICATES:
            if predicate not in body:
                problems.append(f"{side}（{path.name}）没有查 `{predicate.rstrip('(')}` ⇒ "
                                "边缘/落点判据三处不同源（一侧查了、另一侧没查 = `D-374` 形态）")
        for shape in FALL_HANDROLLED_CLEARANCE + FALL_HANDROLLED_LANDING:
            if shape in body:
                problems.append(f"{side}（{path.name}）把共享谓词手搓回 `{shape}` ⇒ "
                                "同一判据两处出处（`K4-P1`；手搓那份会漏头位）")

    # ③ 回收守卫：规划侧具名谓词 + 执行准入，三条谓词必须同步
    recovery_sites = (
        (provider, prov, "规划侧回收守卫", "private static boolean fallRecoverable("),
        (factory, fac, "执行侧准入", "public ValidationResult validate("),
    )
    for path, text, side, signature in recovery_sites:
        body = method_body(text, signature)
        if not body:
            problems.append(f"{path.name} 里找不到{side}的那个方法（{signature}）⇒ 本规则的锚点失效")
            continue
        for predicate in FALL_RECOVERY_PREDICATES:
            if predicate not in body:
                problems.append(f"{side}（{path.name}）的回收守卫没有查 `{predicate.rstrip('(')}` ⇒ "
                                "规划侧与执行侧的 `FALL_NOT_RECOVERABLE_*` 判据不同步"
                                "（`D-376`/`D-379` 的形态：规划产出的边执行必拒）")

    # ④ 表人口下限
    if len(FALL_LANDING_PREDICATES) < FALL_LANDING_PREDICATES_MIN:
        problems.append(f"`FALL_LANDING_PREDICATES` 只剩 {len(FALL_LANDING_PREDICATES)} 条"
                        f"（下限 {FALL_LANDING_PREDICATES_MIN}）⇒ 删表项就能让本规则静默失效")
    if len(FALL_RECOVERY_PREDICATES) < FALL_RECOVERY_PREDICATES_MIN:
        problems.append(f"`FALL_RECOVERY_PREDICATES` 只剩 {len(FALL_RECOVERY_PREDICATES)} 条"
                        f"（下限 {FALL_RECOVERY_PREDICATES_MIN}）⇒ 删表项就能让本规则静默失效")
    return problems


# 把 `bodyPassable(to)` 手工拆回两次 `canWalkThrough` 的**具体写法**（两侧都不许再出现）。
PILLAR_HANDROLLED_CLEARANCE = (
    "canWalkThrough(context.level(), to)",
    "canWalkThrough(context.level(), to.above())",
    "canWalkThrough(level, to)",
    "canWalkThrough(level, to.above())",
)


def rule_place_step_parity():
    """`P2` Traverse 片（2026-09-24）：**搭石/破通族的规划侧与执行侧必须查同一批谓词**。

    <h3>为什么（全部是既有事故的形态，不是假想）</h3>
    ① `D-376`（2026-09-21 真机）：`PLACE_STEP_AND_TRAVERSE` 只查了"站进去放得下"，
       **没查从上一层走下来的过渡空间** ⇒ 规划产出物理上过不去的段（`segment_stall … segmentTicks=222` ×2）
       ⇒ 两侧都补 `canSweepPlayer`（执行侧码 = `PLACE_STEP_AND_TRAVERSE_NO_SWEEP`）；
    ② `D-379`（2026-09-22 真机）：破通移动没查**中间列**立不立得住 ⇒
       "破坏中间列之后走到 `to`"这个承诺是假的（从中间列掉进水里）⇒ 两侧都补 `canWalkOn(mid)`
       （执行侧码 = `BREAK_AND_TRAVERSE_NO_MID_SUPPORT`）。
    ⇒ 判据 = **这些谓词必须同时出现在两侧**；只在一侧 = 回归（要么规划产出执行必拒的边，
    要么执行放行规划永不会生成的边）。

    <h3>两条臂（各有一条注入）</h3>
    ① 逐条谓词：`PLACE_STEP_SHARED_PREDICATES` 里每一个都必须在**执行侧**与**规划侧**都出现
       （注入 A 删执行侧 `canSweepPlayer(` ⇒ 红；注入 B 删规划侧 `canWalkOn(` ⇒ 红）；
    ② 人口：表本身不得短于 `PLACE_STEP_SHARED_PREDICATES_MIN`（注入 C 删表项 ⇒ 红）。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    exec_factory = base / "pathing/core/PlaceStepAndTraverseExecutionFactory.java"
    provider = base / "pathing/core/search/SurfaceMovementProvider.java"
    problems = []
    if len(PLACE_STEP_SHARED_PREDICATES) < PLACE_STEP_SHARED_PREDICATES_MIN:
        problems.append(f"共享谓词表只剩 {len(PLACE_STEP_SHARED_PREDICATES)} 条（下限 "
                        f"{PLACE_STEP_SHARED_PREDICATES_MIN}）⇒ 删表项就能让本规则静默失效")
    for path, side, signature in ((exec_factory, "执行侧", "public ValidationResult validate("),
                                  (provider, "规划侧", "private static void appendPlaceStepAndTraverse(")):
        if not path.exists():
            problems.append(f"{path.name} 不存在（改名？同步本规则 `P2`）")
            continue
        body = method_body(code_only(path.read_text(encoding="utf-8")), signature)
        if not body:
            problems.append(f"{path.name} 里找不到 {side}的那个方法（{signature}）⇒ 本规则的锚点失效")
            continue
        for predicate in PLACE_STEP_SHARED_PREDICATES:
            if predicate not in body:
                problems.append(f"{side}（{path.name}）没有查 `{predicate.rstrip('(')}` ⇒ "
                                "两侧谓词不齐（`D-376`/`D-379` 的事故形态：一侧查了、另一侧没查）")
    return problems


def rule_pillar_water_admission():
    """`P2` Pillar 片（2026-09-24）：**水柱支的起跳门控 + 两侧净空谓词同源**。

    <h3>为什么（真机证据，不是假想）</h3>
    ① `PillarExecutionFactory.validate` 原先**无条件**要求 `bot.onGround()`。而 `D-244` 的水柱支
       （`from`/`to` 都是水）里 `onGround` **恒假** —— 这是**执行侧自己写着的契约**
       （`PillarExecution.postconditionHolds` 水柱支原文：「水里没有 `onGround`、也没有支撑」，
       且 `preconditionsHold()` **故意**不查它）。⇒ 灌水竖井 ≥3 格时**第 2 段起**在准入处被
       `PILLAR_NOT_ON_GROUND` 挡下 = 「规划得到、执行不了」（`D-242` 家族）。
       真机证据 = `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md:554`（`PILLAR_NOT_ON_GROUND` ×11，
       现场 = 破掉脚下 → 落水 → 沉底 → 溺水）；`D-244` 的夹具只有 **2 格水 = 1 段** ⇒ 这条缝量不到。
    ② `validate` / `PillarExecution.preconditionsHold` 各自**手搓**了一份"脚位 + 头位可穿"
       （两次 `canWalkThrough`），而规划侧 `appendPillar` 用的是 `bodyPassable` —— 同一判据两处出处
       （`D-374` 的事故形态：手搓那份只查脚位、漏头位）。

    <h3>四条臂（各有注入，全部单独开火变红）</h3>
    ① `PILLAR_NOT_ON_GROUND` 的判据里必须有"脚位是水"的条件（注入 A 改回无条件 ⇒ 红）；
    ② 三处（执行侧准入 / 执行侧运行时 / 规划侧）都必须用 `bodyPassable(`，且都不许出现手工拆开的
       `canWalkThrough(<...>, to[.above()])`（注入 B 准入侧手搓 ⇒ 红；注入 C 运行期侧手搓 ⇒ 红；
       注入 D 规划侧丢掉 `bodyPassable` ⇒ 红）；
    ③ 表人口下限（注入 E 删表项 ⇒ 红）；
    ④ 夹具：前提必须是**实测**的 `!bot.onGround()`（注入 F 改成恒真 ⇒ 红）＋干地对照必须咬
       **精确拒绝码**（注入 G 掏空 ⇒ 红）。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    factory = base / "pathing/core/PillarExecutionFactory.java"
    execution = base / "pathing/core/PillarExecution.java"
    provider = base / "pathing/core/search/SurfaceMovementProvider.java"
    fixture = base / "task/PillarDiagnosticTask.java"
    problems = []
    for path in (factory, execution, provider, fixture):
        if not path.exists():
            problems.append(f"{path.name} 不存在（改名？同步本规则 `D-427`）")
    if problems:
        return problems

    # code_only 只剥 `//`（块注释会留下）⇒ 判据里的窗口以**代码行**为准
    fac = code_only(factory.read_text(encoding="utf-8"))
    exe = code_only(execution.read_text(encoding="utf-8"))
    prov = code_only(provider.read_text(encoding="utf-8"))
    fix = code_only(fixture.read_text(encoding="utf-8"))

    # ---- 臂① 起跳门控必须带"脚位是水"这个条件 ----
    at = fac.find("PILLAR_NOT_ON_GROUND")
    if at < 0:
        problems.append("`PillarExecutionFactory` 里找不到 `PILLAR_NOT_ON_GROUND` ⇒ 本规则的锚点失效")
    elif "isWater(" not in fac[max(0, at - 400):at]:
        problems.append("`PILLAR_NOT_ON_GROUND` 的判据里没有「脚位是水」的条件 ⇒ 水柱支"
                        "（`D-244`：`onGround` 恒假）第 2 段起全被挡在**准入**处"
                        "（`D-242` 家族的「规划得到、执行不了」，真机 ×11 见规则头部）")

    # ---- 臂② 两侧净空谓词同源 ----
    for path, text, side, signature in (
            (factory, fac, "执行侧准入", "public ValidationResult validate("),
            (execution, exe, "执行侧运行时", "private boolean preconditionsHold("),
            (provider, prov, "规划侧", "private static void appendPillar(")):
        body = method_body(text, signature)
        if not body:
            problems.append(f"{path.name} 里找不到{side}的那个方法（{signature}）⇒ 本规则的锚点失效")
            continue
        for predicate in PILLAR_SHARED_PREDICATES:
            if predicate not in body:
                problems.append(f"{side}（{path.name}）没有查 `{predicate.rstrip('(')}` ⇒ "
                                "两侧谓词不齐（一侧查了、另一侧没查 = `D-374`/`D-376` 的事故形态）")
        for shape in PILLAR_HANDROLLED_CLEARANCE:
            if shape in body:
                problems.append(f"{side}（{path.name}）把 `bodyPassable` 手搓回 `{shape}` ⇒ "
                                "同一个判据两处出处（`K4-P1`；手搓那份只查脚位、漏头位）")

    # ---- 臂③ 表人口下限 ----
    if len(PILLAR_SHARED_PREDICATES) < PILLAR_SHARED_PREDICATES_MIN:
        problems.append(f"`PILLAR_SHARED_PREDICATES` 只剩 {len(PILLAR_SHARED_PREDICATES)} 条"
                        f"（下限 {PILLAR_SHARED_PREDICATES_MIN}）⇒ 删表项就能让本规则静默失效")

    # ---- 臂④ 夹具：实测前提 + 精确拒绝码 ----
    # ⚠️ 第一版只查 `!bot.onGround()` 这个**子串** ⇒ **注入 F（把它掏成恒真）没红**：夹具里
    # 「干地悬空」那条前提也写着同样的子串 ⇒ 判据太糙（`D-425` §四的同一课）。改成咬**合取形态**
    # `&& !bot.onGround()` —— 三条实测事实合成的那条前提是唯一一处这样写的。
    if not re.search(r"&&\s*!bot\.onGround\(\)", fix):
        problems.append("`PillarDiagnosticTask` 里没有**实测** `&& !bot.onGround()` 的前提判据 ⇒ "
                        "「水里没有 onGround」退化成引用文档（前提没了，判据就悬空；"
                        "第一版判据只咬子串 ⇒ 注入实测假绿，见本规则注释）")
    if '"PILLAR_NOT_ON_GROUND".equals(' not in fix:
        problems.append("`PillarDiagnosticTask` 的干地对照没有咬精确表达式 "
                        "`\"PILLAR_NOT_ON_GROUND\".equals(code)` ⇒ 判据可以被掏空成恒真"
                        "（`D-425` 的教训）")
    return problems


def rule_write_truth_single_source():
    """`RC4`（2026-09-24）：**"我方写了多少世界"只有一个真相；没发生的写入不许留在账上**。

    <h3>为什么（两份不同源的真事故，不是推测）</h3>
    三本账各司其职，但**同一个量**只能有一个出处：
    · `WorldModLedger` = **义务与残留**的真相（"还欠多少"，含 `RC3` 的不可逆事实）；
    · `WriteBudget` = **闸门 + 人口**（"还让不让写" / "这次窗口写了多少次，含区外"）；
    · `WriteAudit` = **逐条审计明细**（谁授权、写了哪一格）。
    而 `consumeBreak` 在**会话开始前**扣账，破坏却可能在很多 tick 之后才被证明**根本没发生**
    ⇒ `D-323` 真机现场（`BreakRefusedCheckTask` 头部原话）：FTB 认领内 4 次破坏全打了
    `WriteBudget breaks=1/64`，而存档里那 4 格仍是 `minecraft:dirt`。`D-323` 只修好了**报告**，
    **扣账留着** ⇒ 预算账说"写了 N 次"、世界与审计说"一次都没写" = 同一量两份真相。

    <h3>四条臂（各有一条注入）</h3>
    ① **世界没变 ⇒ 退回扣账**：`WriteBudget.refundBreak` 存在，且 `BlockBreakSession.fail(...)`
       （所有"没成功"的终态都走它）与 `BlockInteraction.breakForBulkEdit` 的 `world_unchanged` 分支都调用；
    ② **放置侧的既有正确形状**（回归锁）：`placeAt` 里 `consumePlace(` 必须在"方块真的落地"判据之后
       （"失败不占额度"）—— 破坏那一侧要补齐的就是这条原则；
    ③ **义务口径唯一**：`WriteBudget` **不得**提供"待收/残留"类 API（待收只许问账本）；
    ④ **计数读数只许在夹具/探针**：`WriteBudget.breaks|places|writeCount|population` 不得出现在
       生产决策目录（`action/`（`WriteBudget` 自身除外）/`pathing/`/`job/`/`bot/`）——
       `describe(` 是允许的（它是"上限 + 计数"的证据行，`Z3` 已把它钉成同源）。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    budget = base / "action/WriteBudget.java"
    session = base / "action/BlockBreakSession.java"
    interact = base / "action/BlockInteraction.java"

    def code(path):
        if not path.exists():
            return None
        return code_only(re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S))

    problems = []
    bud = code(budget)
    ses = code(session)
    itr = code(interact)
    for text, name in ((bud, budget.name), (ses, session.name), (itr, interact.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则 `RC4`）")
    if problems:
        return problems

    # ---- 臂① 世界没变 ⇒ 退回扣账 ----
    if "public static void refundBreak(" not in bud:
        problems.append("`WriteBudget` 没有 `refundBreak(...)` ⇒ 没有任何地方能退回"
                        "「世界没变却已扣掉」的那笔账（`D-323` 的尾巴）")
    fail_body = method_body(ses, "private Status fail(")
    if "refundBreak(" not in fail_body:
        problems.append("`BlockBreakSession.fail(...)` 没有退回预算 ⇒ 所有「没成功」的终态"
                        "（`REFUSED` 世界没变 / 超时 / 中止）都会把「没发生的破坏」留在账上")
    bulk = method_body(itr, "public static boolean breakForBulkEdit(")
    if "world_unchanged" in bulk and "refundBreak(" not in bulk:
        problems.append("`breakForBulkEdit` 的 `world_unchanged` 分支没有退回预算 ⇒ "
                        "第二条破坏路径仍在记假账")

    # ---- 臂② 放置侧的"落地之后才计数"（回归锁）----
    # ⚠️ `placeAt` 有**两个重载**（带/不带 `wanted` 方块），且签名跨行 ⇒ 不去抠签名，
    # 直接查**整个文件里的先后顺序**：`consumePlace(` 必须出现在"方块真的落地"判据之后。
    #（第一版抠签名 ⇒ 只拿到第一个重载、规则自己假红一次；记录在台账 `RC4-进度`。）
    at_consume = itr.find("consumePlace(")
    if at_consume < 0:
        problems.append("`BlockInteraction` 里找不到 `consumePlace(` ⇒ 放置根本没进预算"
                        "（本规则的前提变了，同步它）")
    else:
        # ⚠️ 必须**只看 `consumePlace(` 所在方法内部**：第一版拿"整个文件里 canBeReplaced() 的第一次
        # 出现"比位置 ⇒ 注入实测（把落地判据挪到扣账之后）**没红**，因为文件里别处还有同名调用。
        # 教训同 `Z2`：判据太糙 = 假绿。
        method_start = itr.rfind("\n    public static ", 0, at_consume)
        window = itr[method_start if method_start > 0 else 0:at_consume]
        if "canBeReplaced()" not in window:
            problems.append("`placeAt` 里 `consumePlace(` **之前**没有「方块真的落地」判据"
                            "（`canBeReplaced()`）⇒ 失败的放置尝试会占额度（这正是破坏侧犯过的错）")

    # ---- 臂③ 义务口径唯一 ----
    names = re.findall(r"public static [\w<>\[\], .]*?\s(\w+)\(", bud)
    bad = [n for n in names
           # ⚠️ 别把 `…Allowed` 当"待收"：第一版写了 `owed` ⇒ 三条闸门 API 全被误报
           #（`breakAllowed`/`placeAllowed`/`plannedWritesAllowed`），规则自己假红了一次。
           if re.search(r"pending|unreclaim|residue|leftover", n, re.I)]
    if bad:
        problems.append(f"`WriteBudget` 出现了「待收/残留」类 API {bad} ⇒ 同一个量出现第二个真相"
                        "（「还欠多少」只许问账本 `WorldModLedger`/`closure(...)`）")

    # ---- 臂④ 计数读数只许在夹具/探针 ----
    counted = ("WriteBudget.breaks(", "WriteBudget.places(", "WriteBudget.writeCount(",
               "WriteBudget.population(")
    for sub in ("action", "pathing", "job", "bot"):
        for path in sorted((base / sub).rglob("*.java")):
            if path.name == "WriteBudget.java":
                continue
            text = code(path)
            if text is None:
                continue
            for needle in counted:
                if needle in text:
                    problems.append(f"生产决策目录里读了预算计数 {needle}（{sub}/{path.name}）⇒ "
                                    "计数是**人口/遥测**，判据要用账本或夹具读数（`RC4`）")
    return problems


def rule_replay_bounded():
    """`P2b`（2026-09-24）：**同一条计划不许被无限重放** —— 重同步 / 重规划 / 段超时都要有界。

    <h3>为什么（先更正台账，再把它钉成可执行判据）</h3>
    台账 `§11 P2b` 原来写的是「**段级 `resync` 重放同一条计划无次数上限**」——
    **事实核对后该说法不成立**：`PathSession.MAX_RESYNCS = 5` 自 `d9e82c8`（2026-09-09，`D-047`）
    就存在，比 `D-394`（2026-09-22）早 13 天；`PathRetryRunner.DEFAULT_MAX_REPLANS = 2`；
    段超时走 `fail(TIMEOUT, "SEGMENT_TIMEOUT")` **结束会话**（不是重置计时器继续跑）
    ⇒ `D-394` 现场那一串振荡本来就是**上界收口**的样子（"5 次 resync" 正好等于上限）。
    ⚠️ 但**没有任何门禁钉住这三处上界** ⇒ 谁把守卫删掉一行，就会真的变成"无限重放"
    （`D-394` 的振荡形态就是那个后果）。本条 = 把"有界"从**注释里的约定**变成**构建会红的事实**。

    <h3>三条臂（各有一条注入）</h3>
    ① **重同步有界**：`PathSession` 保留 `MAX_RESYNCS`，`tryResync()` 计数（`resyncs++`）且在
       **任何状态变更之前**先判 `resyncs >= MAX_RESYNCS`；
    ② **重规划有界**：`PathRetryRunner` 保留 `maxReplans` 与上界比较，且 `replans++` 的次数
       **不超过**守卫的次数（多出来的那一次 = 无守卫自增 = 可以无限重放）；
    ③ **段超时必须收口**：段超时分支里必须是失败上报（`fail(..., "SEGMENT_TIMEOUT")`），
       不许把 `segmentTicks` 清零后继续跑（那正是"同一条计划无限重放"的另一种写法）。
    """
    base = ROOT / "src/main/java/com/dddgn/alice"
    session = base / "pathing/core/session/PathSession.java"
    runner = base / "task/PathRetryRunner.java"

    def code(path):
        if not path.exists():
            return None
        return code_only(re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S))

    problems = []
    ses = code(session)
    run = code(runner)
    for text, name in ((ses, session.name), (run, runner.name)):
        if text is None:
            problems.append(f"{name} 不存在（改名？同步本规则 `P2b`）")
    if problems:
        return problems

    # ---- 臂① 重同步有界 ----
    if "MAX_RESYNCS" not in ses:
        problems.append("`PathSession` 不再有 `MAX_RESYNCS` ⇒ 重同步次数没有上界"
                        "（`D-394` 的同址振荡就是靠它收口的）")
    resync = method_body(ses, "private boolean tryResync(")
    guard = resync.find("resyncs >= MAX_RESYNCS")
    inc = resync.find("resyncs++")
    if inc < 0:
        problems.append("`tryResync()` 不再计数（`resyncs++`）⇒ 重放次数没有读数，上界也就无从守起")
    if guard < 0:
        problems.append("`tryResync()` 没有在入口判上界（`resyncs >= MAX_RESYNCS`）⇒ "
                        "同一条计划可以被无限重放")
    elif inc >= 0 and guard > inc:
        problems.append("`tryResync()` 的上界判据出现在 `resyncs++` **之后** ⇒ 先动状态后判界，"
                        "最后一次仍会越过上界")

    # ---- 臂② 重规划有界 ----
    if "maxReplans" not in run:
        problems.append("`PathRetryRunner` 不再有 `maxReplans` ⇒ 重规划没有上界")
    guards = run.count("replans < maxReplans")
    incs = run.count("replans++")
    if guards == 0:
        problems.append("`PathRetryRunner` 里没有任何 `replans < maxReplans` 守卫 ⇒ 重规划无界")
    elif incs > guards:
        problems.append(f"`PathRetryRunner` 里 `replans++` 有 {incs} 处、上界守卫只有 {guards} 处 ⇒ "
                        "存在**无守卫自增**（那一次重规划可以无限重复）")

    # ---- 臂③ 段超时收口 ----
    at_timeout = ses.find("segmentTicks > segmentTimeoutTicks()")
    if at_timeout < 0:
        problems.append("`PathSession` 里找不到段超时判据（`segmentTicks > segmentTimeoutTicks()`）⇒ "
                        "段级重放可能变成无界（本规则的前提变了，同步它）")
    else:
        at_code = ses.find("SEGMENT_TIMEOUT", at_timeout)
        window = ses[at_timeout:at_code if at_code > 0 else at_timeout + 400]
        if at_code < 0 or "fail(" not in window:
            problems.append("段超时分支没有**失败上报**（`fail(..., \"SEGMENT_TIMEOUT\")`）⇒ "
                            "段卡住时不会再收口")
        if "segmentTicks = 0" in window:
            problems.append("段超时分支把 `segmentTicks` **清零后继续跑** ⇒ 段预算被无限续杯"
                            "（等价于同一条计划无限重放）")
    return problems


def rule_write_budget_zone_and_container_exception():
    """`Z3`（2026-09-23）：**额度只有一处出处；容器轴是唯一例外；瞬时码只有一个拼法**。

    <h3>为什么（审计挖出来的真缺陷，不是推测）</h3>
    `D-372` 把闸门改成"默认不限"之后，**同一个量在 `WriteBudget` 里出现了 6 个读者**
    （`consumeBreak` / `consumePlace` / `plannedWritesAllowed` / `breakAllowed` / `placeAllowed` /
    `describe`），而 `P1-a`（2026-09-22 真机根因）只修好了 `remaining*` 两个 ⇒ 其余**全都有生产调用者**却
    仍回退 `Caps.DEFAULT`(64/32)：`MovementContext:175 → plannedWritesAllowed`（**A* 的写边谓词**）、
    `MineCandidateSource:462` / `BlockInteraction:419 → breakAllowed`、`BlockInteraction:308/569 → placeAllowed`。
    ⇒ 破满 64 次后，闸门说"还能改"，搜索/预检说"改不动了" ⇒ 计划**静默降级为纯通行**
    （正是 `P1-a` 描述过的"隧道挖不动"复发），且证据行把上限印错。
    这类失败**不报错**（`silent-measurement-failure` §5：同一个量的多个副本）。

    <h3>三条（各一条注入臂 ⇒ 改任一处即红）</h3>
    ① **非容器读者全部走 `effectiveCaps(...)`**（区内/区外同一套：默认不限、显式装订优先）；
    ② **容器轴是唯一例外**（`consumeContainerWrite` / `remainingContainerWrites` 回退 `Caps.DEFAULT`），
       且 `Caps.UNBOUNDED` **不许**顺手把它放开（用户 2026-09-23 裁定：保留容器上限 ——
       容器是别人的存储 `D-076`，与地皮归属正交）；
    ③ **瞬时码 `write_budget_exhausted` 只许有一个出处**（`WriteBudget.EXHAUSTED_CODE`）——
       它跨内核→作业→归因→日志传递，拼错一个字母就静默丢归因。

    <p>另断言判据还在：`WriteBudgetCheckTask` 必须保留**野外前提自证**与 `capForEscape` 对比臂
    （否则"区外无额度"这条判据会退化成一句注释）。
    """
    budget_path = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "action"
                   / "WriteBudget.java")
    fixture_path = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"
                    / "WriteBudgetCheckTask.java")
    if not budget_path.exists() or not fixture_path.exists():
        return ["`WriteBudget.java` 或 `WriteBudgetCheckTask.java` 不存在（改名？同步本规则）"]

    def strip_block_comments(text: str) -> str:
        return re.sub(r"/\*.*?\*/", "", text, flags=re.S)

    budget = code_only(strip_block_comments(budget_path.read_text(encoding="utf-8")))
    fixture = code_only(strip_block_comments(fixture_path.read_text(encoding="utf-8")))
    problems = []

    # ---- 臂① 非容器读者同源 ----
    readers = {
        "public static Verdict consumeBreak(": "破坏闸门",
        "public static Verdict consumePlace(": "放置闸门",
        "public static boolean plannedWritesAllowed(": "计划期剪枝（A* 写边谓词）",
        "public static int remainingBreaks(": "剩余破坏读数",
        "public static int remainingPlaces(": "剩余放置读数",
        "public static boolean breakAllowed(": "内核破坏谓词",
        "public static boolean placeAllowed(": "放置预检",
        "public static String describe(": "证据行",
        "public static void notePlaceRefusal(": "提前拒绝记账",
    }
    for signature, label in readers.items():
        body = code_only(method_body(budget, signature))
        if not body:
            problems.append(f"找不到 `{signature.strip()}`（{label}）—— 改名？同步本规则")
        elif "effectiveCaps(" not in body:
            problems.append(f"{label}（`{signature.split()[-1]}`）没走唯一的额度出处 "
                            f"`effectiveCaps(...)` ⇒ 它读的是**另一个副本**"
                            f"（`P1-a`/`Z3`：闸门说不限、这里说 64 ⇒ 计划静默降级）")

    # ---- 臂② 容器轴是唯一例外 ----
    container = code_only(method_body(budget, "public static Verdict consumeContainerWrite("))
    container_left = code_only(method_body(budget, "public static int remainingContainerWrites("))
    for body, label in ((container, "`consumeContainerWrite`"),
                        (container_left, "`remainingContainerWrites`")):
        if "Caps.DEFAULT" not in body:
            problems.append(f"{label} 不再回退 `Caps.DEFAULT` ⇒ 容器轴（别人的存储，`D-076`）"
                            f"被卷进了 `D-372`/`D-398` 的放开（用户 2026-09-23 裁定：保留容器上限）")
    # 容器列在**三处**出现：两个读者 + 收尾 SUMMARY 的证据行（`closeScope` 要印"容器 x/32"这个真数）
    close_body = code_only(method_body(budget, "public static void closeScope("))
    rest = budget
    for body in (container, container_left, close_body):
        rest = rest.replace(body, "", 1)
    if "Caps.DEFAULT" in rest:
        problems.append("`WriteBudget` 里除容器轴外还出现了 `Caps.DEFAULT` ⇒ 破坏/放置轴上又有了一处 "
                        "`64/32` 兜底（`Z3`：唯一出处是 `effectiveCaps`）")
    if "return CAPS.getOrDefault(scopeId, Caps.UNBOUNDED);" not in code_only(
            method_body(budget, "private static Caps effectiveCaps(")):
        problems.append("`effectiveCaps(...)` 的兜底不再是 `Caps.UNBOUNDED`（`D-372` 的默认不限被改回去了）")
    unbounded = re.search(r"public static final Caps UNBOUNDED\s*=\s*new Caps\(([^;]*)\);", budget)
    if not unbounded or "DEFAULT_MAX_CONTAINER_WRITES" not in unbounded.group(1):
        problems.append("`Caps.UNBOUNDED` 的**容器份额**不是 `DEFAULT_MAX_CONTAINER_WRITES` ⇒ "
                        "装一个「不限的破坏/放置额度」会**顺手放开容器轴**（与用户裁定冲突）")

    # ---- 臂③ 瞬时码只有一个出处 ----
    literal = '"write_budget_exhausted"'
    offenders = []
    for path in (ROOT / "src" / "main" / "java").rglob("*.java"):
        if path.name == "WriteBudget.java":
            continue
        if literal in strip_block_comments(path.read_text(encoding="utf-8")):
            offenders.append(path.name)
    if offenders:
        problems.append(f"瞬时码字面量 {literal} 出现在 WriteBudget 之外：{sorted(offenders)} ⇒ "
                        f"改用 `WriteBudget.EXHAUSTED_CODE`（拼错一个字母 = 静默丢归因）")
    if 'public static final String EXHAUSTED_CODE = "write_budget_exhausted";' not in budget:
        problems.append("`WriteBudget.EXHAUSTED_CODE` 不在了（瞬时码失去唯一出处）")

    # ---- 判据本身还在（不是退化成注释）----
    if "ProtectionZones.isWild" not in fixture:
        problems.append("`WriteBudgetCheckTask` 没有**野外前提自证** ⇒ 「区外无额度」这条判据会退化成一句注释")
    if "capForEscape(" not in fixture:
        problems.append("`WriteBudgetCheckTask` 没有 `capForEscape` 对比臂 ⇒ 「显式装订照旧强制」这半没判据")
    return problems


def rule_vacuous_assertions_carry_population():
    """`Z4`（2026-09-23）：**「我没写世界 / 没残留」的断言必须带人口；义务读数必须只认保护区内**。

    <h3>为什么（`Z1` 引出的假绿，清单见 `docs/reviews/2026-09-23-Z2-…md §6`）</h3>
    `Z1` 让账本**在区外不记账** ⇒ 一族「账本里没有我方临时方块」的断言在野外**恒真**（空集）。
    它不报错、也不变红，只是**失去意义**（本项目纪律：假绿比假红危险）。`Z2` 给了「人口读数」
    这件工具，本规则把它**钉在这些站点上**，防止哪天被顺手删掉。

    <h3>两条口径（各一条注入臂）</h3>
    ① **「零写入」类**断言（探针 / 只用现成的步）必须判**闸门计数的真实写入次数 = 0**
       （`WriteBudget.writeCount(...)`，**与区无关** ⇒ 不会空集；且比「账本空」更强）；
    ② **「无残留」类**断言与**义务读数**必须报出人口，且义务读数只认区内
       （`pendingTemporaryProtected`）—— 用跨 scope 的 owner 口径会把区外 / 旧存档遗留算成「欠着」。
    """
    base = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
    zero_write = {
        "task/CraftTableCheckTask.java": "craft_table（只用现成工作台）",
        "task/CraftGridProbeTask.java": "合成网格探针",
        "task/MachineProbeTask.java": "机器探针",
        "task/MachineStationProbeTask.java": "机器站点探针",
    }
    leftovers = {
        "task/CraftStationCraftCheckTask.java": "合成站（摆 / 收工作站）",
        "task/CraftStationProvisionCheckTask.java": "工作站部署检查",
        "task/CraftFurnaceCheckTask.java": "熔炉检查",
        "task/PathingRegressionTask.java": "寻路回归的场景清理",
        "task/CleanupWrappedTask.java": "诊断包装器的收尾",
        "task/RecoverabilityCheckTask.java": "可回收性残留读数",
    }
    problems = []

    def stripped(rel: str) -> str:
        path = base / rel
        if not path.exists():
            return ""
        return code_only(re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S))

    for rel, label in zero_write.items():
        body = stripped(rel)
        if not body:
            problems.append(f"{rel} 不存在（{label}）—— 改名？同步本规则")
        elif "writeCount(" not in body:
            problems.append(f"{label}（`{rel}`）的「零写入」判据没有人口读数 "
                            f"`WriteBudget.writeCount(...)` ⇒ `Z1` 之后它在野外是**空集**（恒真、不报错）")
    for rel, label in leftovers.items():
        body = stripped(rel)
        if not body:
            problems.append(f"{rel} 不存在（{label}）—— 改名？同步本规则")
        elif "population(" not in body:
            problems.append(f"{label}（`{rel}`）的「无残留」判据没报**覆盖度 / 人口**"
                            f"（`WriteBudget.population(...)`）⇒ 读数会被误读成「很干净」")

    manager = stripped("bot/BotManager.java")
    if not manager:
        problems.append("`bot/BotManager.java` 不存在 —— 改名？同步本规则")
    elif "pendingTemporaryProtected(" not in method_body(
            manager, "public static String teardownRecoveryDecision("):
        problems.append("残留续做决策（`BotManager.teardownRecoveryDecision`）用的是跨 scope 的 owner 口径 "
                        "⇒ 会把**区外**条目算成「我们欠着」（`D-398` R2：区外一定不恢复）")

    snapshot = stripped("decision/DecisionSnapshot.java")
    if not snapshot:
        problems.append("`decision/DecisionSnapshot.java` 不存在 —— 改名？同步本规则")
    # ⚠️ 必须钉**属性发射**本身：文案里提到 `writesThisScope` 不算（第一版判据被自己的提示语满足 ⇒
    # 注入实测没红，本会话第 8 次「判据太弱」）。
    elif "pendingTemporaryProtected(" not in snapshot \
            or 'addProperty("writesThisScope"' not in snapshot:
        problems.append("喂给 LLM 的世界事实（`DecisionSnapshot.worldMod`）既没把义务口径收成区内，"
                        "也没真的发出 `writesThisScope` 属性 ⇒ 那个零会被读成「没改过世界」")

    if "public static String population(" not in stripped("action/WriteBudget.java"):
        problems.append("`WriteBudget.population(...)` 不在了 ⇒ `Z4` 的人口读数工具没了")
    return problems


def grep_symbol_exists(name):
    """该符号是否在 src/main/java 下真实出现（防"编造出处"）。"""
    needle = name + "("
    for path in (ROOT / "src/main/java").rglob("*.java"):
        try:
            if needle in path.read_text(encoding="utf-8"):
                return True
        except OSError:
            continue
    return False


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
    searchbackoff = rule_mine_job_search_limit_backoff()
    detour = rule_standing_point_detour_bounded()
    scan = rule_scan_advances_every_select()
    writecaps = rule_write_caps_default_open_protection_kept()
    ticksearch = rule_tick_search_account_enforced()
    tlb = rule_tick_load_budget_declared()
    approachbound = rule_approach_plans_bounded()
    bodyclear = rule_edge_destination_body_clearance()
    collectgoal = rule_collect_goal_standable()
    sweepclearance = rule_height_change_sweep()
    hazardnotgated = rule_hazard_not_task_gated()
    routeclosure = rule_head_blocked_route_closure()
    btfooting = rule_break_traverse_footing()
    d385 = rule_break_cost_state_penalty()
    capability = rule_k4_capability_provenance()
    z2 = rule_ledger_closure_zone_scoped()
    rc3 = rule_lossy_write_accounted()
    a3 = rule_kill_drop_attributed()
    p7 = rule_battery_nonpass_steps_listed()
    p3 = rule_job_area_grant_scoped()
    rc4 = rule_write_truth_single_source()
    p2b = rule_replay_bounded()
    z3 = rule_write_budget_zone_and_container_exception()
    z4 = rule_vacuous_assertions_carry_population()
    pl1 = rule_stale_proof_replan()
    diagside = rule_diagonal_side_single_source()
    psparity = rule_place_step_parity()
    pillarwater = rule_pillar_water_admission()
    fallparity = rule_fall_landing_parity()
    latch = rule_terminal_latch_replays_status()
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
    for line in searchbackoff:
        print(f"[D-388·搜索受限要跨 tick 摊销] {line}")
    for line in detour:
        print(f"[D-370·不许绕远] {line}")
    for line in scan:
        print(f"[D-371·每次选择都推进扫描] {line}")
    for line in writecaps:
        print(f"[D-372·默认不限+权限层保留] {line}")
    for line in ticksearch:
        print(f"[A1·每tick搜索总账] {line}")
    for line in tlb:
        print(f"[P4·tick负载预算] {line}")
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
    for line in routeclosure:
        print(f"[D-378·夹缝路线收口] {line}")
    for line in btfooting:
        print(f"[D-379·破通行要站得住] {line}")
    for line in d385:
        print(f"[D-385·挖矿成本含状态惩罚] {line}")
    for line in capability:
        print(f"[K4·准入来源单一] {line}")
    for line in z2:
        print(f"[Z2·账本闭合口径] {line}")
    for line in rc3:
        print(f"[RC3·不可逆写入记账] {line}")
    for line in a3:
        print(f"[A3·击杀产物归属] {line}")
    for line in p7:
        print(f"[P7·非PASS步单列] {line}")
    for line in p3:
        print(f"[P3·作业级收集授权] {line}")
    for line in rc4:
        print(f"[RC4·写入真相同源] {line}")
    for line in p2b:
        print(f"[P2b·重放有界] {line}")
    for line in z3:
        print(f"[Z3·额度同源+容器例外] {line}")
    for line in z4:
        print(f"[Z4·空集断言要带人口] {line}")
    for line in latch:
        print(f"[A1′·终态闩锁回放] {line}")
    for line in pl1:
        print(f"[PL-1·过期证明重评] {line}")
    for line in diagside:
        print(f"[P2·对角侧格单源] {line}")
    for line in psparity:
        print(f"[P2·搭石族两侧谓词] {line}")
    for line in pillarwater:
        print(f"[P2·水柱起跳门控] {line}")
    for line in fallparity:
        print(f"[P2·落点三层同源] {line}")
    ok = (not k4 and not k5 and not s8 and not walk and not np and not risk and not speech
          and not perm and not death and not dmg and not prog and not s10 and not f1
          and not prog_default and not j5 and not r2 and not r2p2 and not r2p3 and not ring
          and not noperm and not loop and not bwg and not d344 and not attr and not s3 and not s5 and not intent and not clusters and not value and not refused and not lock and not kinds and not clearance and not breakcost and not support and not inplace and not contract and not searchbudget and not searchbackoff and not detour and not scan and not writecaps and not ticksearch and not approachbound and not bodyclear and not collectgoal and not sweepclearance and not hazardnotgated and not routeclosure and not btfooting and not d385 and not capability and not z2 and not z3 and not z4 and not latch and not rc3 and not rc4 and not p2b and not a3 and not p7 and not p3 and not pl1 and not diagside and not psparity and not pillarwater and not fallparity) and not tlb
    print(f"KERNEL_PREDICATE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"工厂谓词漂移={len(k4)} / 死状态={len(k5)} / 死字段复活={len(s8)} / 行走无界={len(walk)} / 失败当进度={len(np)} / 风险画像未接={len(risk)}"
          f" / 编排器步边界={len(r2)} / 步清单={len(r2p2)} / 步边界对齐={len(r2p3)} / 结构化归因={len(attr)} / 搜索受限≠没有={len(s3)} / 扫描记忆无位置={len(s5)} / 意图先于可挖性={len(intent)} / 簇只做几何={len(clusters)} / 价值只是成本分量={len(value)} / 世界侧拒绝要归因={len(refused)} / 实测锁要挡LLM={len(lock)} / 种类分配={len(kinds)} / 清障不吃任务目标={len(clearance)} / break进成本={len(breakcost)} / 垫方块与簇顺序={len(support)} / 视线内就地挖={len(inplace)} / 移动契约一致={len(contract)} / 搜索预算={len(searchbudget)} / 搜索受限摊销={len(searchbackoff)} / 不许绕远={len(detour)} / 扫描推进={len(scan)} / 写上限={len(writecaps)} / 每tick搜索总账={len(ticksearch)} / tick负载预算={len(tlb)} / 模式B穷举有界={len(approachbound)} / 目的地整体通行={len(bodyclear)} / 收集目标可站={len(collectgoal)} / 高度变化查过渡空间={len(sweepclearance)} / 危险处理不挂任务={len(hazardnotgated)} / 夹缝路线收口={len(routeclosure)} / 破通行要站得住={len(btfooting)} / 挖矿成本含状态惩罚={len(d385)}"
          f" / 准入来源单一={len(capability)} / 账本闭合口径={len(z2)} / 不可逆写入记账={len(rc3)} / 击杀产物归属={len(a3)} / 非PASS步单列={len(p7)} / 作业级收集授权={len(p3)} / 写入真相同源={len(rc4)} / 重放有界={len(p2b)} / 额度同源与容器例外={len(z3)} / 空集断言人口={len(z4)} / 过期证明重评={len(pl1)} / 对角侧格单源={len(diagside)} / 搭石族两侧谓词={len(psparity)} / 水柱起跳门控={len(pillarwater)} / 落点三层同源={len(fallparity)}（未指名能力类="    f"{rule_k4_capability_provenance.unresolved}/{CAPABILITY_UNRESOLVED_BUDGET}，总准入码={rule_k4_capability_provenance.total}）"
          f"（K4-P1/K5-P1/S8-P1/W-P1/NP-P1/S6-P1/F4-P1/R2-P1/R2-P2/R2-P3/M4-P1 —— 见各规则头部的注释）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
