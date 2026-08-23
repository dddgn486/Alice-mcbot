# C4 原版箱子 GUI 隔离测试报告

> 打开并使用一次普通原版箱子 GUI；不执行 Alice GUI/packet 相关操作。

## 场景说明

验证原版普通箱子界面和普通取放行为与 Alice transfer 实验隔离，不被 GUI、packet 或绑定机制拦截。

## 测试工具提示

- 准备：一个未参与当前 transfer request 的原版普通单箱，箱内放少量可识别物品。
- 操作：以正常右键方式打开箱子，查看界面；可进行一次普通取放后原样放回。
- 截图：完整原版箱子 GUI，包含物品栏和箱子内容。
- 禁止：不要对该箱子执行 `/alice transfer-test`；不要使用 Alice 专用绑定或未批准的 UI 工具。

## 本场景引用
- 箱子坐标：[填写]
- 场景结果：`PASS` / `FAIL` / `UNCLEAR`

## 最少文件清单
- `evidence-report.md`
- `C4-gui.png`：普通箱子 GUI 截图
- 共用日志：`../latest.log`、`../debug.log`

## 场景
1. GUI 是否保持原版箱子界面？`是 / 否`
2. 是否没有 Alice GUI 拦截、额外按钮或 packet/UI 替换？`是 / 否`
3. 普通查看/取放是否没有非预期写入？`是 / 否`
4. 是否没有启动转移、挖矿或其他 Bot 任务？`是 / 否`
