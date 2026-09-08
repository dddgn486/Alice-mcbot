---
name: alice-client-artifact-acceptance
description: 确认 Windows 客户端实际运行的是本轮构建工件，并区分代码、日志和真人观察证据。
---
# Alice Client Artifact Acceptance

## 何时使用
任何需要 Windows 客户端验证的构建、资源、GUI、实体、网络、输入或物理修改时使用。

## 核心原则
检查过的源码/JAR 不一定是 Windows 实际加载的 JAR。客户端结果必须绑定到本轮工件和测试时间。

## 最小工件链

```text
WSL 源码修改
 -> build JAR
 -> 同步到 D:\JAVA_projects\alice\
 -> Windows 启动实际客户端
 -> 查找 Windows runtime logs/latest.log 或 debug.log
 -> 用户反馈/截图
```

AI 应主动在 Windows 运行时目录寻找日志；如果当前环境访问不到 Windows 文件，必须明确请用户提供相关片段，不能声称已检查。

## 证据等级

```text
CODE_REVIEW / COMPILE / SERVER_LOG / WINDOWS_CLIENT / USER_ACCEPTED
```

只要服务端说成功而用户看到失败，就记录 `EVIDENCE_CONFLICT`，优先核对实际 JAR、资源路径、包接收者和客户端日志。

## 轻量证据

通常只需：操作步骤、预期/实际、一段关键日志或截图。无需为了每个小改动制作长报告。
