#!/usr/bin/env bash
# 云端 → 本机的**回迁准备**（⭐ 只针对**当前这台设备**：WSL 里的 /home/fb486/projects/alice）。
#
# 为什么只针对本设备：回迁的目标只有一处 —— 本机的 `~/.dsh` + 本机的仓库工作树。
# 换设备时"回迁"没有意义（云端那份本来就是被搬上去的副本），所以不写通用逻辑，只写本机路径。
#
# 它做四件事（都不删远端任何东西）：
#   ① 云端仓库**自检**：有没有未提交 / 未推送的东西（这是回迁最怕丢的部分）
#   ② 本地同步：`git pull` + 刷新 Windows 镜像
#   ③ 云端**非代码状态**（sessions / storages / settings.yaml* / profile 清单）打包回本机归档
#   ④ 打印"能不能被本机 DSH 采纳"的诚实结论 + 收尾清单
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
OUT="$BACKUP_DIR/cloud-dsh-$TS.tar.gz"

export PATH="$HOME/.local/bin:$PATH"
[ -f "$HOME/.gh-token" ] && export GH_TOKEN="$(tr -d '\r\n' < "$HOME/.gh-token")"

die() { printf '✗ %s\n' "$*" >&2; exit 1; }
info() { printf '→ %s\n' "$*"; }
need_gh() { command -v gh >/dev/null || die "找不到 gh"; }

rsh() {   # rsh <脚本>（脚本里不要出现单引号）
    local script="$1" b64
    b64="$(printf '%s' "$script" | base64 -w0)"
    gh codespace ssh -c "$CS" -- "bash -lc 'echo $b64 | base64 -d | bash -l'"
}

need_gh
mkdir -p "$BACKUP_DIR"

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
    echo \"未推送 commit 数 = \$(git log --oneline origin/master..HEAD 2>/dev/null | wc -l)\"
"

printf '\n===== ② 本地同步（仓库 + Windows 镜像）=====\n'
if [ -d "$REPO_DIR/.git" ]; then
    git -C "$REPO_DIR" pull --ff-only github master 2>&1 | tail -2 || info "⚠️ 本地 pull 没成功（有本地改动？先处理再重跑）"
    git -C "$REPO_DIR" log --oneline -1
    [ -x "$REPO_DIR/tools/mirror-windows-workspace.sh" ] && "$REPO_DIR/tools/mirror-windows-workspace.sh" >/dev/null 2>&1 && info "Windows 镜像已刷新"
else
    info "⚠️ 本机没有 $REPO_DIR"
fi

printf '\n===== ③ 云端非代码状态打包回本机 =====\n'
# 远端动态列出**存在**的路径再 tar（列不存在的路径 tar 会报错退出）；base64 中转保证二进制安全；两端 sha256 对账。
remote_out="$(rsh '
    cd ~ || exit 1
    items=""
    for p in .dsh/sessions .dsh/storages .dsh/settings.yaml .dsh/settings.yaml.foreign .dsh/settings.yaml.copied-from-local .dsh/profiles/web/package.json; do
        [ -e "$p" ] && items="$items $p"
    done
    [ -n "$items" ] || { echo "NO_FILES"; exit 0; }
    tar czf /tmp/cloud-dsh.tgz $items
    printf "SHA=%s\n" "$(sha256sum /tmp/cloud-dsh.tgz | cut -c1-16)"
    printf "SIZE=%s\n" "$(stat -c %s /tmp/cloud-dsh.tgz)"
    base64 -w0 /tmp/cloud-dsh.tgz
    rm -f /tmp/cloud-dsh.tgz
' 2>/dev/null)"
if printf '%s' "$remote_out" | head -1 | grep -q NO_FILES; then
    info "⚠️ 云端没有可打包的东西（路径都不存在？）"
else
    remote_sha="$(printf '%s\n' "$remote_out" | sed -n 's/^SHA=//p' | head -1)"
    remote_size="$(printf '%s\n' "$remote_out" | sed -n 's/^SIZE=//p' | head -1)"
    printf '%s\n' "$remote_out" | tail -1 | base64 -d > "$OUT" || die "base64 解码失败"
    local_sha="$(sha256sum "$OUT" | cut -c1-16)"
    printf '云端 %s (%s 字节) / 本机 %s ⇒ %s\n' "$remote_sha" "$remote_size" "$local_sha" \
        "$([ "$remote_sha" = "$local_sha" ] && echo 一致 || echo '✗ 不一致！')"
    info "归档 = $OUT（Windows 可见：D:\\JAVA_projects\\alice-backups\\$(basename "$OUT")）"
    info "内容："; tar tzf "$OUT" | head -12 | sed 's/^/    /'
fi

printf '\n===== ④ 能不能被本机 DSH 采纳（诚实结论）=====\n'
cat <<'EOF'
    · 代码：**已经在 git 里** ⇒ 回迁只需 ②（本地已有 = 云端已推 = GitHub 上）。
    · settings.yaml：本机那份是**权威**（云端那份本来就是从本机搬上去的副本，且被挪走过 ⇒ 归档只为留证，不要覆盖本机）。
    · sessions（会话历史）：**归档可以，直接采纳有风险** ——
        ⚠️ 云端的 slug 与本机不同（云端 --home-vscode-dsh-test-- / 本机 --home-fb486-projects--）⇒ 历史不会出现在同一个工作区下；
        ⚠️ 版本也不同代（云端 dsh 0.1.5-rc.3 / 本机 0.1.5-rc.1）⇒ 会话存储带世代迁移，**跨代读取应"响亮地失败"**（这是预期，不要静默兼容）。
        ⇒ 想读云端某次对话，用归档里的原始文件 + 对应版本的 dsh 工具，别直接扔进本机 ~/.dsh/sessions。
    · 插件：云端装的是 npm 上的 dsh-ears / dsh-whale-widget（本机本来就有，且本机 dsh-whale-widget 是 link: 开发副本）⇒ 无需回迁。
    · 云端那台机器：回迁完成后 **stop**（省额度）；确认不需要了再 delete（`gh codespace delete -c <名> --force`，不可逆）。
EOF

printf '\n===== 收尾清单 =====\n'
cat <<EOF
    [ ] ① 云端有未提交/未推送 ⇒ 先在云端处理掉（本脚本只报告，不替你提交）
    [ ] ② 本机 pull 成功、Windows 镜像已刷新
    [ ] ③ 归档文件已在 Windows 可见：D:\\JAVA_projects\\alice-backups\\$(basename "$OUT")
    [ ] ④ 停掉本机隧道：bash tools/codespace-zero.sh down $CS 之后隧道自然断
    [ ] ⑤ 决定 codespace 去留：gh codespace stop -c $CS（保守）/ gh codespace delete -c $CS --force（彻底）
EOF
