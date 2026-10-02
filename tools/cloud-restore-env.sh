#!/usr/bin/env bash
# 云端环境件复原 —— **幂等**、可复跑（云端 home 不持久，rebuild 后必失）。
#
# 背景（`docs/CLOUD_MIGRATION.md` §20 / `docs/HANDOVER.md` 断点六十二 §A、六十三 §J′）：
#   云端的 **`/workspaces` 持久、家目录不持久**；一次 rebuild 会把仓外环境件整批清掉，
#   而它们**不是仓库的一部分** ⇒ 重来一次会以"引用超界 28 处"／"红线门禁红"的形式暴露。
#
# 复原的三件（都不是仓库内容）：
#   ① `$HOME/reference/baritone-1.20.1`  —— Baritone 参照树（**判据：`MovementHelper.java` = 863 行**）
#   ② `/home/fb486/projects/reference`   —— `AGENTS.md:62` 钉的**旧机绝对路径**
#   ③ `/home/fb486/projects/alice`       —— 同一根下的仓库链接
#
# ⚠️ **② ③ 需要 `/home/fb486` 存在；`/home` 属主是 `root`，`vscode` 建不出来** ⇒
#    本脚本**不假装成功**：做不到就**明确报 SKIP 并给出后果**（`check-redline-gates` 恒红）。
#
# 用法：
#   bash tools/cloud-restore-env.sh            # 复原 + 复算判据
#   bash tools/cloud-restore-env.sh --check    # 只复算，不改任何东西（rebuild 后先跑这个）
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REF_DIR="${ALICE_BARITONE_DIR:-$HOME/reference/baritone-1.20.1}"
BARITONE_REPO="https://github.com/cabaletta/baritone.git"
BARITONE_BRANCH="1.20.1"
MOVEMENT_HELPER="src/main/java/baritone/pathing/movement/MovementHelper.java"
EXPECT_LINES=863          # 判据（与 ref-integrity 的修复同一把尺子，2026-09-24 实测）
LEGACY_ROOT="/home/fb486/projects"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

say() { printf '%s\n' "$*"; }
ok()   { printf '  ✅ %s\n' "$*"; }
skip() { printf '  ⛔ SKIP %s\n' "$*"; }
bad()  { printf '  ❌ %s\n' "$*"; }

say "=== 云端环境件复原 $( ((CHECK_ONLY)) && echo '(只复算)' || echo '(复原+复算)' ) ==="
say "仓库   : $REPO"
say "参照树 : $REF_DIR"
say ""

say "[①] Baritone 参照树"
if [[ ! -d "$REF_DIR/.git" && ! -d "$REF_DIR/src" ]]; then
  if ((CHECK_ONLY)); then
    bad "不存在 ⇒ 引用解析会退化成「只索引本仓」⇒ \`ref-integrity\` 会把 Baritone 行号误判超界（实测 28 处）"
  else
    say "     不存在 ⇒ 克隆（--depth 1 --branch $BARITONE_BRANCH）"
    mkdir -p "$(dirname "$REF_DIR")"
    git clone --depth 1 --branch "$BARITONE_BRANCH" "$BARITONE_REPO" "$REF_DIR" 2>&1 | tail -3
  fi
fi
if [[ -f "$REF_DIR/$MOVEMENT_HELPER" ]]; then
  got=$(wc -l < "$REF_DIR/$MOVEMENT_HELPER" | tr -d ' ')
  [[ "$got" == "$EXPECT_LINES" ]] && ok "判据 $MOVEMENT_HELPER = $got 行（应 $EXPECT_LINES）" \
                                  || bad "判据 $MOVEMENT_HELPER = $got 行（应 $EXPECT_LINES）⇒ 树不对"
  gotv=$(grep -m1 '^minecraft_version' "$REF_DIR/gradle.properties" 2>/dev/null | cut -d= -f2)
  [[ "$gotv" == "1.20.1" ]] && ok "gradle.properties minecraft_version=$gotv" \
                            || bad "minecraft_version=${gotv:-读不到}（应 1.20.1）"
else
  bad "判据文件 $MOVEMENT_HELPER 不存在"
fi
say ""

say "[②③] 旧机绝对路径 $LEGACY_ROOT（\`AGENTS.md:62\` 钉的就是它）"
if [[ ! -d /home/fb486 ]]; then
  skip "/home/fb486 不存在 —— **必须 root 才能建**（本轮实测：\`touch /home/.probe\` → Permission denied）"
  say "     ⇒ 后果：\`check-redline-gates\` 检查②读不到该路径的 \`gradle.properties\` ⇒ **恒红**"
  say "     ⇒ 这是**环境件缺失**，不是仓库缺陷；兜底方案见 \`docs/HANDOVER.md\` 断点六十三 §K 第 6 条"
else
  for pair in "reference:$REF_DIR" "alice:$REPO"; do
    name="${pair%%:*}"; target="${pair#*:}"
    link="$LEGACY_ROOT/$name"
    if ((CHECK_ONLY)); then
      [[ -e "$link" ]] && ok "$link -> $(readlink "$link" 2>/dev/null || echo '（实目录）')" || bad "$link 不存在"
    else
      if [[ -e "$link" && ! -L "$link" ]]; then
        bad "$link 已存在且不是符号链接 ⇒ 不动它（免得覆盖真目录）"
      else
        ln -sfn "$target" "$link" && ok "$link -> $target"
      fi
    fi
  done
fi
say ""

say "[复算] 两道门禁的真实读数"
printf '  ref-integrity : '; (cd "$REPO" && python3 tools/ref-integrity.py 2>&1 | tail -1)
printf '  redline-gates : '; (cd "$REPO" && python3 tools/redline-gates.py 2>&1 | tail -1)
say ""
say "（\`redline-gates\` 红 = 环境件缺失，见上 [②③]；\`ref-integrity\` 才是参照树是否可用的判据。）"
