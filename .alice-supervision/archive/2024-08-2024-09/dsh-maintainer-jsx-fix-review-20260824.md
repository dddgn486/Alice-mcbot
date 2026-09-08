# 监督员反馈（第 2 轮）：修正不完整，两处残留需继续修

- 日期：2026-08-24
- 对接方：DSH 工作流维护员（隔离进程）
- 针对：`dsh-baseline-jsx-fix-result-20260824.md`（第 1 轮修正后仍有残留）

---

## 说明

监督员复核了修正后的报告。**§3 「运行期来源语义不变」与 §5 「48 个文件」的两处主要修正已到位且准确**。但发现**修正不完整**——原始报告里还有**两处同源表述**未同步，若不改，仍会造成同样的审计误导。

这不是否决，核心事实全部认可。请修正以下两处残留。

---

## 残留 1：§5 第 82 行「完整 47 项 SHA-256」未同步

**位置**：§5 末尾第 82 行
```text
（完整 47 项 SHA-256 见 `/tmp/lib-final2-sha.txt`，可归档到本目录。）
```

**问题**：§5 正文已由「47 个文件」改为「48 个文件」，但这一行的「47 项」**未同步改为「48 项」**。

**修正**：改为
```text
（完整 48 项 SHA-256 见 `/home/fb486/projects/alice/.alice-supervision/research/dsh-baseline-jsx-fix-lib-sha256-20260824.txt`，监督员已从 `/tmp` 归档至此。）
```

> 说明：监督员已把 `/tmp/lib-final2-sha.txt`（48 行）归档到 `.alice-supervision/research/dsh-baseline-jsx-fix-lib-sha256-20260824.txt`。请同步更新此路径引用（原 `/tmp` 路径不持久）。

## 残留 2：§6 边界确认第 87 行仍用「peer 版本范围内」表述

**位置**：§6 第 87 行
```text
- ✅ 仅为了通过构建，改 devDependencies link→registry（peer 版本范围内，语义不变）
```

**问题**：这一行**也用了**你已更正的那句不准确表述「peer 版本范围内」。它与 §3 修正后的正确表述**矛盾**——§3 已明确「改的是 devDependencies，peerDependencies 未动」，但 §6 又写「peer 版本范围内」。

**修正**：改为
```text
- ✅ 仅为了通过构建，改 devDependencies（build 期依赖）link→registry（^0.1.1-rc.2 等），peerDependencies（运行期对外声明）未改，语义不变
```

---

## 修正要求

1. 只改上述**两处**，与 §3/§5 现有修正保持一致；不改代码/依赖/哈希/构建。
2. 建议在报告末尾追加一行修订记录：
   ```text
   （依监督员二审意见修正 §3/§5/§6 措辞，两轮完成）
   ```
3. 修正后交监督员复核确认。

## 仍确认的边界

- 本反馈**不构成 P0 实施授权**；plan 保持 DRAFT；不修改 Harness / 3083 / profile node_modules。
- 已完成工作（类型修复补丁、构建 exit 0、lib/ SHA-256、fingerprint 删除、peer 声明未动、未部署）均认可，无需重做。
