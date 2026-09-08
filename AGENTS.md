# Alice Agent Instructions

Alice 是 Minecraft Forge 1.20.1 项目。这里的规则用于帮助 AI 在会话切换和上下文压缩后保持方向，不是严格审批或提交流水线。

## 每次会话先做

1. 确认仓库路径和 Git 状态；
2. 阅读 `docs/AI_PROJECT_STATE.md`、`docs/AI_DEVELOPMENT_PLAYBOOK.md`、`docs/AI_DECISIONS.md` 和 `docs/AI_TEST_MATRIX.md`；
3. 根据技术领域读取 `.alice-supervision/skills/` 中相关 skill：
   - 设计/修改测试入口、测试物品、数据包场景、自检夹具 → **`alice-scene-based-testing`（必读）**
   - 同一问题失败 2+ 次 / 证据冲突 → `debugging-root-cause-analysis`、`failure-pattern-recognition`
   - 实体物理/碰撞/移动 → `forge-entity-physics-collision`；实体同步 → `forge-entity-sync-broadcast`
   - GUI/容器 → `forge-container-menu-protocol`；事件 → `forge-event-priority-cancel`
   - 客户端/服务端划分 → `minecraft-client-server-sync`；假人生命周期 → `forge-fakeplayer-lifecycle`
   - 跨模块或 >3 文件 → `minimal-implementation-planning`
4. 向用户复述当前目标、成功条件和不改变的边界；
5. 先选择一个最小可验证闭环，再开始实现。

## 当前开发方式

讨论需求 → 读取 skills → 设计最小闭环 → 实施 → 编译/聚焦测试 → 同步 Windows `D:\JAVA_projects\alice\` → 用户用游戏内测试物品和键盘鼠标实测 → 讨论日志/截图/现象与根因 → 再决定是否修复。

不要求每个小改动创建 active plan、工作包、审核包、HANDOVER 或执行旧监督脚本。提交、推送和文档更新按当前协作需要处理。

## 不可悄悄改变的架构边界

- LLM 只做目标级决策；确定性执行器负责动作、权限、安全和完成条件；
- 服务端是世界、bot、任务和库存的真相；
- `MineTask`、`DropCollectionTask` 和普通挖矿保持 `HARD_PATH`；
- `SOFT_SURFACE` 不得隐式接入挖矿、拾取、道路、隧道、流体或逃生；
- `SEARCH_LIMIT` 不是 `UNREACHABLE`，不能自动授权挖隧道；
- 未知模组能力默认只读，不让 AI 猜槽位、配方或写入语义。

## 客户端测试规则

- 涉及渲染、实体、物理、GUI、同步或玩家交互时，Windows 客户端测试是必要证据；Linux/WSL 编译和服务端日志不能替代真人观察；
- 优先使用可 `/give` 获得的游戏内测试物品，通过右键/Shift+右键观察 GUI、bot、粒子、聊天和日志；避免让用户输入复杂坐标命令；
- **测试入口必须零参数**：优先"游戏内物品右键"，其次"一条无坐标的简单命令"（如单词方向）。**禁止**要求用户输入坐标、长参数串或自己计算位置；新测试功能先做成物品或一键命令再交给用户。测试流程详见 `docs/TESTING_GUIDE.md`；
- **测试夹具一次动作覆盖全部**：需要特定地形时用数据包函数一键生成（`/function alice_test:<scene>`，参考 `tools/test-scenes/`），不要求用户手搭地形；**测试场景必须是孤立长方体区域——区域边界外至少一圈（含上下）为空气，不得与其他场景/地形相连；不要求封闭房间，开阔场景即可，必要时才加围墙或底部隔离地板防 bot 走出/掉出**；多项检查合并为一个自检任务（一次右键跑完，输出 `SUMMARY key=VALUE`）；每个子项开始前把 bot 复位到统一起点保证独立；夹具检测候选必须复用规划器 provider（保证"可规划即可执行"）；场景与夹具源码必须入库；
- AI 应主动查找可访问的 Windows runtime 日志；不可访问时明确请用户提供，不得假称已经读取；
- 测试反馈要主动询问操作、预期/实际、复现频率、截图和 `latest.log`/`debug.log` 片段。
- **物理场景必须主动询问**：当关键证据需要分辨具体物理场景、而日志无法完整还原客户端物理效果时（动作/视觉事实如是否跳跃、贴墙滑动、卡边缘、抖动回弹、踩半砖；服务端说"到位"但玩家看到偏移/穿模/悬空；具体方块/半砖/流体状态；复现频率；日志盲区），**必须主动问用户**，不得凭日志或代码推断下结论。禁止用"日志里看起来是…""按代码应该会…"替代用户观察宣称客户端物理已验证。

## 修复规则

看到 bug 或可能的修复方式后，不要立即改代码。先确认用户操作和现象，检查当前代码/工件，提出根因假设，区分事实与推测，再和用户讨论最小修复。相同问题第二次失败或证据冲突时，使用 `debugging-root-cause-analysis` / `failure-pattern-recognition`，停止盲目增加参数、超时和特判。

## 报告验证等级

始终区分 `IMPLEMENTED`、`COMPILES`、`SERVER_TESTED`、`WINDOWS_CLIENT`、`USER_ACCEPTED`。构建成功不代表客户端可用。
