#!/usr/bin/env bash
# ==============================================================================
# 云端开机钩子（devcontainer `postStartCommand`）—— 2026-10-02 加
#
# 为什么需要它（用户 2026-10-02 的硬需求：「新设备跟云端交互，不然迁移干嘛」）：
#   新设备**不能**用 `gh codespace ssh`（那台机器的 `~/.ssh` 内容读被系统层挡住，
#   实测连 `cmd /c type` 都挂）⇒ **整条 SSH 通道在新设备上是废的**。
#   而云端 `dsh web` 的入口地址带**每次启动新生成的令牌** ⇒ 新设备怎么拿到它？
#   ⇒ 由**云端自己在每次启动时**把入口地址**发布到 git 分支**（`steward/entry`），
#     新设备 `git fetch` + `gh codespace ports`（纯 API）即可自助打开 UI。
#
# ⛔ 纪律：**绝不阻塞启动**。任何一步失败都只记一行、继续走、最后 `exit 0`
#   —— 开机钩子挂掉会让整个 codespace 起不来，那比"没发布地址"严重得多。
# ==============================================================================
set +e

LOG="$HOME/dsh-web.log"
ENTRY_FILE_NAME="entry.txt"
REPO="/workspaces/Alice-mcbot"
BRANCH="steward/entry"
FORWARD_DOMAIN="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
CS_NAME="${CODESPACE_NAME:-unknown}"
TRUSTED_HOST="${CS_NAME}-3081.${FORWARD_DOMAIN}"
PORT=3081

say() { echo "[post-start] $*"; }

# ① 信箱目录（幂等）
mkdir -p "$HOME/bus/to-win" "$HOME/bus/to-cloud" "$HOME/client-info" "$HOME/outbox" 2>/dev/null

# ② 起 dsh web（已经在跑就不动它）
if pgrep -f "[d]sh web" >/dev/null 2>&1; then
    say "dsh web already running (pid=$(pgrep -f '[d]sh web' | head -1))"
else
    D="$(command -v dsh || echo /usr/local/share/nvm/current/bin/dsh)"
    if [ -x "$D" ] || command -v "$D" >/dev/null 2>&1; then
        say "starting: $D web --port $PORT --no-open --trusted-host $TRUSTED_HOST"
        ( cd "$REPO" && setsid nohup "$D" web --port "$PORT" --no-open --trusted-host "$TRUSTED_HOST" >> "$LOG" 2>&1 & )
        for _ in $(seq 1 30); do sleep 2; pgrep -f "[d]sh web" >/dev/null 2>&1 && break; done
        say "dsh web pid=$(pgrep -f '[d]sh web' | head -1)"
    else
        say "WARN: dsh not found - skip starting the web UI"
    fi
fi

# ③ 抓令牌（DSH 启动时会打印一次的带令牌 URL；日志里可能有多条 ⇒ 取最后一条）
TOKEN=""
for _ in $(seq 1 20); do
    TOKEN="$(grep -o 'token=[A-Za-z0-9_-]\{8,\}' "$LOG" 2>/dev/null | tail -1 | cut -d= -f2)"
    [ -n "$TOKEN" ] && break
    sleep 2
done
if [ -z "$TOKEN" ]; then
    say "WARN: no token found in $LOG - nothing published"
    exit 0
fi

LOOPBACK_URL="http://127.0.0.1:${PORT}/?token=${TOKEN}"
FORWARD_URL="https://${TRUSTED_HOST}/?token=${TOKEN}"

# ④ 写到仓库并推到专用分支（纯 HTTPS + 容器自带的 GITHUB_TOKEN ⇒ 不需要 SSH 密钥）
DIR="$REPO/steward-entry"
mkdir -p "$DIR" 2>/dev/null
{
    echo "# 云端入口（每次启动自动重写 —— 别手改这个文件）"
    echo
    echo "generated_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "codespace: $CS_NAME"
    echo "state: Available"
    echo
    echo "## 新设备用这个（HTTPS 转发域名；不需要 SSH 密钥）"
    echo "url: $FORWARD_URL"
    echo
    echo "## 挂了隧道的老机器用这个（回环）"
    echo "loopback: $LOOPBACK_URL"
} > "$DIR/$ENTRY_FILE_NAME" 2>/dev/null
cp "$DIR/$ENTRY_FILE_NAME" "$HOME/steward-entry.txt" 2>/dev/null

cd "$REPO" || { say "WARN: repo missing"; exit 0; }
git config user.email "codespace@github.com" >/dev/null 2>&1
git config user.name "alice-codespace" >/dev/null 2>&1
# ⚠️ 用 `git hash-object` 那条路绕开工作树：钩子跑在**已存在的容器**里，工作树可能是脏的，
#    我们**绝不想**把别的东西一起提交进去。⇒ 只把这一个文件做成一次提交，推分支后回到原状。
BLOB=$(git hash-object -w "$DIR/$ENTRY_FILE_NAME" 2>/dev/null)
TREE=$(printf '100644 blob %s\t%s\n' "$BLOB" "$ENTRY_FILE_NAME" | git mktree 2>/dev/null)
if [ -n "$TREE" ]; then
    PARENT=$(git rev-parse -q --verify "refs/heads/$BRANCH" 2>/dev/null || git rev-parse -q --verify "origin/$BRANCH" 2>/dev/null || echo "")
    if [ -n "$PARENT" ]; then
        NEW=$(git commit-tree "$TREE" -p "$PARENT" -m "entry: cloud DSH web URL @ $(date -u +%Y-%m-%dT%H:%M:%SZ)" 2>/dev/null)
    else
        NEW=$(git commit-tree "$TREE" -m "entry: cloud DSH web URL @ $(date -u +%Y-%m-%dT%H:%M:%SZ)" 2>/dev/null)
    fi
    if [ -n "$NEW" ]; then
        # ⚠️ 实测（2026-10-02）：**启动阶段 `git push origin` 会失败**（那时没有登录 shell 的凭据）
        #    ⇒ 手动跑同一个脚本却成功。⇒ 显式用容器自带的 `GITHUB_TOKEN` 拼 push URL。
        # ⚠️ 实测（2026-10-02 深夜）：`GITHUB_TOKEN` 在某些上下文（如 ssh 拿到的 `bash -lc`）里**是空的**
        #    ⇒ 拼出来的 URL 变成 `x-access-token:@…` ⇒ 认证失败。⇒ **有才用，没有就回退 origin**，
        #    并且**不再把 stderr 丢掉**（否则又是一次"静默失败"）。
        PUSH_URL=""
        if [ -n "$GITHUB_TOKEN" ]; then
            PUSH_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY:-dddgn486/Alice-mcbot}.git"
        fi
        # ⚠️ 实测：父提交对不上会报 **non-fast-forward** ⇒ 被拒。本分支语义就是「**只存最新一条**」
        #    （只有一个写者：云端这个钩子；设备只读）⇒ **--force 才是正确语义**，不是绕过保护。
        PUSHED=1
        if [ -n "$PUSH_URL" ]; then
            git push -q --force "$PUSH_URL" "$NEW:refs/heads/$BRANCH" 2>&1 | sed 's/^/    push: /'
            PUSHED=${PIPESTATUS[0]}
        fi
        if [ "$PUSHED" -ne 0 ]; then
            say "  token 路径没成（GITHUB_TOKEN ${GITHUB_TOKEN:+有}${GITHUB_TOKEN:-空}）⇒ 回退 origin"
            git push -q --force origin "$NEW:refs/heads/$BRANCH" 2>&1 | sed 's/^/    push: /'
            PUSHED=${PIPESTATUS[0]}
        fi
        if [ "$PUSHED" -eq 0 ]; then
            say "published entry -> branch $BRANCH (commit $NEW)"
        else
            say "WARN: git push failed - entry not published (device can still use gh codespace ports + this log)"
        fi
    else
        say "WARN: commit-tree failed"
    fi
fi

# ⑤ 顺手把带令牌的地址也打进容器日志（`gh codespace logs` 走 API 能读到，是第二条兜底）
say "ENTRY_FORWARD_URL=$FORWARD_URL"
exit 0
