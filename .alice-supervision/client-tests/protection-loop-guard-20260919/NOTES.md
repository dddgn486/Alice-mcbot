# 客户端证据：保护区封顶 / 夹具血脉闸门 / 循环守卫（2026-09-19）

> 归档依据：skill `alice-scene-based-testing` §4（证据必须归档：`*-key-lines.log` + `evidence-report.md`，
> 含工件 SHA-256、场景、入口、结果表、待确认项）。

## 工件与轮次

| 轮次 | 会话启动 | `JAR_CONTENT_SHA256` | 对应改动 | 用户判定 |
|---|---|---|---|---|
| 反向测试（保护区） | 19:00:06 | `3412a74a…`（封顶 `L1` + 事件环补全） | `D-338` 附注十四/十五 | 见 `docs/AI_DECISIONS.md D-338` 附注九/十/十四/十五 |
| `D-339` 复验 | 19:26:30 | `129f1749…` | 夹具终态不交 LLM | 「符合预期」 |
| `D-340`+`D-341` 复验 | 20:17:20 | `ea9d885d…` | 夹具事件不交 LLM + 「无权」≠「没有」 | 「符合预期」 |

⚠️ **诚实标注**：19:00 与 19:26 两轮的 `latest.log` **已被后续会话覆盖**（日志是会话级滚动）
⇒ 那两轮的关键行**只存在于当时的对话记录**，未落盘（本目录只归档了 20:17 那轮；
两条闸门的 `SERVER_TESTED` 反向对照不受影响，但"客户端逐行"这一级的**可复核性**只对 20:17 轮成立）。

## 本次归档轮（20:17:20，新包 `ea9d885d…`）

**入口（零参数）**：`/alice instruct "在保护区里起一个 region_lumber"`（用户自己的直连通道）
+ 保护区里右键 `alice:lumber_job` / `alice:region_lumber`。

**结果表**

| 断言 | 期望 | 实测（`evidence/key-lines.log`） | 判定 |
|---|---|---|---|
| `D-341` 端到端：LLM 自起的区域作业在保护区（封顶 `L1`）**如实失败** | 立刻 `FAILED no_permitted_candidate` | `task_terminal_reason kind=region_lumber driver=llm terminalReason=no_permitted_candidate` + `[Job] maintain no_permitted_candidate zone_break_not_allowed tree@20,64,208:…` | ✅ |
| 同上：**不再**空转 20 分钟 | 无 `viable=0` 长循环 | 对照 19:06 轮（空转到 `maxTicks=24000`） | ✅ |
| `D-340`：夹具驱动任务的 `PROGRESS` **不叫 LLM** | 只有 `trigger_dropped` | `fixture_driver（夹具驱动的事件不交给决策层）` × **23** | ✅ |
| `D-339`：夹具**终态**不叫 LLM | 只有 `trigger_dropped` | `fixture_driver（夹具终态不交给决策层）` × **1** | ✅ |
| 无漏网：LLM 只被"合法途径"叫到 | 仅 `operator/directed` | `decision_request trigger=operator mode=directed`（**全会话仅 1 次**） | ✅ |
| 保护区里**玩家自己**发起照旧干活 | 砍 + 垫方块 | 19:00 轮：`chopped=0→5`、`ALLOW … STEP_PLACEMENT`、`scaffoldLeft=0`（已在 `D-338` 附注九归档） | ✅ |

**待确认项 / 未覆盖**

- ⚠️ `D-342`（循环受理闸）**尚未客户端复验**：需要"同一目标在 1200 tick 内失败 2 次"，然后再让 LLM
  起同一目标 ⇒ 应回读 `repeat_failure（同一目标 …）`；而**玩家自己**连点同一目标 3 次 ⇒ 应**照旧放行**（豁免）。
- 视觉/物理层面本轮**无需**询问（无移动、无跳跃、无贴墙——两轮现象都是"站着不动 + 日志"）。
