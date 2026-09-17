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

## ⚠️ 「客户端是不是最新」怎么证明（2026-09-17 实测补）

**坑**：Gradle 打出的 mod jar **不可字节复现** —— 同一份源码**连编两次**得到**不同** sha256
（实测 `862ef1e7…` → `9a8a7309…`；差异只在 `META-INF/MANIFEST.MF`/条目顺序/时间戳）。
⇒ 因此：

| 手段 | 能回答 | 不能回答 |
|---|---|---|
| 文件 `sha256sum` | "当时同步过去的**是同一个文件**"（跟 HANDOVER 记的值比） | ❌ "客户端里是不是**现在这版代码**"（重建一次哈希就变） |
| **内容摘要**（推荐） | "客户端里的 jar 与当前构建**内容一致**"（条目数 + 逐条目零差异） | —— |

**一条命令**：

```bash
./tools/jar-content-hash.sh --compare build/libs/alice-1.0.0-1.20.1.jar \
    "/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/mods/alice-1.0.0-1.20.1.jar"
# SAME   ⇒ 客户端就是这版代码（可写进证据链）
# DIFFER ⇒ 列出「仅 A 有 / 仅 B 有 / 内容不同」的条目，直接看出差在哪
```

- 同步脚本会同时打印 `source_sha256=`（文件身份）与 `content_sha256=`（内容身份）⇒ **HANDOVER 两个都记**。
- **同步后必须重启客户端**才会加载新 jar（jar 的 mtime 可作提示；`.bak.*` 备份文件不是 `.jar` 结尾，Forge 不会加载，无需清理）。
- 结论写法：不要写"哈希一致 ⇒ 客户端是最新"，要写"内容摘要一致（N 条目零差异）⇒ 客户端运行的是本轮构建"。
