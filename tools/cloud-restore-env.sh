#!/usr/bin/env bash
# 云端环境件复原 —— **幂等**、可复跑（云端 home 不持久，rebuild 后必失）。
#
# 背景（`docs/CLOUD_MIGRATION.md` §20 / `docs/HANDOVER.md` 断点六十二 §A、六十三 §J′）：
#   云端的 **`/workspaces` 持久、家目录不持久**；一次 rebuild 会把仓外环境件整批清掉，
#   而它们**不是仓库的一部分** ⇒ 重来一次会以"引用超界 28 处"／"红线门禁红"的形式暴露。
#
# 复原的两件（都不是仓库内容）：
#   ① `/workspaces/reference/baritone-1.20.1` —— Baritone 参照树（**判据：`MovementHelper.java` = 863 行**）
#      ⚠️ **2026-10-02 改**：原来是 `$HOME/reference/…`。**家目录不持久、`/workspaces` 持久**
#      ⇒ 而 `AGENTS.md:62` 现在钉的是**相对路径**（`reference/baritone-1.20.1/`，
#      由 `redline-gates.py:186` 按 `ROOT.parent` = `/workspaces` 解析）⇒ 参照树**必须在 `/workspaces` 下**。
#      ⛔ **必须是真目录，⛔ 不能是软链**：软链指向家目录 ⇒ 家目录一没，软链就断，相对钉法照样失败。
#   ② `/home/fb486/projects/reference` ＋ `/home/fb486/projects/alice`
#      —— `AGENTS.md` **改相对钉法之前**用的旧机绝对路径（2026-10-02 起**不再被钉**）
#      ⇒ 降级为**可选**（`--also-legacy`）；不再报"恒红"，因为新版 `AGENTS.md` 不读它。
#
# ⚠️ 本脚本**不假装成功**：做不到就**明确报 SKIP 并给出后果**。
#
# 用法：
#   bash tools/cloud-restore-env.sh               # 复原① + 复算判据（默认够了）
#   bash tools/cloud-restore-env.sh --check       # 只复算，不改任何东西（rebuild 后先跑这个）
#   bash tools/cloud-restore-env.sh --also-legacy # 连旧机绝对路径 ② 一起复原（仅当钉回绝对路径时）
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
#: ⚠️ 默认落 **`/workspaces/reference`**（持久盘），⛔ 不是 `$HOME/reference`（rebuild 后会没）
REF_DIR="${ALICE_BARITONE_DIR:-/workspaces/reference/baritone-1.20.1}"
BARITONE_REPO="https://github.com/cabaletta/baritone.git"
BARITONE_BRANCH="1.20.1"
MOVEMENT_HELPER="src/main/java/baritone/pathing/movement/MovementHelper.java"
EXPECT_LINES=863          # 判据（与 ref-integrity 的修复同一把尺子，2026-09-24 实测）
LEGACY_ROOT="/home/fb486/projects"
PINNED_REL="reference/baritone-1.20.1"   #: `AGENTS.md:62` 现在的相对钉法

CHECK_ONLY=0
ALSO_LEGACY=0
for arg in "$@"; do
  case "$arg" in
    --check)       CHECK_ONLY=1 ;;
    --also-legacy) ALSO_LEGACY=1 ;;
  esac
done

say() { printf '%s\n' "$*"; }
ok()   { printf '  ✅ %s\n' "$*"; }
skip() { printf '  ⛔ SKIP %s\n' "$*"; }
bad()  { printf '  ❌ %s\n' "$*"; }

say "=== 云端环境件复原 $( ((CHECK_ONLY)) && echo '(只复算)' || echo '(复原+复算)' )$( ((ALSO_LEGACY)) && echo ' +旧机绝对路径' ) ==="
say "仓库   : $REPO"
say "参照树 : $REF_DIR"
say ""

say "[①] Baritone 参照树（**必须真目录** —— 软链指向家目录 ⇒ 家目录没了就断）"
if [[ -L "$REF_DIR" ]]; then
  bad "$REF_DIR 是**符号链接** -> $(readlink "$REF_DIR")"
  say "     ⇒ ⛔ 家目录不持久 ⇒ rebuild 后这条链必断 ⇒ 相对钉法失效（**门禁会红，不是静默**）"
  ((CHECK_ONLY)) || say "     ⇒ 修法：\`rm '$REF_DIR'\` 后重跑本脚本（会真 clone，⛔ 不要 ln -s）"
fi
if [[ ! -d "$REF_DIR/.git" && ! -d "$REF_DIR/src" ]]; then
  if ((CHECK_ONLY)); then
    bad "不存在 ⇒ 引用解析会退化成「只索引本仓」⇒ \`ref-integrity\` 会把 Baritone 行号误判超界"
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

say "[②] 相对钉法（\`AGENTS.md:62\` = \`$PINNED_REL/\`，由 \`redline-gates.py:186\` 按 \`ROOT.parent\` 解析）"
RESOLVED="$(cd "$REPO/.." && pwd)/$PINNED_REL"
say "    解析为: $RESOLVED"
if [[ -f "$RESOLVED/gradle.properties" ]]; then
  rv=$(grep -m1 '^minecraft_version' "$RESOLVED/gradle.properties" | cut -d= -f2)
  [[ "$rv" == "1.20.1" ]] && ok "无参数 \`redline-gates.py\` 检查②可复算（minecraft_version=$rv）" \
                          || bad "解析到的树 minecraft_version=${rv:-读不到}（应 1.20.1）"
else
  bad "$RESOLVED/gradle.properties 不存在 ⇒ \`check-redline-gates\` 检查②必红"
fi
say ""

if ((ALSO_LEGACY)); then
say "[③] 旧机绝对路径 $LEGACY_ROOT（⛔ 2026-10-02 起 \`AGENTS.md\` **不再钉它** ⇒ 默认不做）"
if [[ ! -d /home/fb486 ]]; then
  skip "/home/fb486 不存在 —— **必须 root 才能建**（实测：\`touch /home/.probe\` → Permission denied）"
  say "     ⇒ 后果：**仅当**回退到绝对钉法时，\`check-redline-gates\` 检查②才会读不到 ⇒ 红"
  say "     ⇒ 当前相对钉法**不依赖它** ⇒ ⛔ 这条不再是『恒红』的原因"
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
fi

say "[复算] 两道门禁的真实读数"
printf '  ref-integrity : '; (cd "$REPO" && python3 tools/ref-integrity.py 2>&1 | tail -1)
printf '  redline-gates : '; (cd "$REPO" && python3 tools/redline-gates.py 2>&1 | tail -1)
say ""
say "（\`redline-gates\` 红 = 参照树缺失/版本不符，见上 [①②]；\`ref-integrity\` 才是参照树是否可用的判据。）"
