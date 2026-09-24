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
#   tools/codespace-zero.sh state  <name>     # ⭐ 送 .credentials.yaml（600）；加 --with-settings 才连 settings.yaml 一起送
#   tools/codespace-zero.sh verify <name>     # 零期判据 1–4、6、7（node/java/dsh/配置/编译/门禁）
#   tools/codespace-zero.sh start  <name>     # 后台起 dsh web + 打印外部 URL + 端口设 private
#   tools/codespace-zero.sh tunnel <name>     # ⭐ 推荐入口：SSH 隧道到本地回环 + 打印带令牌的回环 URL
#   tools/codespace-zero.sh tunnel-bg <name>  # ⭐⭐ 常驻恢复：确保云端服务在跑 + 挂自动重连的隧道（重启电脑后用这条）
#   tools/codespace-zero.sh url    <name>     # 只打印转发 URL（⚠️ 走它打开时设置页不可用，见 tunnel 的说明）
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

# ⭐ 实跑抓到的坑：`gh codespace ssh -- bash -lc '脚本'` **不可用** —— gh 把 `--` 之后的参数
#    用空格拼成一个字符串，**本地引号在那一刻已经没了** ⇒ 远端只收到 `bash -lc mkdir`
#    （症状：`mkdir: missing operand`；多行脚本更隐蔽：login shell 会把脚本**逐行**当命令跑）。
#    ⇒ 统一走 base64：本地把脚本编码，远端 `bash -lc '<base64>' | base64 -d | bash -l`。
#    （`bash -l` 是必要的：ssh 的非登录 shell 不读 profile ⇒ `node`/`dsh` 会不在 PATH 里。）
rsh() {   # rsh <codespace> <脚本>（脚本里**不要**出现单引号）
    local name="$1" script="$2" b64
    b64="$(printf '%s' "$script" | base64 -w0)"
    ghc ssh -c "$name" -- "bash -lc 'echo $b64 | base64 -d | bash -l'"
}

# ⭐ 另一个实跑抓到的坑：`gh codespace cp`（本机 gh 2.45.0）把远端路径**连引号一起**交给远端 scp
#    （症状：`dest open "'/home/vscode/.dsh/x'": No such file or directory`）⇒ 不用它。
#    改成**内容经 base64 走 ssh**：不依赖任何路径引号规则，并且**两端 sha256 可对账**。
rput() {   # rput <codespace> <本地文件> <远端绝对路径>
    local name="$1" src="$2" dst="$3" b64 local_sha
    [ -f "$src" ] || die "本地文件不存在：$src"
    b64="$(base64 -w0 "$src")"
    local_sha="$(sha256sum "$src" | cut -c1-16)"
    rsh "$name" "mkdir -p \$(dirname $dst) && printf %s $b64 | base64 -d > $dst && chmod 600 $dst && printf '远端 ' && sha256sum $dst | cut -c1-16 && printf '本地 $local_sha  $dst\n'"
}

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
    local name="${1:?用法: state [--with-settings] <codespace 名>}"
    local with_settings=no
    if [ "$name" = "--with-settings" ]; then with_settings=yes; name="${2:?用法: state [--with-settings] <codespace 名>}"; fi
    [ -f "$HOME/.dsh/.credentials.yaml" ] || die "本机没有 ~/.dsh/.credentials.yaml"
    # ⭐ 默认**只送凭据**：本机 settings.yaml 是 rc.1 时代的（含本机插件段），跨版本 schema 差异是否会打坏设置页**未验证**
    #    ⇒ 云端让 DSH 自建（实测它会重建一个最小的）；要连设置一起送再加 --with-settings。
    [ "$with_settings" = no ] || [ -f "$HOME/.dsh/settings.yaml" ] || die "本机没有 ~/.dsh/settings.yaml"
    # ⚠️ 两个实跑坑：① `remote:` 的相对路径会被 gh 的 scp **加引号当字面名**（实测失败）；
    #    ② 远端家目录随镜像而变（Codespaces 默认镜像 = /home/codespace，自建 devcontainer = /home/vscode）
    #    ⇒ 先**问**远端 $HOME，再用绝对路径拷。
    local rh
    rh="$(rsh "$name" 'mkdir -p ~/.dsh && chmod 700 ~/.dsh && echo "$HOME"' 2>/dev/null | tr -d '\r' | tail -1)"
    [ -n "$rh" ] || die "拿不到远端 HOME（远端 shell 正常吗？）"
    info "远端 HOME = $rh（sha256 前缀两端对账）"
    if [ "$with_settings" = yes ]; then
        info "送 settings.yaml（--with-settings；含 contextWindow / 插件配置）…"
        rput "$name" "$HOME/.dsh/settings.yaml" "$rh/.dsh/settings.yaml"
        printf '本地 %s  %s\n' "$(sha256sum "$HOME/.dsh/settings.yaml" | cut -c1-16)" "settings.yaml"
    else
        info "跳过 settings.yaml（默认只送凭据 ⇒ 云端自建设置；要一起送加 --with-settings）"
    fi
    info "送 .credentials.yaml（⭐ 含密钥：内容不打印、不落 git）…"
    rput "$name" "$HOME/.dsh/.credentials.yaml" "$rh/.dsh/.credentials.yaml"
    printf '本地 %s  %s\n' "$(sha256sum "$HOME/.dsh/.credentials.yaml" | cut -c1-16)" ".credentials.yaml"
}

cmd_verify() {
    need_gh
    local name="${1:?用法: verify <codespace 名>}"
    info "零期判据（1–4、6、7；5、8 需要你在浏览器里点验）…"
    rsh "$name" '
        echo "--- 1) node（硬要求 ≥22.15：tools/dsh-session-log.mjs 用 zlib.zstdDecompressSync）"
        node -v
        echo "--- 2) java 17"
        java -version 2>&1 | head -1
        echo "--- 3) dsh"
        DSH_BIN="$(command -v dsh || echo "$(npm prefix -g 2>/dev/null)/bin/dsh")"
        [ -x "$DSH_BIN" ] && "$DSH_BIN" --version || echo "✗ 没有 dsh（跑：npm i -g @deepseek-ai/dsh@0.1.5-rc.3 —— 发布的 rc.1/rc.2 残缺）"
        echo "--- 4) 配置与凭据（只看在不在与权限）"
        ls -l ~/.dsh/settings.yaml ~/.dsh/.credentials.yaml 2>&1
        echo "--- 6) 编译（首次会下 Forge/MC 依赖，数 GB）"
        R=/workspaces/Alice-mcbot; [ -d "$R" ] || R=~/projects/alice
        cd "$R" && pwd && ./gradlew compileJava --no-daemon -q && echo "compileJava OK"
        echo "--- 7) 离线门禁（无上游 jar ⇒ check-machine-map 必然 WARN，属预期）"
        bash tools/check-all.sh 2>&1 | tail -3
    '
}

cmd_start() {
    need_gh
    local name="${1:?用法: start <codespace 名>}"
    info "后台启动 dsh web（回环 + --trusted-host；见 tools/codespace-start-dsh.sh）…"
    rsh "$name" '
        R=/workspaces/Alice-mcbot; [ -d "$R" ] || R=~/projects/alice; cd "$R"
        chmod +x tools/codespace-start-dsh.sh
        pkill -f "dsh web" 2>/dev/null || true
        nohup env DSH_WORKDIR=/workspaces tools/codespace-start-dsh.sh > ~/dsh-web.log 2>&1 &
        sleep 8; echo "--- ~/dsh-web.log ---"; tail -8 ~/dsh-web.log
        echo "--- 监听 ---"; (ss -ltn 2>/dev/null | grep -E ":3081" || echo "(还没监听)")
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

# ⭐⭐ 这一条是**源码级**结论（2026-09-23 实跑 + 读源码），决定「从哪打开」：
#   ① `dsh-client-ui-settings/lib/client.js:1345`：
#        const persistence = ctx.remote.$host.isLoopback ? "host" : "memory";
#   ② `dsh-client-connection/lib/client.js:6344`：
#        isLoopback: transport?.ownsHost === true || pageLocation === void 0
#                    || isLoopbackHostname(pageLocation.hostname)   # 只认 localhost / [::1] / 127.x.x.x
#   ③ persistence 为 memory 时，设置镜像的 `ensure()` **立刻返回**（settings client:1252）
#      ⇒ `view` 恒为 undefined ⇒ 设置页报 settings are unavailable in this browser
#   实测：走 https://<域名>-3081.app.github.dev 时**对话能用，但模型/插件配置打不开**；
#         走 SSH 隧道（页面 hostname = 127.0.0.1）时 isLoopback=true ⇒ 设置可读可写。
#   ⇒ **推荐入口 = tunnel**；转发 URL 只当「能对话」的备用入口。
cmd_tunnel() {
    need_gh
    local name="${1:?用法: tunnel <codespace 名> [本地端口，默认 3181]}"
    local lp="${2:-3181}" token
    token="$(rsh "$name" 'grep -o "token=[A-Za-z0-9_-]*" ~/dsh-web.log | tail -1' 2>/dev/null | tr -d '\r' | tail -1 | cut -d= -f2)"
    info "为什么要隧道（源码级事实，不是偏好）："
    info '  dsh-client-ui-settings/lib/client.js:1345  persistence = ctx.remote.$host.isLoopback ? "host" : "memory"'
    info '  ⇒ 非回环页面（HTTPS 转发域名）= memory ⇒ 设置镜像 ensure() 直接返回 ⇒ 模型/插件配置永远打不开'
    info '  ⇒ 回环页面（127.0.0.1）= host ⇒ 设置可读可写'
    printf '\n  ⭐ 浏览器打开： http://127.0.0.1:%s/?token=%s\n\n' "$lp" "${token:-(读不到：远端 ~/dsh-web.log)}"
    info "下面这条会占住当前终端（保持开着 = 隧道；Ctrl-C 断开）："
    printf '    gh codespace ssh -c %s -- -L %s:127.0.0.1:%s\n\n' "$name" "$lp" "$PORT"
    exec ghc ssh -c "$name" -- -L "$lp:127.0.0.1:$PORT"
}

remote_token() {   # 读远端 dsh web 日志里的令牌（每次重启服务都会变）
    local name="$1"
    rsh "$name" 'grep -o "token=[A-Za-z0-9_-]*" ~/dsh-web.log | tail -1' 2>/dev/null | tr -d '\r' | tail -1 | cut -d= -f2
}

# ⭐⭐ 常驻版：**本机重启 / codespace 被空闲停掉**之后，一条命令恢复「云端服务 + 隧道」。
#     与 tunnel 的区别：tunnel 是前台（占终端，Ctrl-C 断）；tunnel-bg 是 setsid 脱离会话 +
#     断线自动重连的循环，日志在 ~/.dsh-cloud-tunnel.log。
cmd_tunnel_bg() {
    need_gh
    local name="${1:?用法: tunnel-bg <codespace 名> [本地端口，默认 3181]}"
    local lp="${2:-3181}" token
    info "① 确保远端 dsh web 在跑（不在就起；唤醒 codespace 由 gh 自动做）…"
    rsh "$name" '
        R=/workspaces/Alice-mcbot; [ -d "$R" ] || R=~/projects/alice
        if pgrep -f "bin/dsh web" >/dev/null; then echo "远端服务已经在跑"; else
            cd "$R" && chmod +x tools/codespace-start-dsh.sh
            nohup env DSH_WORKDIR=/workspaces tools/codespace-start-dsh.sh >> ~/dsh-web.log 2>&1 &
            sleep 10; tail -3 ~/dsh-web.log
        fi'
    info "② 挂常驻隧道（断线自动重连）…"
    pkill -f "L ${lp}:127.0.0.1:${PORT}" 2>/dev/null || true
    setsid nohup bash -c "while true; do gh codespace ssh -c $name -- -N -o ServerAliveInterval=30 -o ServerAliveCountMax=6 -o ExitOnForwardFailure=yes -L ${lp}:127.0.0.1:${PORT}; sleep 5; done" >> "$HOME/.dsh-cloud-tunnel.log" 2>&1 &
    sleep 10
    info "③ 本地监听 127.0.0.1:${lp} = $(ss -ltn 2>/dev/null | grep -c ":${lp}") 条；自检 HTTP = $(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "http://127.0.0.1:${lp}/")（401 = 隧道通、只是没带令牌）"
    token="$(remote_token "$name")"
    printf '\n  ⭐ 浏览器打开（回环入口 ⇒ 设置页才可用）：\n\n      http://127.0.0.1:%s/?token=%s\n\n' "$lp" "${token:-(读不到：远端 ~/dsh-web.log)}"
}

cmd_down() { need_gh; local name="${1:?用法: down <codespace 名>}"; ghc stop -c "$name"; info "已停（计费停止，存储仍计）。删除用：gh codespace delete -c $name"; }

case "${1:-}" in
    doctor) cmd_doctor ;;
    create) shift; cmd_create "$@" ;;
    state)  shift; cmd_state  "$@" ;;
    verify) shift; cmd_verify "$@" ;;
    start)  shift; cmd_start  "$@" ;;
    tunnel) shift; cmd_tunnel "$@" ;;
    tunnel-bg) shift; cmd_tunnel_bg "$@" ;;
    url)    shift; cmd_url    "$@" ;;
    list)   cmd_list ;;
    down)   shift; cmd_down   "$@" ;;
    destroy) shift; need_gh; ghc delete -c "${1:?用法: destroy <codespace 名>}" ;;
    *) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
