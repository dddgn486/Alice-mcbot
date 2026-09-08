---
name: alice-scene-based-testing
description: 用"一键场景 + 一键自检"把真人测试压缩到两次操作，并保证证据可判读、失败不被掩盖。
---
# Alice Scene-Based Testing

## 何时使用

- 新增或修改任何**可观察行为**（Movement、寻路、任务、GUI、物理、同步）需要真人客户端验证时；
- 用户抱怨"测试太繁琐""要输入坐标""不知道从哪开始测"时；
- 设计测试入口、测试物品、诊断任务、数据包场景时；
- 需要判断"这次改动是否真的被验证过"时。

## 核心原则

```text
一次动作覆盖全部信息
  + 零参数入口（物品右键 > 无坐标命令）
  + 场景是孤立长方体（边界外一圈空气）
  + 结果机器可判读（SUMMARY key=VALUE）
  + 失败必须诚实（不被 PASS 掩盖、不把预算耗尽当不可达）
  + 视觉结论必须问用户（日志证明不了观感）
```

测试的目的不是"跑一遍"，而是**让 AI 能从日志独立判定，让真人只需看少数无法日志化的现象**。

---

## 1. 测试入口设计（零参数优先）

| 优先级 | 形式 | 例子 |
|---|---|---|
| 1 | 游戏内交互物品（右键 / Shift+右键） | `alice:pathing_battery`、`alice:pathing_planner` |
| 2 | 一条无坐标命令 | `/alice pathing plan-here`、`/alice pathing chain east 3` |
| 3 | 数据包函数（建场景） | `/function alice_test:pathing_course` |
| ❌ | 要求用户输入坐标/长参数/自己算位置 | 禁止 |

**场景专属测试器**：只负责"启动测试"，起点与终点由场景固定（硬编码常量并与场景函数注释对齐），
不依赖玩家站在哪里。参考 `PathingBatteryItem.COURSE_START_FOOT`、`PathingSessionItem.COURSE_GOAL_FOOT`。

---

## 2. 一键场景（数据包函数）

**模板**（`tools/test-scenes/<pack>/data/<ns>/functions/<scene>.mcfunction`）：

```text
function <ns>:<scene>_reset          # 先清空外扩区域
fill <内部区域> <支撑层> stone        # 建造测试结构
...                                  # 特征地形（台阶/坑/链）
tp @s <观察点>                        # 玩家到观察点，不占测试点
give @s <场景专属测试物品>
tellraw @s [操作说明]
```

**孤立长方体规则（强制）**：

- 场景必须定义为一个**长方体区域**；
- 该区域**边界外至少一圈（含上方与下方）为空气**，不得与其他场景或自然地形相连；
- **不要求封闭房间**，开阔场景即可；只有需要防 bot 走出/掉落时才加围墙或区域底部隔离地板；
- `reset` 函数必须先把外扩区域整体清空为空气，再建造内部结构；
- **判定标准**：站在场景内任意位置，四周/上方/下方一圈都不存在非本场景方块。

参考实现：`tools/test-scenes/alice_test/data/alice_test/functions/pathing_course*.mcfunction`。

---

## 3. 一键自检夹具（Battery）

把多个检查项合并为**一个任务**，一次交互跑完，末尾一行汇总：

```text
[R3 Battery] SUMMARY plan_flat=REACHED(2) plan_up=REACHED(1) plan_budget=SEARCH_LIMIT
             traverse=PASS diagonal=PASS ascend=PASS descend=PASS chain2=PASS
```

必须遵守：

1. **结果键值化**：`key=VALUE`，状态/失败码明确，AI 可直接读日志判读；
2. **每项独立**：每个子项开始前把 bot 锚回统一起点（日志 `anchor_to_start`），避免顺序耦合；
3. **缺地形记 `SKIP`**，不算失败；
4. **失败覆盖 PASS**：多段项出现"段1 PASS、段2 失败"时，汇总必须是失败码，不能被 `putIfAbsent` 掩盖；
5. **候选同源**：夹具检测"可测什么"必须复用规划器的 `MovementProvider`，保证"可规划即可执行"；
6. **bot 自动就位**：没有 bot 自动生成、已有 bot 自动锚定到固定起点，不要求用户 `/alice spawn`；
7. **只报告不决策**：夹具输出事实（PASS/失败码），不替任务层决定重试或放弃。

参考实现：`PathingBatteryTask`、`PathingSessionDiagnosticTask`。

---

## 4. 证据与验收

- AI **主动**读 Windows `latest.log` / `debug.log`，不要让用户整理日志；
- 关键行按前缀过滤（如 `[R3 Battery]`、`[R4 Session]`、`[R2-C ...]`）；
- 证据归档到 `.alice-supervision/client-tests/<topic>-<date>/evidence/`，含
  `*-key-lines.log` 与 `evidence-report.md`（记录工件 SHA-256、场景、入口、结果表、待确认项）；
- 验证等级严格区分：`IMPLEMENTED` / `COMPILES` / `SERVER_TESTED` / `WINDOWS_CLIENT` / `USER_ACCEPTED`；
- **构建成功 ≠ 客户端可用**。

---

## 5. 视觉结论必须问用户

日志能证明"位置对了"，**证明不了观感**。出现下列任一情形必须主动询问，不得用"日志里看起来是…"代替：

- 是否跳跃、贴墙滑动、卡边缘、抖动回弹、踩半砖、穿模、紫黑贴图、橡皮筋；
- 服务端日志显示"到位"但玩家看到偏移/悬空；
- 具体方块类型/半砖朝向/流体/模组方块行为；
- 复现频率（必现/偶发）与是否需要特定朝向、速度、前序动作；
- 日志盲区（探针未覆盖的分支、日志被轮转）。

询问时至少覆盖：**用了什么物品/命令 + 具体按键、预期与实际差异、复现频率、截图/视频、日志片段**。

---

## 6. 探针生命周期

```text
测试失败/行为异常
  -> 加临时探针（关键检查点 + 足够上下文：tick/坐标/状态/条件值）
  -> 跑一次拿到证据
  -> 定位根因 + 修复
  -> 验证通过后【立即删除全部临时探针】
  -> 只保留终态日志（started/completed/failed/rejected）
```

反模式：把探针留在生产代码里；用探针日志长期替代验收标准。

---

## 7. 反模式清单（禁止）

- ❌ 让用户输入坐标、长参数串或自己计算位置；
- ❌ 让用户手搭测试地形（必须数据包函数一键生成）；
- ❌ 场景与已有场景/地形相连（边界外必须有空气圈）；
- ❌ 多个检查项拆成多次交互（能合并就合并）；
- ❌ 夹具与内核判定分叉（必须复用规划器/执行器的判定）；
- ❌ 汇总用 `putIfAbsent` 掩盖后段失败；
- ❌ 把 `SEARCH_LIMIT` 当成 `UNREACHABLE`；
- ❌ 用日志推断代替真人视觉确认。

---

## 8. 完成前自检

- [ ] 入口是物品或一条无坐标命令，写在 `docs/TESTING_GUIDE.md`；
- [ ] 场景是孤立长方体、边界外一圈空气、有 `_reset` 函数；
- [ ] 夹具一次动作跑完，输出 `SUMMARY key=VALUE`；
- [ ] 每项独立（锚回固定起点），缺地形记 `SKIP`；
- [ ] 失败覆盖 PASS，失败码可分类；
- [ ] 场景与夹具源码已入库（`tools/test-scenes/`、`src/main/...`）；
- [ ] AI 已读日志、证据已归档、验证等级已标注；
- [ ] 视觉结论已向用户提问，临时探针已删除。

全部勾选 = **PASS**；任一项未勾选 = **FAIL**。
