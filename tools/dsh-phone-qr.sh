#!/usr/bin/env bash
# 生成「手机访问云端 DSH」的二维码（顺带打印 URL）。
# 为什么需要它：DSH 的 token **每次服务重启都会换**，而手机端最省事的入口是
#   GitHub Codespaces 的转发域名 + ?token=…（端口是 private ⇒ 手机浏览器需已登录 GitHub）。
# 用法：bash tools/dsh-phone-qr.sh [输出路径]
set -uo pipefail
export PATH="$HOME/.local/bin:$PATH"
[ -n "${GH_TOKEN:-}" ] || export GH_TOKEN="$(tr -d '\r\n' < "$HOME/.gh-token" 2>/dev/null)"
CS="${ALICE_CODESPACE:-humble-tribble-97pv59gw5rg62prg5}"
OUT="${1:-/mnt/c/Users/dddgn/Desktop/dsh-手机扫码.png}"

# 当前 token：从服务的启动日志里取最后一条（服务重启后日志会追加新的）
TOK="$(gh codespace ssh -c "$CS" -- 'grep -oE "token=[A-Za-z0-9_-]+" ~/dsh-web.log 2>/dev/null | tail -1' 2>/dev/null | tr -d '\r' | cut -d= -f2)"
[ -n "$TOK" ] || { echo "取不到 token（服务没起？先跑 tools/codespace-zero.sh tunnel-bg）" >&2; exit 1; }

URL="https://${CS}-3081.app.github.dev/?token=${TOK}"
echo "手机 URL：$URL"
echo "本机 URL：http://127.0.0.1:3181/?token=${TOK}   （需先跑隧道）"

# 用桌面包自带的 qrcode 库生成（离线、不把 token 发给任何第三方）
J="$(mktemp --suffix=.js)"; cat > "$J" <<'JS'
const qrcode = require('D:/DSH Desktop/resources/app/node_modules/qrcode');
qrcode.toFile(process.argv[3], process.argv[2], { width: 520, margin: 2, errorCorrectionLevel: 'M' },
  (e) => { if (e) { console.error(e.message); process.exit(1); } console.log('ok'); });
JS
WINOUT="$(wslpath -w "$OUT" 2>/dev/null || echo "$OUT")"
powershell.exe -NoProfile -Command "node '$(wslpath -w "$J")' '$URL' '$WINOUT'" 2>&1 | tr -d '\r' | tail -1
rm -f "$J"
echo "二维码：$OUT"
