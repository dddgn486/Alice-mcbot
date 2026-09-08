# Alice Agent Instructions

Alice 是 Minecraft Forge 1.20.1 项目。这里的规则用于帮助 AI 在会话切换和上下文压缩后保持方向，不是严格审批或提交流水线。

## 每次会话先做

1. 确认仓库路径和 Git 状态；
2. 阅读 `docs/AI_PROJECT_STATE.md`、`docs/AI_DEVELOPMENT_PLAYBOOK.md`、`docs/AI_DECISIONS.md` 和 `docs/AI_TEST_MATRIX.md`；
3. 根据技术领域读取 `.alice-supervision/skills/` 中相关 skill；
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
- AI 应主动查找可访问的 Windows runtime 日志；不可访问时明确请用户提供，不得假称已经读取；
- 测试反馈要主动询问操作、预期/实际、复现频率、截图和 `latest.log`/`debug.log` 片段。

## 修复规则

看到 bug 或可能的修复方式后，不要立即改代码。先确认用户操作和现象，检查当前代码/工件，提出根因假设，区分事实与推测，再和用户讨论最小修复。相同问题第二次失败或证据冲突时，使用 `debugging-root-cause-analysis` / `failure-pattern-recognition`，停止盲目增加参数、超时和特判。

## 报告验证等级

始终区分 `IMPLEMENTED`、`COMPILES`、`SERVER_TESTED`、`WINDOWS_CLIENT`、`USER_ACCEPTED`。构建成功不代表客户端可用。
