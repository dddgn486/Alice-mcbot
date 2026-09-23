#!/usr/bin/env bash
# 零期（Codespaces）驱动脚本 —— **在本机 WSL 里跑**，用 `gh` 遥控云端 Codespace。
#
# 出处：`docs/CLOUD_MIGRATION.md`（修补版 v2）§4.1；用户 2026-09-23 选定的 **A 路**。
#
# ⚠️ 本机网络的两个实测事实（决定本脚本的认证方式）：
#   ① **`github.com` 的 HTTPS 不通**（所以 `~/.ssh/config` 把 github 指向 `ssh.github.com:443`）
#      ⇒ `gh auth login --web`（设备码流程要访问 github.com）**在本机走不通**；
#   ② `api.github.com` **通**（实测 200）⇒ 用 **PAT** 认证（`GH_TOKEN`），全程只走 api。
#
# 认证（二选一，见 `doctor`）：
#   A. 把带 `codespace` scope 的 PAT 写进 `~/.gh-token`（chmod 600）—— 本脚本自动读取，**从不打印**
#   B. 已经 `gh auth login` 过（在能访问 github.com 的网络里做过）
#
# 用法：
#   tools/codespace-zero.sh doctor            # 先跑这个：查 gh / 认证 / 仓库
#   tools/codespace-zero.sh create [machine]  # 默认机器用 API 定（免费档 = 2 核/8 G/32 G）
#   tools/codespace-zero.sh state  <name>     # ⭐ 把 settings.yaml + .credentials.yaml 送进去（600）
#   tools/codespace-zero.sh verify <name>     # 零期判据 1–4、6、7（node/java/dsh/配置/编译/门禁）
#   tools/codespace-zero.sh start  <name>     # 后台起 dsh web + 打印外部 URL + 端口设 private
#   tools/codespace-zero.sh url    <name>     # 只打印转发 URL
#   tools/codespace-zero.sh list              # 列 codespaces
#   tools/codespace-zero.sh down   <name>     # 停（stop）；`destroy` 才删
set -uo pipefail

REPO="${ALICE_CLOUD_REPO:-dddgn486/Alice-mcbot}"
BRANCH="${ALICE_CLOUD_BRANCH:-master}"
PORT="${DSH_PORT:-3081}"
TOKEN_FILE="${GH_TOKEN_FILE:-$HOME/.gh-token}"

export PATH="$HOME/.local/bin:$PATH"   # gh 装在这里（无 sudo 的本地前缀）
if [ -f "$TOKEN_FILE" ]; then
    GH_TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
    export GH_TOKEN
fi

die() { printf '✗ %s\n' "$*" >&2; exit 1; }
info() { printf '→ %s\n' "$*"; }

need_gh() {
    command -v gh >/dev/null || die "找不到 gh。装法（无需 sudo）：cd /tmp && apt-get download gh && dpkg-deb -x gh_*.deb ghx && mkdir -p ~/.local/opt && mv ghx/usr ~/.local/opt/gh-2.45.0 && ln -sf ~/.local/opt/gh-2.45.0/bin/gh ~/.local/bin/gh"
}

ghc() { gh codespace "$@"; }

cmd_doctor() {
    need_gh
    info "gh = $(gh --version | head -1)"
    info "repo = $REPO（分支 $BRANCH）"
    if [ -n "${GH_TOKEN:-}" ]; then
        info "认证 = PAT（来自 $TOKEN_FILE，未打印）"
    elif gh auth status >/dev/null 2>&1; then
        info "认证 = gh auth login（已有）"
    else
        cat >&2 <<EOF
✗ 没有认证。本机 github.com 的 HTTPS 不通 ⇒ **不能**用 \`gh auth login --web\`。请二选一：
  A) 在浏览器里建一个 **classic PAT**，勾上 \`repo\` + \`codespace\` 两个 scope：
       https://github.com/settings/tokens/new?scopes=repo,codespace&description=alice-codespace
     然后把令牌**原样**存成文件（别贴进聊天）：
       printf '%s' '<PAT>' > ~/.gh-token && chmod 600 ~/.gh-token
  B) 换到能访问 github.com 的网络做 \`gh auth login\`（git-protocol 选 ssh），再回来跑本脚本。
EOF
        return 1
    fi
    info "api.github.com 探测 …"
    gh api /user --jq '.login' >/dev/null 2>&1 && info "api OK（账号 $(gh api /user --jq .login 2>/dev/null)）" || die "api 不通（检查代理/网络）"
    info "已有 codespaces："
    ghc list 2>/dev/null | sed 's/^/    /' || true
}

cmd_create() {
    need_gh
    local machine="${1:-}"
    local args=(-R "$REPO" -b "$BRANCH" --idle-timeout 30m --retention-period 24h)
    [ -n "$machine" ] && args+=(-m "$machine")
    info "创建 codespace（免费档 = 2 核 / 8 G / 32 G；devcontainer 会装 JDK17+Node22+DSH）…"
    ghc create "${args[@]}"
    info "等 postCreateCommand（装 DSH）跑完：gh codespace create -s / gh codespace list"
}

cmd_list() { need_gh; ghc list --json name,state,machineName,repository,createdAt 2>/dev/null || ghc list; }

cmd_state() {
    need_gh
    local name="${1:?用法: state <codespace 名>}"
    [ -f "$HOME/.dsh/settings.yaml" ] || die "本机没有 ~/.dsh/settings.yaml"
    [ -f "$HOME/.dsh/.credentials.yaml" ] || die "本机没有 ~/.dsh/.credentials.yaml"
    info "建远端 ~/.dsh（并把权限收紧）…"
    ghc ssh -c "$name" -- bash -lc 'mkdir -p ~/.dsh && chmod 700 ~/.dsh'
    info "送 settings.yaml（含 contextWindow / 插件配置）…"
    ghc cp -e "$HOME/.dsh/settings.yaml" "remote:$HOME/.dsh/settings.yaml" -c "$name"
    info "送 .credentials.yaml（⭐ 含密钥：内容不打印、不落 git）…"
    ghc cp -e "$HOME/.dsh/.credentials.yaml" "remote:$HOME/.dsh/.credentials.yaml" -c "$name"
    ghc ssh -c "$name" -- bash -lc 'chmod 600 ~/.dsh/settings.yaml ~/.dsh/.credentials.yaml && ls -l ~/.dsh/'
}

cmd_verify() {
    need_gh
    local name="${1:?用法: verify <codespace 名>}"
    info "零期判据（1–4、6、7；5、8 需要你在浏览器里点验）…"
    ghc ssh -c "$name" -- bash -lc '
        set -u
        echo "--- 1) node（硬要求 ≥22.15：tools/dsh-session-log.mjs 用 zlib.zstdDecompressSync）"
        node -v
        echo "--- 2) java 17"
        java -version 2>&1 | head -1
        echo "--- 3) dsh"
        command -v dsh >/dev/null && dsh --version || echo "✗ 没有 dsh（postCreateCommand 是否跑完？）"
        echo "--- 4) 配置与凭据（只看在不在与权限）"
        ls -l ~/.dsh/settings.yaml ~/.dsh/.credentials.yaml 2>&1
        echo "--- 6) 编译（首次会下 Forge/MC 依赖，数 GB）"
        if [ -d ~/projects/alice ]; then cd ~/projects/alice; elif [ -d /workspaces/Alice-mcbot ]; then cd /workspaces/Alice-mcbot; fi
        pwd; ./gradlew compileJava --no-daemon -q && echo "compileJava OK"
        echo "--- 7) 离线门禁（无上游 jar ⇒ check-machine-map 必然 WARN，属预期）"
        bash tools/check-all.sh 2>&1 | tail -3
    '
}

cmd_start() {
    need_gh
    local name="${1:?用法: start <codespace 名>}"
    info "后台启动 dsh web（带 --host 0.0.0.0 与 --trusted-host；见 tools/codespace-start-dsh.sh）…"
    ghc ssh -c "$name" -- bash -lc '
        cd ~ && if [ -d ~/projects/alice ]; then cd ~/projects/alice; elif [ -d /workspaces/Alice-mcbot ]; then cd /workspaces/Alice-mcbot; fi
        chmod +x tools/codespace-start-dsh.sh
        pkill -f "dsh web" 2>/dev/null || true
        nohup env DSH_WORKDIR="${DSH_WORKDIR:-$HOME/projects}" tools/codespace-start-dsh.sh > ~/dsh-web.log 2>&1 &
        sleep 6; tail -5 ~/dsh-web.log
    '
    info "把 $PORT 设为 private（别设 public）…"
    ghc ports visibility "$PORT:private" -c "$name" 2>/dev/null || info "（该版本 gh 不支持命令行改可见性 ⇒ 在 PORTS 面板手动设为 Private）"
    cmd_url "$name"
}

cmd_url() {
    need_gh
    local name="${1:?用法: url <codespace 名>}"
    info "转发地址："
    ghc ports -c "$name" --json sourcePort,browseUrl,visibility 2>/dev/null || ghc ports -c "$name"
}

cmd_down() { need_gh; local name="${1:?用法: down <codespace 名>}"; ghc stop -c "$name"; info "已停（计费停止，存储仍计）。删除用：gh codespace delete -c $name"; }

case "${1:-}" in
    doctor) cmd_doctor ;;
    create) shift; cmd_create "$@" ;;
    state)  shift; cmd_state  "$@" ;;
    verify) shift; cmd_verify "$@" ;;
    start)  shift; cmd_start  "$@" ;;
    url)    shift; cmd_url    "$@" ;;
    list)   cmd_list ;;
    down)   shift; cmd_down   "$@" ;;
    destroy) shift; need_gh; ghc delete -c "${1:?用法: destroy <codespace 名>}" ;;
    *) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
