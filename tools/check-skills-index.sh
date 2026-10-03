#!/usr/bin/env bash
# 门禁：`.alice-supervision/skills/README.md` **不许与现实分叉**（生成物，禁手改）。
#
# ⭐ 为什么有它（用户 2026-10-02 采纳 ①）：原 README 是**手维护 131 行、无生成器、无门禁** ——
#   而本项目 `§五 5.1` 已量出规律：索引能活 **当且仅当** ① 单一出处 ② 有门禁逐字节比对。
#   它**两条都没有** ⇒ **已烂**（列 24 行 vs 磁盘 21）。
#
# 断言（真正的强制力在 `tools/skills-index.py`）：
#   · **陈旧**：重新生成 ⇒ **逐字节相同**（手改 / 磁盘变了没重生成 ⇒ 红）；
#   · ⭐ **frontmatter 缺失** ⇒ 红 —— DSH 的 skill provider 按 frontmatter 解析，
#     缺字段的文件会被**安静地丢掉**（⛔ 不报错）= "写在磁盘上却永远到不了 agent"。
#     ⭐ 本门禁落地时**当场抓到一份**：`forge-blockpos-mutability.skill.md`（21 份里唯一没有的）；
#   · **人口下限**：磁盘 `*.skill.md` ≥ 15（目录搬了 ⇒ 响亮失败）。
#   · 自证 4 臂（好文件 / 缺 frontmatter / 人口下限 / 陈旧）。
#
# ⚠️ 它**判不了** skill 写得好不好，⛔ 也判不了"该不该用某个 skill"。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/skills-index.py --check
