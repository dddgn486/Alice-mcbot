#!/usr/bin/env bash
# alice-cloudctl.sh —— **云端服务体检 / 修复入口**（跑在 codespace 内）
# ============================================================================
# 为什么有它：Windows 侧管家 agent 在"云端出事"时必须能**自己看到事实**（而不是猜）。
#   ⇒ 这是**确定性、幂等、零 LLM** 的一层；管家/主工作流调用它拿事实，再做判断。
#
# 调用方式（从外面，Windows 管家或 WSL 侧都同一条）：
#     gh codespace ssh -c <codespace> -- "bash -lc '<把本脚本内容 base64 后喂进去>'"
#   ⚠️ **别**直接把本脚本路径写进 ssh 命令 —— 新机器上还没有这个文件（鸡生蛋）。
#      `tools/codespace-zero.sh` 的 `rsh()` 与 client-agent 的 `Remote-Exec` 都用 base64 中转；
#      本脚本自己再提供一个 `install` 子命令，把**自己**装到云端 `~/bin/` 去（之后就能按路径调）。
#
# 子命令（全部**只读或幂等**；没有破坏性动作）：
#     status     只报事实：主机/工具链/仓库/信箱/dsh web 服务/隧道日志尾
#     fix        status + 幂等修复：建信箱目录 · 起 dsh web（已在跑就不动）· 打印带令牌 URL
#     url        只打印带令牌的回环转发 URL（给 Windows 侧挂隧道用）
#     ensure     只做"该在的东西在"：信箱四目录 + ~/bin + 本脚本自装
#     install    把本脚本装到云端 ~/bin/alice-cloudctl.sh（幂等）
#     version    打印版本
#
# 退出码：0 = 关键项全好（或已修好）· 1 = 有 FAIL 需要人看
#
# ⚠️ 设计纪律（都是踩过的坑）：
#   · **不要** `set -e`：逐项检查要能继续跑完（一行坏了不代表整机坏了）。
#   · **不要** `pkill -f "dsh web"`：会匹配到执行这条命令的 shell 自己（CLOUD_MIGRATION §9-37）。
#     本脚本一律用 `pgrep -f "[d]sh web"` 这种方括号写法。
#   · **不要**假设 `CODESPACE_NAME` 存在：ssh 会话里没有它（§9-32），转发域名要由调用方传或从
#     已有服务日志里读。
#   · 输出**一行一个事实**，`KEY=VALUE` 或 `[ OK|FIX|FAIL|SKIP ] 描述`：给人看也给 agent 读。
# ============================================================================
VERSION="2026-10-01.1"

PORT="${DSH_PORT:-3081}"
WORKDIR="${DSH_WORKDIR:-$HOME/projects}"
MAILBOXES="$HOME/bus/to-win $HOME/bus/to-cloud $HOME/client-info $HOME/outbox"
LOG="$HOME/dsh-web.log"
PIDFILE="$HOME/.dsh-web.pid"
URLFILE="$HOME/.dsh-web-url.txt"

OK=0; BAD=0
say()  { printf '%s\n' "$*"; }
ok()   { OK=$((OK+1));  printf '[ OK ] %s\n' "$*"; }
fix()  { OK=$((OK+1));  printf '[FIX ] %s\n' "$*"; }
bad()  { BAD=$((BAD+1)); printf '[FAIL] %s\n' "$*"; }
skip() { printf '[SKIP] %s\n' "$*"; }

# --- dsh 可执行文件定位（非交互 ssh 里 PATH 可能没有 nvm 的 bin —— §实跑）---
find_dsh() {
    local b
    b="$(command -v dsh 2>/dev/null || true)"
    [ -n "$b" ] && { printf '%s' "$b"; return 0; }
    b="$(npm prefix -g 2>/dev/null)/bin/dsh"
    [ -x "$b" ] && { printf '%s' "$b"; return 0; }
    return 1
}
web_pid() { pgrep -f "[d]sh web" 2>/dev/null | head -1; }

# --- 转发域名：ssh 里没有 CODESPACE_NAME ⇒ 从已有状态里找，找不到就诚实说不知道 ---
trusted_host() {
    if [ -n "${DSH_TRUSTED_HOST:-}" ]; then printf '%s' "$DSH_TRUSTED_HOST"; return 0; fi
    if [ -f "$URLFILE" ]; then
        sed -n 's#.*https://\([^/]*\).*#\1#p' "$URLFILE" | head -1
        return 0
    fi
    if [ -f "$LOG" ]; then
        sed -n 's#.*--trusted-host \([^ ]*\).*#\1#p' "$LOG" | tail -1
        return 0
    fi
    return 1
}

cmd_status() {
    say "=== alice-cloudctl $VERSION · status ==="
    # ① 主机
    say "HOST=$(hostname)"
    say "USER=$(id -un) HOME=$HOME"
    say "CPUS=$(nproc 2>/dev/null || echo '?') MEM=$(free -g 2>/dev/null | awk '/Mem:/{print $2"G"}' || echo '?')"
    say "UPTIME=$(uptime -p 2>/dev/null || uptime 2>/dev/null | sed 's/^ *//')"
    # ② 工具链（零期判据 1/2/3）
    if command -v node >/dev/null; then ok "node $(node -v)"; else bad "没有 node（判据 1）"; fi
    if command -v java >/dev/null; then ok "java $(java -version 2>&1 | head -1)"; else bad "没有 java 17（判据 2）"; fi
    local D; if D="$(find_dsh)"; then ok "dsh $("$D" --version 2>&1) @ $D"; else bad "没有 dsh（判据 3）⇒ npm i -g @deepseek-ai/dsh@0.1.5-rc.3"; fi
    # python stdlib（§9-37：基础镜像物理缺 json 标准库 ⇒ 4 项门禁假红）
    if python3 -c 'import json,html,shutil,zipfile' 2>/dev/null; then ok "python3 标准库完整"
    else bad "python3 缺标准库（json/html/shutil/zipfile）⇒ sudo apt-get install -y libpython3.12-stdlib"; fi
    # ③ 配置与凭据（判据 4）
    for f in "$HOME/.dsh/.credentials.yaml" "$HOME/.dsh/settings.yaml"; do
        if [ -f "$f" ]; then ok "$(basename "$f") 在（$(stat -c %s "$f") 字节）"; else bad "缺 $f"; fi
    done
    # ④ 仓库/工作区
    if [ -d "$WORKDIR" ]; then
        ok "workdir 在：$WORKDIR"
        if [ -d "$WORKDIR/alice/.git" ] || [ -d "$WORKDIR/.git" ]; then
            local R="$WORKDIR/alice"; [ -d "$WORKDIR/.git" ] && R="$WORKDIR"
            say "  git HEAD=$(git -C "$R" rev-parse --short HEAD 2>/dev/null) branch=$(git -C "$R" rev-parse --abbrev-ref HEAD 2>/dev/null) remote=$(git -C "$R" remote | tr '\n' ',' 2>/dev/null)"
        else skip "workdir 下没看到 git 仓库"; fi
    else bad "workdir 不存在：$WORKDIR"; fi
    # ⑤ 信箱四目录
    local miss=""
    for d in $MAILBOXES; do [ -d "$d" ] || miss="$miss $d"; done
    if [ -z "$miss" ]; then ok "信箱四目录齐（to-win/to-cloud/client-info/outbox）"; else bad "信箱缺目录：$miss"; fi
    if [ -d "$HOME/bus/to-win" ]; then
        say "  to-win 待处理=$(ls -1 "$HOME/bus/to-win" 2>/dev/null | grep -v '^\.done$' | grep -c . ) 条 · .done=$( [ -f "$HOME/bus/to-win/.done" ] && wc -l < "$HOME/bus/to-win/.done" || echo 0 ) 行"
    fi
    # ⑥ dsh web 服务
    local P; P="$(web_pid)"
    if [ -n "$P" ]; then
        ok "dsh web 在跑（pid=$P）"
        say "  listen=$( (ss -ltnp 2>/dev/null || true) | grep ":$PORT " | head -1 | sed 's/^ *//' )"
        say "  日志尾："; tail -3 "$LOG" 2>/dev/null | sed 's/^/    /'
    else
        bad "dsh web **没在跑**（pid 空；pgrep -f '[d]sh web' 无输出）"
    fi
    # ⑦ 隧道/入口
    local TH; if TH="$(trusted_host)"; then say "TRUSTED_HOST=${TH}"; else skip "不知道转发域名（ssh 会话里没有 CODESPACE_NAME）"; fi
    [ -f "$URLFILE" ] && say "last_url=$(cat "$URLFILE" 2>/dev/null | head -1)"
    say "=== 汇总：$OK 好 / $BAD 坏 ==="
    [ "$BAD" -eq 0 ]
}

cmd_ensure() {
    local n=0
    for d in $MAILBOXES; do
        [ -d "$d" ] || { mkdir -p "$d" && n=$((n+1)); }
    done
    [ "$n" -eq 0 ] && ok "信箱四目录齐（无需动）" || fix "建了 $n 个信箱目录"
    mkdir -p "$HOME/bin"
    if [ -f "$HOME/bin/alice-cloudctl.sh" ]; then ok "~/bin/alice-cloudctl.sh 已在"
    else fix "~/bin 已建（本脚本可用 install 子命令自装）"; fi
    return 0
}

# 起 dsh web（幂等：已在跑就只报事实）
cmd_fix() {
    say "=== alice-cloudctl $VERSION · fix（幂等）==="
    cmd_ensure
    local P; P="$(web_pid)"
    if [ -n "$P" ]; then
        ok "dsh web 已在跑（pid=$P）⇒ 不动它"
    else
        local D; if ! D="$(find_dsh)"; then bad "没有 dsh ⇒ 装不了服务（npm i -g @deepseek-ai/dsh@0.1.5-rc.3）"; return 1; fi
        local TH; TH="$(trusted_host || true)"
        if [ -z "$TH" ]; then
            bad "不知道转发域名 ⇒ 起服务会缺 --trusted-host（症状：页面能开、发消息就坏）"
            say "      修法之一：调用方显式给 DSH_TRUSTED_HOST=<名>-${PORT}.app.github.dev"
            return 1
        fi
        mkdir -p "$WORKDIR"
        say "  启动：$D web --port $PORT --no-open --trusted-host $TH（cwd=$WORKDIR）"
        ( cd "$WORKDIR" && setsid nohup "$D" web --port "$PORT" --no-open --trusted-host "$TH" >> "$LOG" 2>&1 & echo $! > "$PIDFILE" )
        local i
        for i in $(seq 1 20); do
            sleep 1
            P="$(web_pid)"; [ -n "$P" ] && break
        done
        if [ -n "$P" ]; then fix "dsh web 已起（pid=$P，日志 $LOG）"; else bad "起了但没看到进程 ⇒ 看 $LOG"; return 1; fi
    fi
    cmd_url
    cmd_status
}

# 打印带令牌的回环 URL（Windows 侧挂隧道后的入口）
cmd_url() {
    local base="http://127.0.0.1"
    local lp="${LOCAL_PORT:-3181}"
    local tok=""
    # 令牌在服务日志里（DSH 启动时打印一次性 URL）
    if [ -f "$LOG" ]; then
        tok="$(sed -n 's#.*[?&]token=\([A-Za-z0-9_-]\{8,\}\).*#\1#p' "$LOG" | tail -1)"
    fi
    if [ -z "$tok" ]; then
        bad "日志里找不到 token（服务没起过？或日志被清）"
        say "      修法：先跑 fix；或去 Codespaces 的 Ports 面板拿裸链接（会 401，需手动补 token）"
        return 1
    fi
    local u="${base}:${lp}/?token=${tok}"
    printf '%s' "$u" > "$URLFILE"
    ok "本地回环入口（Windows 侧挂隧道到 $PORT 后用）：$u"
    local TH; TH="$(trusted_host || true)"
    [ -n "$TH" ] && say "  转发入口（⚠️ 设置页不可用，只能对话）：https://${TH}/?token=${tok}"
    return 0
}

cmd_install() {
    local self="$HOME/bin/alice-cloudctl.sh"
    mkdir -p "$HOME/bin"
    if [ "${BASH_SOURCE[0]}" != "$self" ]; then
        cp -f "${BASH_SOURCE[0]}" "$self" 2>/dev/null && chmod +x "$self" && fix "已装到 $self"
    fi
    [ -f "$self" ] && ok "$self 在位（$(stat -c %s "$self") 字节）"
    return 0
}

cmd_version() { printf 'alice-cloudctl %s\n' "$VERSION"; }

case "${1:-status}" in
    status)  cmd_status ;;
    ensure)  cmd_ensure ;;
    fix)     cmd_fix ;;
    url)     cmd_url ;;
    install) cmd_install ;;
    version) cmd_version ;;
    *) say "用法：alice-cloudctl.sh {status|fix|ensure|url|install|version}"; exit 2 ;;
esac
