#!/usr/bin/env bash
# 云端 → 本机的**增量回迁**（⭐ 只针对**当前这台设备**：WSL 里的 /home/fb486/projects/alice）。
#
# 为什么只针对本设备：回迁的目标只有一处 —— 本机的 `~/.dsh` + 本机的仓库工作树。
# 换设备时"回迁"没有意义（云端那份本来就是被搬上去的副本），所以不写通用逻辑，只写本机路径。
#
# ⚠️ 2026-09-24 重写（旧版已不可用）：旧版把**整个** `~/.dsh/sessions` 打包回来 ——
#    当时云端只有 2 个会话（21 KB，能用）；迁移 177 个会话后那一份会变成 **292 MB**，
#    在"额度快耗尽 + 链路不稳"的场景下等于不可用。现在只搬**本机没有的那一段字节**。
#
# 核心事实（成立前提，✅ 2026-09-24 实测）：会话日志**只追加**，且云端那份是从本机复制出去的
#   ⇒ 本机文件是云端文件的前缀（分叉点 F 之前逐字节相同）⇒ 只需传 `云端[F:]`，
#     本机拼回 `本机[0,F) + 云端[F:]` = **云端文件的逐字节副本**，用 sha256 两端对账。
#   F 由**哈希阶梯**反查（云端给出前 1/2/3…MiB 的 sha256，本机找最大的共同那一级）。
#
# 步骤（都不删远端任何东西）：
#   ① 云端仓库**自检**：未提交 / 未推送（回迁最怕丢的就是这个）
#   ② 本机同步：`git pull` + 刷新 Windows 镜像
#   ③ 云端清单（会话 + 哈希阶梯 + 附件 + 小文件）→ 取回本机
#   ④ 本机算**增量计划**（哪些要搬、从第几字节起搬）
#   ⑤ 云端按计划切字节 → 打包
#   ⑥ 取回 + **重建 + sha256 对账** → 落 Windows 可见归档
#   ⑦ **文本出口**（层次 b）：把搬回来的增量解码成可读文本 + JSONL
#   ⑧ 打印"能不能被本机 DSH 采纳"的诚实结论 + 收尾清单
#
# 用法：
#   tools/cloud-rollback.sh                 # 用默认 codespace 名
#   tools/cloud-rollback.sh <codespace 名>
set -uo pipefail

CS="${1:-humble-tribble-97pv59gw5rg62prg5}"
REPO_DIR="${ALICE_REPO:-/home/fb486/projects/alice}"
BACKUP_DIR="${ALICE_CLOUD_BACKUP_DIR:-/mnt/d/JAVA_projects/alice-backups}"   # 放 Windows 可见处（本机重装也不丢）
REMOTE_REPO=/workspaces/Alice-mcbot
TS="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="$BACKUP_DIR/cloud-rollback-$TS"
WORK="$(mktemp -d)"
TOOL="$REPO_DIR/tools/dsh-session-rollback.mjs"

export PATH="$HOME/.local/bin:$PATH"
[ -f "$HOME/.gh-token" ] && export GH_TOKEN="$(tr -d '\r\n' < "$HOME/.gh-token")"

# ⭐ 2026-09-24 实测坑：WSL **不继承 Windows 代理**，`gh`（Go）**也不认 Windows 系统代理**
#    ⇒ 不设 HTTPS_PROXY 会 i/o 超时（表现成"连不上云端"）。用默认网关地址，不要用 127.0.0.1。
ensure_proxy() {
    [ -n "${HTTPS_PROXY:-}" ] && return 0
    local hostip port
    hostip="$(ip route show default 2>/dev/null | awk '{print $3; exit}')"
    [ -n "$hostip" ] || return 0
    for port in 7897 7890; do
        if curl -s -o /dev/null --max-time 2 -x "http://${hostip}:${port}" https://api.github.com/ 2>/dev/null; then
            export HTTPS_PROXY="http://${hostip}:${port}" HTTP_PROXY="http://${hostip}:${port}"
            info "已启用宿主代理 http://${hostip}:${port}"
            return 0
        fi
    done
    return 0
}

die() { printf '✗ %s\n' "$*" >&2; exit 1; }
info() { printf '→ %s\n' "$*"; }
need_gh() { command -v gh >/dev/null || die "找不到 gh"; }

rsh() {   # rsh <脚本>（脚本里不要出现单引号）
    local script="$1" b64
    b64="$(printf '%s' "$script" | base64 -w0)"
    gh codespace ssh -c "$CS" -- "bash -lc 'echo $b64 | base64 -d | bash -l'" 2>&1 | grep -v 'setlocale\|LC_ALL'
}

rput() {  # rput <本地文件> <远端路径>
    local src="$1" dst="$2" b64
    b64="$(base64 -w0 "$src")"
    rsh "mkdir -p \$(dirname $dst); printf '%s' $b64 | base64 -d > $dst; echo \"上传 \$(stat -c %s $dst) 字节 → $dst\""
}

need_gh
ensure_proxy
mkdir -p "$ARCHIVE"
[ -f "$TOOL" ] || die "找不到 $TOOL"
info "归档目录 = $ARCHIVE"

printf '\n===== ① 云端仓库自检（回迁最怕丢的就是这个）=====\n'
rsh "
    cd $REMOTE_REPO || exit 1
    echo \"当前 commit = \$(git rev-parse --short HEAD)\"
    echo \"分支 = \$(git rev-parse --abbrev-ref HEAD)\"
    if [ -n \"\$(git status --porcelain)\" ]; then
        echo '⚠️ 有未提交改动：'
        git status --porcelain | head -20
        git diff --stat | tail -5
    else
        echo '未提交改动 = 无'
    fi
    echo \"相对**本地记录的** upstream 未推送数 = \$(git log --oneline @{u}..HEAD 2>/dev/null | wc -l)（⚠️ 云端可能没 fetch，见 §13 结论）\"
"

printf '\n===== ② 本机同步（仓库 + Windows 镜像）=====\n'
if [ -d "$REPO_DIR/.git" ]; then
    git -C "$REPO_DIR" pull --ff-only github master 2>&1 | tail -2 || info "⚠️ 本地 pull 没成功（有本地改动？先处理再重跑）"
    git -C "$REPO_DIR" log --oneline -1
    [ -x "$REPO_DIR/tools/mirror-windows-workspace.sh" ] && "$REPO_DIR/tools/mirror-windows-workspace.sh" >/dev/null 2>&1 && info "Windows 镜像已刷新"
else
    info "⚠️ 本机没有 $REPO_DIR"
fi

printf '\n===== ③ 云端清单（会话 + 哈希阶梯 + 附件 + 小文件）=====\n'
rput "$TOOL" /tmp/dsh-session-rollback.mjs
rsh "cd /tmp && node /tmp/dsh-session-rollback.mjs inventory --out /tmp/cloud-inventory.json" | sed 's/^/    /'
gh codespace cp -e -c "$CS" remote:/tmp/cloud-inventory.json "$ARCHIVE/cloud-inventory.json" >/dev/null 2>&1 \
    || die "取回 cloud-inventory.json 失败"
[ -s "$ARCHIVE/cloud-inventory.json" ] || die "cloud-inventory.json 是空的"
info "清单已取回：$ARCHIVE/cloud-inventory.json（$(stat -c%s "$ARCHIVE/cloud-inventory.json") 字节）"

printf '\n===== ④ 本机算增量计划（哪些要搬、从第几字节起搬）=====\n'
node "$TOOL" plan --cloud "$ARCHIVE/cloud-inventory.json" --out "$WORK/plan.json" | sed 's/^/    /' \
    || die "plan 失败"
cp -f "$WORK/plan.json" "$ARCHIVE/plan.json"

printf '\n===== ⑤ 云端按计划切字节 + 打包 =====\n'
# ⚠️ 计划文件别走 rput（base64 套 base64 会把单个参数顶过 Linux 的 128KB 上限：
#    实测 84KB 的计划 ⇒ "Argument list too long"）⇒ 用 gh codespace cp 传二进制。
gh codespace cp -e -c "$CS" "$WORK/plan.json" remote:/tmp/rollback-plan.json >/dev/null 2>&1 \
    || die "上传计划到云端失败"
rsh "
    set -e
    cd /tmp
    node /tmp/dsh-session-rollback.mjs pack --plan /tmp/rollback-plan.json --staging /tmp/rollback-staging
    tar czf /tmp/rollback-bundle.tgz -C /tmp/rollback-staging .
    echo \"BUNDLE=\$(stat -c %s /tmp/rollback-bundle.tgz) SHA=\$(sha256sum /tmp/rollback-bundle.tgz | cut -c1-16)\"
" | sed 's/^/    /'

printf '\n===== ⑥ 取回 + 重建 + sha256 对账 =====\n'
gh codespace cp -e -c "$CS" remote:/tmp/rollback-bundle.tgz "$ARCHIVE/rollback-bundle.tgz" >/dev/null 2>&1 \
    || die "取回 rollback-bundle.tgz 失败"
info "bundle = $(stat -c%s "$ARCHIVE/rollback-bundle.tgz") 字节 / 本机 sha256 $(sha256sum "$ARCHIVE/rollback-bundle.tgz" | cut -c1-16)"
mkdir -p "$WORK/unpacked" && tar xzf "$ARCHIVE/rollback-bundle.tgz" -C "$WORK/unpacked"
node "$TOOL" rebuild --parts "$WORK/unpacked" --dest "$ARCHIVE" | sed 's/^/    /' \
    || die "重建 sha256 对账**失败** ⇒ 归档里有不一致项，别当成功（上面列了是哪一项）"

printf '\n===== ⑦ 文本出口（层次 b：给下一个主工作流读的原文）=====\n'
shopt -s nullglob
for p in "$WORK/unpacked/parts/"*__session-*.part; do
    base="$(basename "$p" .part)"
    node "$REPO_DIR/tools/dsh-session-log.mjs" --file "$p" --out "$ARCHIVE/$base.jsonl" 2>&1 | sed 's/^/    /'
    node "$TOOL" transcript --in "$ARCHIVE/$base.jsonl" --out "$ARCHIVE/$base.md" 2>&1 | sed 's/^/    /'
done
[ -n "$(ls -1 "$ARCHIVE"/*.md 2>/dev/null)" ] || info "（本次没有会话增量 ⇒ 没有文本出口产物）"

printf '\n===== ⑧ 能不能被本机 DSH 采纳（诚实结论）=====\n'
cat <<'EOF'
    · 代码：**已经在 git 里** ⇒ 回迁只需 ②（本地已有 = 云端已推 = GitHub 上）。
    · settings.yaml：本机那份是**权威**（云端那份本来就是从本机搬上去的副本，且被挪走过 ⇒ 归档只为留证，不要覆盖本机）。
    · sessions（会话历史）：**归档可读，但不要把归档会话塞回本机 ~/.dsh/sessions/**，两个理由：
        ⚠️ ① **同一个会话 id 在两端各自长过**（分叉点之后内容不同）⇒ 同 id 会与活着的本机分支互踩；
        ⚠️ ② 云端 rc.3 / 本机 rc.1 **不同代**（云端那份用本机的 rc.1 工具能解出来，但别指望 DSH 本体静默兼容）。
        ⇒ 想读云端那段对话：`node tools/dsh-session-log.mjs --file <归档里的 .zstd>`，或直接看本次产出的 `.md` 文本出口。
    · 本机拼出来的"云端会话副本"是**逐字节**的（sha256 已对账）⇒ 将来若要采纳，先升级本机 DSH 再决定，且**改 id**。
    · 插件：云端装的是 npm 上的 dsh-ears / dsh-whale-widget（本机本来就有，且本机 dsh-whale-widget 是 link: 开发副本）⇒ 无需回迁。
    · 云端那台机器：回迁完成后 **stop**（省额度）；确认不需要了再 delete（`gh codespace delete -c <名> --force`，不可逆）。
EOF

printf '\n===== 收尾清单 =====\n'
cat <<EOF
    [ ] ① 云端有未提交/未推送 ⇒ 先在云端处理掉（本脚本只报告，不替你提交）
    [ ] ② 本机 pull 成功、Windows 镜像已刷新
    [ ] ③ 归档目录（Windows 可见）：D:\\JAVA_projects\\alice-backups\\$(basename "$ARCHIVE")
    [ ] ④ 看一遍 ⑦ 产出的 *.md（那是云端这段时间干了什么的原文）
    [ ] ⑤ 停掉本机隧道：bash tools/codespace-zero.sh down $CS
    [ ] ⑥ 决定 codespace 去留：gh codespace stop -c $CS（保守）/ gh codespace delete -c $CS --force（彻底）
EOF
rm -rf "$WORK"
printf '\n✓ 回迁完成（归档 = %s）\n' "$ARCHIVE"
