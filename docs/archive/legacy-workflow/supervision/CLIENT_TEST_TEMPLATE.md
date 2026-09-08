# Alice 客户端测试记录模板

> 本模板适用于所有需要 Windows/客户端验收的工作包。监督员在提交二审通过后创建测试根记录；用户只填写场景报告和总体决定。编译、headless 日志、监督审核均不能替代本记录。

## 测试根记录

- 关联提交：`<full SHA>`
- 关联计划 ID：`<plan ID>`
- 监督审核记录：`.alice-supervision/reviews/<review-id>.md`
- 测试人：`user`
- 状态：`NOT_STARTED` | `IN_PROGRESS` | `PASSED` | `FAILED` | `NEEDS_DISCUSSION`
- 测试根目录：`.alice-supervision/client-tests/<short-commit>-<package>/`
- 场景证据目录：`.alice-supervision/client-tests/<short-commit>-<package>/evidence/`

## 目录与文件规则

每个客户端场景必须使用一个独立证据包；一轮完整测试共用一份总表单和一组日志：

```text
.alice-supervision/client-tests/<short-commit>-<package>/
├── <short-commit>-<package>.md
└── evidence/
    ├── evidence-report.md   ← 本轮总表单，只填写一次
    ├── latest.log
    ├── debug.log
    ├── S1-<short-name>/
    │   ├── evidence-report.md
    │   └── S1-<observation>.png
    └── S2-<short-name>/
        └── ...
```

1. 一轮完整客户端测试只保留 `evidence/latest.log` 和 `evidence/debug.log` 各一份；用户可在同一客户端会话连续完成全部场景，无需为每个场景重启客户端、复制或拆分日志。
2. `evidence/evidence-report.md` 是本轮总表单：测试日期时间、Windows Git SHA、客户端/服务端版本、世界/存档条件、Bot、request/task ID、输入（item/count）、总体结果和用户总体决定只在此填写一次；场景表单不得重复这些字段。
3. 每个场景的 `evidence-report.md`、固定截图/录屏和该场景产生的异常材料必须同目录；不同场景截图不得混放。场景最少文件清单不得重复要求根目录的共用日志；只有确有必要时增加专用录屏或世界副本。
4. 截图/录屏使用固定、可读的文件名，例如 `S2-source-before.png`、`S2-destination-after.png`。允许先创建零字节 `.png` 占位，但用户开始测试后必须用真实文件直接覆盖；空占位不构成证据。
5. `evidence-report.md` 必须用中文，逐项说明最少动作、预期关键点、日志关键字和固定证据文件名。观察项优先写成 `是 / 否`；仅在需要解释差异时填写简短说明。
6. 场景发生异常时，保留该场景目录、共用完整日志、request/task ID 和必要世界副本；不得重试已经发生世界或库存变异的同一请求。

## 场景矩阵

| ID | 场景目录 | 最少动作 | 核心观察 | 最少证据 |
|---|---|---|---|---|
| S1 | `evidence/S1-<short-name>/` | [填写] | [填写] | `evidence-report.md`、[填写] |
| S2 | `evidence/S2-<short-name>/` | [填写] | [填写] | `evidence-report.md`、[填写] |

## 场景报告模板

在每个场景目录写入 `evidence-report.md`：

```markdown
# S<id> <场景名称>测试报告

## 本场景引用
- 对应总表 request/task ID：见 `../evidence-report.md` 的 S<id> 行
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 场景说明

[说明该场景证明的唯一行为边界，以及不验证什么。]

## 测试工具提示

- 准备的方块、物品、Bot 或存档状态：[填写]
- 使用的批准命令/测试工具与观察时机：[填写]
- 禁止动作或不得混入的其他场景：[填写]
- 固定截图/录屏的捕获时机：[填写]

## 最少操作
- 本次命令或动作：[填写]
- 测试前关键世界/库存状态：[填写]

## 核心观察
1. <可判定观察 1>？`是 / 否`
2. <可判定观察 2>？`是 / 否`
3. 日志是否出现 `<stable-code-or-key>`？`是 / 否`
4. 是否没有 <禁止行为>？`是 / 否`

## 最少文件清单
- `evidence-report.md`
- `<fixed-screenshot-name>.png`
- 共用日志：`../latest.log`、`../debug.log`

## 异常与停止
- 是否出现停止条件？`是 / 否`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
```

## 总表单模板

在 `evidence/evidence-report.md` 写入本轮唯一总表单：

```markdown
# <package> Windows 客户端完整测试报告

> 本文件只填写一次，覆盖同一客户端会话内连续完成的全部场景。`latest.log` 和 `debug.log` 是本轮唯一完整日志；场景目录只填写观察并放置专用截图。

## 基本信息
- 测试日期时间：[填写]
- Windows Git SHA：[填写]
- 世界/存档条件：[填写]
- 客户端/服务端 Forge 与 Mod 版本：[填写]
- Bot 名称/UUID：[填写]
- item/count（各请求不同则逐项列出）：[填写]
- request/task ID（按场景列出，可多个）：[填写]
- 总体结果：`PASS` / `FAIL` / `UNCLEAR`
- 用户总体决定：`USER_ACCEPTED` / `NEEDS_FIX` / `NEEDS_REPLAN` / `NEEDS_DISCUSSION`

## 本轮共用文件
- `latest.log`：本轮完整客户端日志，用实际文件覆盖。
- `debug.log`：本轮完整客户端日志，用实际文件覆盖。
- 每个 `S<id>-*` 子目录：该场景的 `evidence-report.md` 与最少专用截图。

## 场景结果汇总

| 场景 | 结果 | request/task ID（如适用） | 日志定位关键字/时间 |
|---|---|---|---|
| S1 ... | PASS / FAIL / UNCLEAR | | |
| S2 ... | PASS / FAIL / UNCLEAR | | |

## 异常与停止

- 是否出现无法解释的物品/世界变化、自动 retry/resume/finish-insert、任务替换、错误端点写入、GUI 拦截、崩溃或卡死？`是 / 否`
- 发生场景和 request/task ID：[填写]
- 是否已停止该变异请求，且未重试？`是 / 否 / 不适用`
- 说明：[填写]

## 监督员使用区
- 审核状态：待审核
- 审核备注：[留空]
```

## 共用停止条件

出现不可解释的世界/库存变化、自动 retry/resume/隐式完成、未授权任务接入、错误端点写入、GUI/交互拦截、崩溃或卡死时立即停止。保留证据包并等待监督结论；不得为了完成测试而扩大权限、切换执行策略或修改世界语义。

## 用户总体决定

用户总体决定只填写在 `evidence/evidence-report.md` 总表单中：`USER_ACCEPTED` | `NEEDS_REPLAN` | `NEEDS_FIX` | `NEEDS_DISCUSSION`。只有用户可以填写客户端观察和总体决定。监督员不得代替用户标记 `USER_ACCEPTED`。
