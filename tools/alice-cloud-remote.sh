#!/usr/bin/env bash
# ============================================================================
#  alice-cloud-remote.sh —— **本机（WSL）侧**的云端调用封装（带重试）
# ============================================================================
#
#  为什么有它（2026-10-01 实测）：`gh codespace ssh` 在弱网下**约 1/5 次**报
#      error getting ssh server details: rpc error: code = DeadlineExceeded
#      error connecting to internal server: context deadline exceeded
#  ⇒ **单次失败 ≠ 云端坏了**。人/agent 徒手重试会误判，所以把重试收成一层。
#
#  用法：
#     CODESPACE=<名> tools/alice-cloud-remote.sh status|fix|url|ensure|install|version
#         ⇒ 把本仓 tools/alice-cloudctl.sh 送上云端跑那个子命令（**不依赖云端已装它**）
#     CODESPACE=<名> tools/alice-cloud-remote.sh <本地脚本文件>
#         ⇒ 送任意脚本上去跑（bash -l）
#     tools/alice-cloud-remote.sh --stdin <codespace> <<'SH' … SH
#         ⇒ 从标准输入吃脚本
#
#  它做什么：
#     ① 代理自动探测（WSL 不继承 Windows 代理；gh 只认 HTTP(S)_PROXY，CLOUD_MIGRATION §9-42）
#     ② 脚本 base64 **单层**喂远端 `bash -l`（gh 把 `--` 之后的参数空格拼接 ⇒ 不能传裸引号）
#     ③ **只对传输层错误**重试（DeadlineExceeded / timeout / refused）；远端脚本自己的退出码
#        是**结论**不是故障 ⇒ 原样透出
#     ④ 返回远端退出码（调用方据此判红绿）
#
#  ⚠️ 不做什么：不改云端任何东西（改由被送上去的脚本决定）；不打印凭据。
# ============================================================================
set -uo pipefail

PROXY_PORTS=(7897 7890 10809)
RETRIES="${ALICE_SSH_RETRIES:-3}"

log() { printf '[remote] %s\n' "$*" >&2; }
die() { printf '[remote] ✗ %s\n' "$*" >&2; exit 2; }

export PATH="$HOME/.local/bin:$PATH"
GH="$(command -v gh || true)"
[ -n "$GH" ] || die "找不到 gh"

# ---- 代理（WSL 不继承 Windows 的；NAT 下要用**默认网关**，127.0.0.1 不指向 Windows）----
ensure_proxy() {
    [ -n "${HTTPS_PROXY:-}" ] && return 0
    local hostip port
    hostip="$(ip route show default 2>/dev/null | awk '{print $3; exit}')"
    [ -n "$hostip" ] || return 0
    for port in "${PROXY_PORTS[@]}"; do
        if curl -s -o /dev/null --max-time 2 -x "http://${hostip}:${port}" https://api.github.com/ 2>/dev/null; then
            export HTTPS_PROXY="http://${hostip}:${port}" HTTP_PROXY="http://${hostip}:${port}"
            log "已启用宿主代理 http://${hostip}:${port}"
            return 0
        fi
    done
    log "没探测到可用宿主代理（在用代理的话，检查网关 IP=$hostip）"
    return 0
}

[ -f "$HOME/.gh-token" ] && export GH_TOKEN="$(tr -d '\r\n' < "$HOME/.gh-token")"

# ---- 送脚本上去跑（只对传输层错误重试）----
remote_call() {   # remote_call <codespace> <脚本内容> ⇒ 打印远端输出，返回远端退出码
    local name="$1" script="$2" b64 out rc attempt
    b64="$(printf '%s' "$script" | base64 -w0)"
    for attempt in $(seq 1 "$RETRIES"); do
        out="$("$GH" codespace ssh -c "$name" -- "bash -lc 'echo $b64 | base64 -d | bash -l'" 2>&1)"
        rc=$?
        if ! grep -qE 'DeadlineExceeded|context deadline exceeded|i/o timeout|connection refused|SSH RPC|ssh server details' <<<"$out"; then
            printf '%s\n' "$out"
            return "$rc"
        fi
        log "第 $attempt/$RETRIES 次传输失败：$(head -1 <<<"$out")"
        [ "$attempt" -lt "$RETRIES" ] && sleep $((attempt * 2))
    done
    printf '%s\n' "$out"
    return "$rc"
}

ensure_proxy

# ---- 参数解析 ----
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CS="${CODESPACE:-}"

if [ "${1:-}" = "--stdin" ]; then
    CS="${2:-$CS}"; [ -n "$CS" ] || die "没给 codespace 名"
    SCRIPT="$(cat)"
    remote_call "$CS" "$SCRIPT"; exit $?
fi

case "${1:-}" in
  status|fix|url|ensure|install|version)
      SUB="$1"
      # ⭐ 脚本末尾要有换行，否则子命令会拼到上一行（实测症状：`status: command not found`）
      SCRIPT="$(cat "$HERE/alice-cloudctl.sh")
alice-cloudctl.sh $SUB"
      ;;
  ""|-h|--help)
      die "用法：CODESPACE=<名> $0 {status|fix|url|ensure|install|version} | $0 <本地脚本> | $0 --stdin <codespace>"
      ;;
  *)
      # 位置参数不是子命令 ⇒ 形态为：<codespace> <本地脚本>
      # ⚠️ 若已经用 $CODESPACE 指定过 codespace，则只给一个脚本路径即可（`$1` 就是脚本）
      F=""
      if [ -f "${1:-}" ]; then
          F="$1"
      else
          [ -n "$CS" ] || CS="$1"
          shift || true
          F="${1:-}"
      fi
      [ -n "$F" ] && [ -f "$F" ] || die "找不到脚本文件：$F"
      SCRIPT="$(cat "$F")"
      ;;
esac

[ -n "$CS" ] || die "没给 codespace 名（位置参数或 \$CODESPACE）"
remote_call "$CS" "$SCRIPT"
exit $?
