#!/usr/bin/env bash
# ============================================================================
#  无头回归电池（T2）—— 把"游戏内右键 alice:regression_battery"搬到无需真人的服务端
# ============================================================================
#
#  用法：
#      tools/headless-battery.sh                       # 跑 CORE（30 项）—— 生产服务端
#      tools/headless-battery.sh full                  # 跑 FULL（40 项）
#      tools/headless-battery.sh single:capability_gate  # 只跑一步（秒级，调试通道用）
#      tools/headless-battery.sh core --dev            # 退回 dev 服务端（gradlew runServer）
#      tools/headless-battery.sh core --keep-world     # 保留本轮世界（查现场）
#
#  退出码（**由脚本解析判决行得出，不是 Gradle 的退出码**，原因见下）：
#      0 = PASS        1 = FAIL        2 = DEGRADED（有步被跳过 ⇒ 不是绿）
#      3 = 没等到判决（超时/卡住）      4 = 起不来（假人/指派失败）
#      5 = 判决解析不出来 / 环境没准备好（脚本自身的问题）
#
# ============================ 为什么是"生产服务端" ============================
#  `--dev`（`./gradlew runServer`）**加载不了上游生产模组**：cofh_core 的 mixin
#  `@Shadow m_21211_` 在它自己的 refmap 里没有条目（实测只有 5 条别的成员），
#  而 dev 环境靠 SRG→named 重映射解析该名字 ⇒ `MixinApplyError` 直接崩服。
#  生产环境按 SRG 名原生解析，所以**只在 dev 犯病**。⇒ 默认走生产服务端：
#    · `mods/` = **我们自己构建的 Alice jar**（与发到客户端的同一个工件）+ 客户端那套模组；
#    · 环境与客户端同构（同样的 jar、同样的模组、同样的世界），只少了客户端渲染。
#  一次性准备：`tools/headless-battery.sh --install`（下 Forge 47.4.10 安装器并装服务端）。
#
# ============================ 为什么不能用 Gradle 退出码 ============================
#  `runServer` 是 Gradle 的 `JavaExec`，它把子进程退出码**吞掉** —— 服务端 JVM 无论退 1
#  还是 2，Gradle 一律只报 `finished with non-zero exit value N` 然后**自己退 1**。
#  ⇒ 唯一判据是服务端打的 `[Headless] RESULT verdict=… exit=…`（stdout + 日志 +
#  `<运行目录>/headless-result.txt` 三份），本脚本解析它。
#
# ============================ 为什么世界必须是客户端存档的副本 ============================
#  夹具的 `START_FOOT` 是**绝对坐标**（`(6,64,67)`、`(0,64,66)`、`(38,64,46)`、`(44,64,158)`…），
#  其中 `clear_retry` / `write_budget` / `scaffold` / `clear_guard` / `mine_regression`
#  这 5 个 CORE 步**既无场景函数也无 provision**，完全依赖该坐标处**已存在的世界地形**。
#  换个新生成的世界 ⇒ 这 5 步会对着随机地形跑出**假红**。
#  ⇒ 从 `ALICE_CLIENT_SAVE` 拷一份 `run/world-pristine` 作母本，每轮从母本复制、跑完丢弃
#    （所以无头跑**不存档**，见 `HeadlessBattery.exit` 的 halt）。
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 5

MODE="core"
BACKEND="prod"          # prod = 生产专用服务端（默认）；dev = gradlew runServer
KEEP_WORLD=no
DO_INSTALL=no
NO_BUILD=no             # prod 默认每轮重建 Alice 工件；--no-build 用现有 build/libs 里的 jar
TIMEOUT_SEC="${ALICE_HEADLESS_TIMEOUT:-1200}"

while [ $# -gt 0 ]; do
    case "$1" in
        core|full|single:*) MODE="$1" ;;
        --dev)              BACKEND="dev" ;;
        --prod)             BACKEND="prod" ;;
        --keep-world)       KEEP_WORLD=yes ;;
        --no-build)         NO_BUILD=yes ;;
        --install)          DO_INSTALL=yes ;;
        --timeout)          shift; TIMEOUT_SEC="$1" ;;
        -h|--help)          sed -n '2,55p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数：$1（-h 看用法）" >&2; exit 5 ;;
    esac
    shift
done

CLIENT_ROOT="/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10"
CLIENT_SAVE="${ALICE_CLIENT_SAVE:-$CLIENT_ROOT/saves/新的世界}"
CLIENT_MODS="${ALICE_CLIENT_MODS:-$CLIENT_ROOT/mods}"
SERVER_DIR="${ALICE_SERVER_DIR:-/home/fb486/alice-server}"
FORGE_VERSION="1.20.1-47.4.10"
FORGE_ARGS="$SERVER_DIR/libraries/net/minecraftforge/forge/$FORGE_VERSION/unix_args.txt"
PRISTINE="$REPO/run/world-pristine"
ARTIFACT="$REPO/build/libs/alice-1.0.0-1.20.1.jar"

# 客户端模组里**只在客户端有意义**的（生产服务端装了要么无意义、要么崩）：默认排除
CLIENT_ONLY_DEFAULT="[通用拼音搜索] jecharacters-1.20.1-forge-4.6.11.jar
[JEI物品管理器] jei-1.20.1-forge-15.58.0.209.jar"

say() { printf '[headless] %s\n' "$*"; }
die() { printf '[headless] 错误：%s\n' "$*" >&2; exit 5; }

# ---------------------------------------------------------------- --install
if [ "$DO_INSTALL" = "yes" ]; then
    mkdir -p "$SERVER_DIR"
    cd "$SERVER_DIR" || die "无法进入 $SERVER_DIR"
    if [ -f "$FORGE_ARGS" ]; then
        say "服务端已装好（$FORGE_ARGS 存在）⇒ 跳过安装"
        exit 0
    fi
    say "下载 Forge $FORGE_VERSION 安装器 → $SERVER_DIR"
    curl -sS -f -o forge-installer.jar \
        "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE_VERSION/forge-$FORGE_VERSION-installer.jar" \
        || die "下载安装器失败（需要网络）"
    say "安装服务端（会拉取约 150–200 MB 库）…"
    java -jar forge-installer.jar --installServer || die "安装失败，看 $SERVER_DIR/forge-installer.jar.log"
    printf 'eula=true\n' > eula.txt
    [ -f "$FORGE_ARGS" ] || die "装完了却找不到 $FORGE_ARGS —— 安装器布局变了"
    say "安装完成：$FORGE_ARGS"
    exit 0
fi

if [ "$BACKEND" = "prod" ] && [ ! -f "$FORGE_ARGS" ]; then
    die "生产服务端还没装。先跑：tools/headless-battery.sh --install"
fi

# ---------------------------------------------------------------- 世界母本
if [ ! -d "$PRISTINE" ]; then
    [ -d "$CLIENT_SAVE" ] || die "客户端存档不存在：$CLIENT_SAVE（可用 ALICE_CLIENT_SAVE 指定）"
    say "建原始世界母本（一次性）：$CLIENT_SAVE → $PRISTINE"
    rm -rf "$PRISTINE"
    cp -r "$CLIENT_SAVE" "$PRISTINE" || die "拷贝存档失败"
    rm -f "$PRISTINE/session.lock"
fi

# ---------------------------------------------------------------- 后端差异
if [ "$BACKEND" = "prod" ]; then
    WORLD="$SERVER_DIR/world"; MODS="$SERVER_DIR/mods"; RUN_DIR="$SERVER_DIR"
    LOG="$SERVER_DIR/logs/latest.log"; RESULT="$SERVER_DIR/headless-result.txt"
else
    WORLD="$REPO/run/world"; MODS="$REPO/run/mods"; RUN_DIR="$REPO/run"
    LOG="$REPO/run/logs/latest.log"; RESULT="$REPO/headless-result.txt"
fi

say "重置世界：$PRISTINE → $WORLD"
mkdir -p "$RUN_DIR" "$MODS" "$(dirname "$WORLD")"
rm -rf "$WORLD"
cp -r "$PRISTINE" "$WORLD" || die "复制世界失败"
# 场景数据包**每轮以仓库版为准**（母本里那份可能过期；HANDOVER 里原本是"手动复制 + /reload"）
rm -rf "$WORLD/datapacks/alice_test"
cp -r tools/test-scenes/alice_test "$WORLD/datapacks/alice_test" || die "装场景数据包失败"
# **Alice 的持久化状态一律清零**（`world/data/alice_*.dat`：假人 / 转移账本 / 区域状态 /
# 权限 / 安全区 / 决策状态 / 收集授权 / 世界改动账本）。两个理由：
#  ① **存档假人会让起服崩溃**：`BotManager.onServerStarted → restoreFromWorld → spawn`
#     在 `ServerStartedEvent` 里**同步**生成玩家 ⇒ `PlayerList.placeNewPlayer` 给新玩家下发
#     命令树 ⇒ WorldEdit 的 `ForgePlayer.<init>` 执行
#     `ThreadSafeCache.getInstance().getOnlineIds().add(uuid)`（字节码 line 69），
#     而此刻 WorldEdit 自己的 `ServerStartedEvent` handler 还没跑（缓存未初始化 ⇒ 不可变集合）
#     ⇒ `UnsupportedOperationException` ⇒ **服务端 tick 循环崩**（实测 crash-report 实证）。
#  ② 跨轮次残留（尤其 51 KB 的转移账本）会污染判决，无头基线必须是干净起点。
rm -f "$WORLD"/data/alice_*.dat

# ---------------------------------------------------------------- 模组
say "装模组 → $MODS"
rm -f "$MODS"/*.jar
if [ "$BACKEND" = "prod" ]; then
    if [ "$NO_BUILD" = "no" ]; then
        say "构建 Alice 工件（./gradlew build）…"
        ./gradlew build --no-daemon -q > /tmp/alice-headless-build.log 2>&1 \
            || { tail -30 /tmp/alice-headless-build.log; die "构建失败（详见 /tmp/alice-headless-build.log）"; }
    fi
    [ -f "$ARTIFACT" ] || die "找不到 Alice 工件 $ARTIFACT（先 ./gradlew build）"
    cp "$ARTIFACT" "$MODS/" || die "拷贝 Alice 工件失败"
    # 客户端那套模组全量搬过来（除客户端专属 + alice 自己 + 备份），保证与客户端同构
    CLIENT_ONLY="${ALICE_HEADLESS_CLIENT_ONLY-$CLIENT_ONLY_DEFAULT}"
    while IFS= read -r jar; do
        [ -n "$jar" ] || continue
        case "$jar" in alice-*.jar|*.bak.*) continue ;; esac
        if [ -n "$CLIENT_ONLY" ] && grep -Fxq "$jar" <<< "$CLIENT_ONLY"; then
            say "  跳过客户端专属：$jar"; continue
        fi
        [ -f "$CLIENT_MODS/$jar" ] || { say "  ⚠️ 客户端缺这个模组，跳过：$jar"; continue; }
        cp "$CLIENT_MODS/$jar" "$MODS/" || die "拷贝模组失败：$jar"
    done < <(ls "$CLIENT_MODS")
else
    # dev 后端**默认不装上游模组**：装了会因 mixin refmap 缺失崩服（见文件头）
    MODS_LIST="${ALICE_HEADLESS_MODS-}"
    while IFS= read -r jar; do
        [ -n "$jar" ] || continue
        cp "$CLIENT_MODS/$jar" "$MODS/" || die "拷贝模组失败：$jar"
    done <<< "$MODS_LIST"
fi
say "  $MODS: $(ls "$MODS" | tr '\n' ' ')"

# ---------------------------------------------------------------- 夹具洁净度（敌对生物清零）
# **为什么**（2026-09-15 实测）：`core` 跑出过一次
# `[alice] 假人死亡: Alice was blown up by Creeper → 直接清除` ⇒ 电池**没有判决**（exit=3）。
# 同轮更早的日志还显示 bot 被**推离预期格**（`PLACE_NO_VALID_FACE` + `feet=1,64,68`，起点 z=66）
# ⇒ 会话假红（`ASCEND_NO_HEADROOM` / `*_STALE_START` 这类"位置不对"的码都能由它造成）。
# 电池测的是**寻路/任务的确定性**，不是"能不能在怪物手里活下来"⇒ 敌对生物是**噪声源**，必须清零。
# 做法：让**无头服务端**跑 `peaceful`（改它自己的 `server.properties`；不碰客户端存档，
# 且 `peaceful` 会把**存档里已经有的**敌对生物一起清掉，不只是停止新生成）。
# ⚠️ 与客户端**有意不同构**：客户端电池仍可能被怪物干扰 —— 见
# `docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md` §遗留。
if [ "$BACKEND" = "prod" ] && [ -f "$SERVER_DIR/server.properties" ]; then
    PROPS="$SERVER_DIR/server.properties"
    if grep -qE '^difficulty=' "$PROPS"; then
        sed -i 's/^difficulty=.*/difficulty=peaceful/' "$PROPS"
    else
        printf 'difficulty=peaceful\n' >> "$PROPS"
    fi
    say "夹具洁净度：difficulty=$(grep -E '^difficulty=' "$PROPS" | head -1 | cut -d= -f2)"
fi

# ---------------------------------------------------------------- 跑
rm -f "$RESULT" "$LOG"
say "启动无头服务端（$BACKEND）：mode=$MODE timeout=${TIMEOUT_SEC}s"
START=$(date +%s)
if [ "$BACKEND" = "prod" ]; then
    # ⚠️ `-D` 必须放在 `@args` **之前**：`unix_args.txt` 里含 main class，放在它后面会被
    # 当成**程序参数**而不是 JVM 属性（那样 HeadlessBattery 读不到开关，服务端会一直空跑）。
    # `ALICE_EXTRA_JVM_ARGS`（可选）：额外 JVM 属性，用于 A/B 对照（例如
    #   `ALICE_EXTRA_JVM_ARGS="-Dalice.bot.vanillaTick=true" tools/headless-battery.sh core`
    # 验 D-230 的"完整原版 tick"模式）。留空 = 与平时完全一致。
    ( cd "$SERVER_DIR" && exec java -Xmx3G "-Dalice.headless.battery=$MODE" ${ALICE_EXTRA_JVM_ARGS:-} \
        "@user_jvm_args.txt" "@libraries/net/minecraftforge/forge/$FORGE_VERSION/unix_args.txt" nogui ) \
        > /tmp/alice-headless-server.log 2>&1 &
else
    ./gradlew runServer --no-daemon "-Dalice.headless.battery=$MODE" \
        > /tmp/alice-headless-server.log 2>&1 &
fi
SRV=$!

VERDICT=""
for _ in $(seq 1 "$TIMEOUT_SEC"); do
    if [ -f "$RESULT" ]; then
        VERDICT="$(grep -ao 'verdict=[A-Za-z]*' "$RESULT" | head -1 | cut -d= -f2)"
    elif [ -f "$LOG" ]; then
        VERDICT="$(grep -ao 'Headless\] RESULT verdict=[A-Za-z]*' "$LOG" | head -1 | sed 's/.*verdict=//')"
    fi
    if [ -n "$VERDICT" ]; then
        say "判决行已出现：verdict=$VERDICT（+$(( $(date +%s) - START ))s）"
        for _ in $(seq 1 20); do          # 判决已落盘 ⇒ 再给 20s 自己收尾，收不掉就杀
            kill -0 "$SRV" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$SRV" 2>/dev/null; then
            say "⚠️ 判决已出但进程仍未退出 ⇒ 主动终止（不影响判决）"
            kill -TERM "-$SRV" 2>/dev/null || kill -TERM "$SRV" 2>/dev/null
            sleep 3
            kill -KILL "-$SRV" 2>/dev/null || kill -KILL "$SRV" 2>/dev/null
            SRV_HUNG=yes
        fi
        break
    fi
    kill -0 "$SRV" 2>/dev/null || break
    sleep 1
done

if kill -0 "$SRV" 2>/dev/null; then
    say "⚠️ ${TIMEOUT_SEC}s 内没有判决行 ⇒ 超时，终止"
    kill -TERM "-$SRV" 2>/dev/null || kill -TERM "$SRV" 2>/dev/null
    sleep 3
    kill -KILL "-$SRV" 2>/dev/null || kill -KILL "$SRV" 2>/dev/null
fi
wait "$SRV" 2>/dev/null; SRV_EXIT=$?
ELAPSED=$(( $(date +%s) - START ))

# ---------------------------------------------------------------- 判决
case "$VERDICT" in
    PASS)     CODE=0 ;;
    DEGRADED) CODE=2 ;;
    FAIL)     CODE=1 ;;
    "")       CODE=3 ;;
    *)        say "无法识别的判决：$VERDICT"; CODE=5 ;;
esac

say "──── 结果 ────"
say "verdict=${VERDICT:-<无>} exit=$CODE 用时=${ELAPSED}s 进程退出码=$SRV_EXIT${SRV_HUNG:+  进程_hung=yes}"
grep -a 'Regression\] SUMMARY' "$LOG" 2>/dev/null | tail -1 | cut -c1-600
say "日志：$LOG（服务端 stdout：/tmp/alice-headless-server.log）"
[ "$KEEP_WORLD" = "no" ] || say "（--keep-world：$WORLD 已保留）"
exit "$CODE"
