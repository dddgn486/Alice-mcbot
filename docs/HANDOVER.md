# 交接文档（HANDOVER）—— 2026-09-14 会话收口

> 新会话**从本文开始读**；权威细节在 `docs/AI_DECISIONS.md`（决策与实测事实）、
> `docs/OPEN_ITEMS_LEDGER.md`（开放项与逐轮实测）、`docs/BATTERY_CURATION.md`（电池分档）、
> `docs/AI_DEVELOPMENT_PLAYBOOK.md`（协作规则，含 §5.0b/§5.0c/§5.0d 三条 2026-09-13 新增纪律）。

## 1. 今天（09-13/14）做完的

| 阶段 | 状态 | 关键证据 |
|---|---|---|
| **3-A（A1–A5）合成/熔炼接进任务层** | ✅ 收口（客户端验证 + `USER_ACCEPTED`） | A4b：`smelted=true product+1 no_half_products=true`；A5：`mode=directed → craft → 世界事实 product 0→1`；CORE 曾 `(25/25)` |
| **3-B / S0 机器类型事实表** | ✅ 完成（离线，真数据） | `docs/MEKANISM_FACTS.md`：Mekanism **26 类型 / 1171 条**（`crushing` 210 领跑），总量随会话变、已标出处 |
| **3-B / S1 机器配方只读** | ✅ 完成（客户端验证） | `MachineRecipeFacts`（问上游 `getOutputDefinition()`/`getInput().getRepresentations()` + 自校验）→ `RecipeQuery.MACHINE_ROUTE`（有出处的路线，含机器类型与材料）；`CraftJob` 如实拒绝 `not_executable` |
| **3-B / S2 机器站点只读** | ✅ 完成（客户端验证） | `machine_block=mekanism:enrichment_chamber@66,64,306`、`menu=…MekanismTileContainer slots=41`、**进度=上游自述** `getScaledProgress/getOperatingTicks/getActive` |

**方向来源留档**：`docs/reviews/2026-09-14-外部质疑与工作流审查留档.md`（外部质疑三条 + 两轮工作流审查 + 设计讨论的完整来龙去脉、事实核校、裁定表、驳回项与 AI 自身教训；
想追"为什么现在这么定"就读它）。

**协议**：`docs/MOD_ADAPTER_PROTOCOL.md`（六步流水线 S0→S5；**只读先于执行**；"读不懂多少"始终可见；
进通用骨架须满足"上游自述／两上游共享／纯形态可自校验"；**反模式**：依赖上一步清场、按类名认、为适配放宽红线）。

## 2. 进行中：S3/S4（下一次继续）

- **S3（下一步，只读）**：把"**机器类型 ↔ 机器方块/菜单**"做成**单一出处**的映射表
  （像 `RecipeDump.stationFor` 那样），让 `MACHINE_ROUTE` 能回答"**去哪台机器**"；
- **S4（之后）**：单机最小闭环（放料→等→取产物）。**需要写入授权与预算**，按 D-076 走显式授权；
- **S5**：每次收尾都要回收临时探针（今天已按此回收两支：`alice:machine_probe`、
  `alice:machine_station_probe` ⇒ 任务转为电池步 `machine_route` / `machine_station`）。

## 3. 待客户端验证（不阻塞下一步）

**`alice:regression_battery`（CORE = 27 项）** ⇒ 期望 `(27/27) → PASS`，判据：
`machine_route=PASS`、`machine_station=PASS`（新步；日志含"已传送…/结束复位…"）、
`decision_contract=PASS`（上一轮补的自带传送/复位）。

## 4. 今天新增/变更的纪律（都在 PLAYBOOK + AGENTS.md 里）

1. **§5.0b 决策权**：你有最终决策权，但不必把每句话当最终决策；AI **允许并鼓励评价你的决策**；
   临时裁定要标 `（临时）` + 复核触发条件。
2. **§5.0c 继续/停止判据**：不需要你参与且**离线可做**就继续做，不要为"省你一轮"而停；
   **上下文量级 1,000,000 / 800,000 自动压缩** ⇒ 在那之前不得以"预算"为由停下。
3. **§5.0d 场景夹具两条硬纪律**：夹具**自带传送**到场景起点（不依赖电池 provision）+ **结束复位**
   （关菜单/停输入/回起点，失败路径同走）。
4. **active goal**：可用；范围设到"两次测试之前"，到测试点 `pause`（恢复只能由你发起）。
5. **上下文（2026-09-14 立规 + 当天再校准）**：**不估算、不主动报占比、不据此改输出或提前收尾**；
   目的是别让"估算"逼 AI 自己压缩输出（自动压缩照常工作，只要压得准）。真实数据一条命令：
   `bash tools/dsh-context-usage.sh`（窗口/压缩阈值/还差多少触发；要时才跑）。
   **数字只在 `AGENTS.md` 维护一次**，本文与其它文档只指向它（防漂移）。
   **让压缩准的正解**：事实先落盘（docs/台账/commit）+ 少灌原始日志（`head/cut`，全量写文件）。

## 5. 环境与入口速查

- 客户端：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（日志 `logs/latest.log`）。
- 同步：`./tools/sync-windows-artifact.sh build/libs/alice-1.0.0-1.20.1.jar /mnt/d/JAVA_projects/alice "<客户端>/mods"`；
  镜像 `./tools/mirror-windows-workspace.sh`；资源自检 `bash tools/check-item-models.sh`（当前 76 项）。
- **本轮最后同步的 jar**：`1c441fc9b9de98b2`（完整 sha256 见 `git log -1` 与 `AI_DECISIONS` 最新条目）。
- 场景：`/function alice_test:machine_course`（S2 机器场景；已同步进存档 datapack）、
  `furnace_course`、`craft_tab_course`、`craft_table_course`、`craft_station_course`。
- 电池：`alice:regression_battery`（CORE=27）/ `/alice battery full`（FULL=37）；
  唯一配置入口 `RegressionBatteryTask.CURATION`。

## 5b. 断点（2026-09-14 会话中段，上下文 ≈0.9×压缩阈值时收口）

**刚落地（已推送）**：授权/审批框架可视化 v1 —— 单一出处 `docs/authz/AUTHZ_REGISTRY.csv`（**27 道闸门 / 6 层**）
+ 生成器 `tools/authz-map.py`（零依赖，产出 `OVERVIEW.md` / `flow.svg` / 可搜索 `index.html`）
+ **防过期检查** `bash tools/check-authz-registry.sh`（断言注册表 vs 代码：拒绝码 101 / MovementType / WriteReason 全覆盖 ⇒ 当前 **PASS**）。
用户已确认"满意现在的识图"。

**下一步第一件事（新会话从这里开始）**：
1. ~~`/alice authz` 运行时命令~~ **已验证**（`BotCommand.authzSnapshot`；`SERVER_TESTED` + `WINDOWS_CLIENT`
   2026-09-14：`latest.log:202-208` 七行齐全）。零参数只读，打印 7 行——L0 当前任务 / L1 纯通行集合 /
   L3 预算余量与已拒数 / L4 账本 pending 与 scope / L4 保护区判定 / 最近终态码。用法见 `docs/TESTING_GUIDE.md` 末节。
2. **R1 集中策略表**（区域×任务类别 → `TEMP/KEEP` + Movement 集合 + 预算；默认 PROTECTED、显式降级），
   与主线 **3-B S3**（机器类型 ↔ 机器方块/菜单的单一映射）**合并成一轮离线工作**（两者同性质：建"单一出处"表）。
3. **R2/R3**（野外默认放开 `PILLAR/FALL/DOWNWARD`；`miningApproach` 改按条件放行）——**须先 A/B 客户端证据**。
4. 待用户拍板：验证等级 5→3、`AI_TEST_MATRIX` 去留、规则日落机制。

**两个已知小遗留（下次顺手处理）**：① ~~`WriteReason` 检出 14/16~~ **已查清并关闭**：`WriteReason` 真实取值就是 **14 种**（我先前数成 16，多出的 2 个来自嵌套枚举 `Policy`/`Action`）——**是检查脚本抓到我自己文档的错**，CSV 已改；顺带记下一个有用事实：`WriteReason` 每条自带分类 `Policy(EXPLICIT_TARGET/CLEARING)` + `Action(BREAK/PLACE/BOTH)`；
② `flow.svg` 无 PNG 版本（本机无 mmdc/inkscape/ImageMagick ⇒ 浏览器查看，或用时再写纯 Python 位图导出）。

**③（新，验证时从日志发现）** `/alice authz` 在**无作用域**时把破坏/放置余量打成 `2147483647`（`Integer.MAX_VALUE`）
——语义是"无作用域预算限制"，显示成天文数字易被误读；建议改成"无作用域（不受预算约束）"。
**④（新，验证时从日志发现）** 回归电池结束后的自动决策选了 240 格外的 `region:saved` 做 `region_lumber`，
401 tick 后 `FAILED code=failed:outside_region`（`chopped=0 patrols=380`，`latest.log:3828`）——失败优雅且留痕（对的），
但**目标层菜单项没带可达性/距离信息**，LLM 会据此挑到够不着的活。建议：菜单项附距离或可达性标注（未决）。

**未验证堆积**：~~`CORE 27` 尚未跑过~~ **已跑并全绿**：`PROFILE=CORE … (27/27) ticks=2845 → PASS`
（`latest.log:3801`，含新步 `machine_route` / `machine_station`）。当前**无待验证项**。

**会话摘要调查（2026-09-14，用户提问触发）**：结论 = **不必获取会话摘要，也不装第三方插件**
（摘要已原生自动产生并持久化；且 1% 量级有损 ⇒ 事实来源是原文，而压缩后原文**未丢**：1078/1078 遮蔽事件仍在磁盘、
可按 `seq` 取回）。完整取证 + 第三方生态清单 + 可选只读解码器见 `docs/reviews/2026-09-14-会话摘要调查.md`。

## 6. 未做/已知边界（不假装完成）

- **机器执行（S4）未做**：`MACHINE_ROUTE` 只报路线，`CraftJob` 明确拒绝 `not_executable`；
  化学品/气体类输出如实 `machine_output_not_item` / `MACHINE_RECIPE_UNSUPPORTED`；
- **电池瘦身**：已**回退**（D-201 附注一）——撤走 8 步会暴露隐含前置；瘦身前置=**夹具自证前提**，
  目前只落地了 `FixturePremise`（ownMenu/stationMenuOpen/onGround）+ 电池级每步自证与清场，
  其余夹具待逐条接上后才能"逐条撤 + 每条复跑"；
- Refined Storage / 精妙背包站点：未做（用户此前裁定暂缓）。
