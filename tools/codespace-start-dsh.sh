#!/usr/bin/env bash
# 在 **Codespace 内**启动 DSH web —— 把"端口转发场景的两个必需参数"算在这里。
#
# 为什么需要这个脚本（两处都是我方实测，不是猜的）：
#   ① `dsh web` **默认只绑 `127.0.0.1`**（`ss -ltnp` 实测）⇒ Codespaces 的转发器从容器网络取端口，
#      不绑 `0.0.0.0` 就**外部连不上**；
#   ② `--trusted-host <authority>` 是 **`/api` 的浏览器信任围栏** ⇒ 用转发域名访问时不加它，
#      会出现「**页面能打开、一发消息就坏**」这种最难查的形态。
#
# 另：会话史按 **cwd** 分目录（`~/.dsh/sessions/<slug>/`，slug = cwd 把 `/` 换成 `-`）。
#     想接上本机那份历史，就让云端 cwd 与本机一致（本机主工作流 = `/home/fb486/projects`）：
#       DSH_WORKDIR=/home/fb486/projects tools/codespace-start-dsh.sh
#
# 用法（在 Codespace 终端里）：
#   tools/codespace-start-dsh.sh              # 前台跑，Ctrl-C 停
#   DSH_WORKDIR=~/projects tools/codespace-start-dsh.sh
#   DSH_TRUSTED_HOST=xxx-3081.app.github.dev tools/codespace-start-dsh.sh   # 自动推断失败时手填
set -euo pipefail

PORT="${DSH_PORT:-3081}"
WORKDIR="${DSH_WORKDIR:-$HOME/projects}"

# ---- 转发域名：Codespaces 通常导出这两个变量；拿不到就**响亮失败**（不猜）----
if [ -n "${DSH_TRUSTED_HOST:-}" ]; then
    TRUSTED="$DSH_TRUSTED_HOST"
elif [ -n "${CODESPACE_NAME:-}" ] && [ -n "${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}" ]; then
    TRUSTED="${CODESPACE_NAME}-${PORT}.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
else
    cat >&2 <<'EOF'
✗ 拿不到转发域名（CODESPACE_NAME / GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN 都没有）。
  ⇒ 打开 VS Code 的 **PORTS** 面板，找到 3081 那一行的转发地址，然后：
       DSH_TRUSTED_HOST=<那个域名，例如 xxx-3081.app.github.dev> tools/codespace-start-dsh.sh
  ⚠️ 别省这一步：不加 --trusted-host 会「页面能开、功能坏」。
EOF
    exit 2
fi

mkdir -p "$WORKDIR"
cd "$WORKDIR"
SLUG="$(printf '%s' "$WORKDIR" | tr '/' '-')"
echo "→ cwd = $WORKDIR（会话 slug 目录 = ~/.dsh/sessions/${SLUG}/）"
echo "→ bind 0.0.0.0:${PORT} · --trusted-host ${TRUSTED}"
echo "→ 外部入口（PORTS 面板同一行也有）：https://${TRUSTED}"

exec dsh web --host 0.0.0.0 --port "$PORT" --no-open --trusted-host "$TRUSTED"
