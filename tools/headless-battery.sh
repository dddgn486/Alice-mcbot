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
# ============================ 环境变量 ============================
#   ALICE_EXTRA_JVM_ARGS="…"     额外 JVM 属性（A/B 对照用；例：-Dalice.bot.vanillaTick=true）
#   ALICE_KEEP_ALICE_DATA=1      保留世界里的 `alice_*.dat`（默认清掉 = 干净起点）
#   ALICE_SAVE_ON_HALT=1         停机前同步存档（配 `-Dalice.headless.saveOnHalt=true`，见 HeadlessBattery）
#   —— 后两个是**持久化实验**专用（D-235）：`SavedData` 里的状态只有存档才看得见。
#
# ============================ CORE 结果缓存（2026-09-20） ============================
#  动机（用户 2026-09-20 定，观测指标"从改一行到知道对不对"）：CORE 真跑 ≈ 4–5 min，
#  而 `check-all` 每次都跑它 ⇒ 绝大多数时间花在**与本次改动无关**的回归上。
#  ⇒ `core`（且仅 `core`、且仅默认 prod/不保留世界/无 A/B 开关）时：
#     **源码指纹一致 + 上次判决 PASS ⇒ 直接复用判决，秒级返回**。
#  ⚠️ **绝不允许假绿**（比慢贵得多），因此：
#    · 指纹 = `src/` + `tools/` + 构建脚本 + **世界母本** + **上游模组 jar** + `server.properties`
#      （**剔注释行** + difficulty 归一 —— 第一版没剔 ⇒ 保存时间戳每次 boot 都变 ⇒ 永不命中）
#      + `unix_args.txt` + `java -version` + （`--no-build` 时）工件 sha
#      —— **输入变了就必然不匹配**；
#    · 缓存**缺失 / 读不动 / 指纹不匹配 / 上次不是 PASS** ⇒ **真跑**（没有任何静默复用路径）；
#    · 命中时会**大声**打一行 `缓存复用`（含指纹前 12 位 + 真实轮次时间戳）⇒ 读日志的人不会误以为刚跑过；
#    · `--no-cache`（或 `ALICE_BATTERY_NO_CACHE=1`）**强制真跑**。
#    · 缓存文件 `run/.cache/core-verdict.txt`（`run/` 在 `.gitignore` 里 ⇒ 本机状态，不进仓库）。
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 5

MODE="core"
BACKEND="prod"          # prod = 生产专用服务端（默认）；dev = gradlew runServer
KEEP_WORLD=no
DO_INSTALL=no
NO_BUILD=no             # prod 默认每轮重建 Alice 工件；--no-build 用现有 build/libs 里的 jar
# 强制真跑开关：`--no-cache` 或 `ALICE_BATTERY_NO_CACHE=<真值>`。
# ⚠️ 真值判定写成显式 case：**第一版只认字符串 `yes`** ⇒ `ALICE_BATTERY_NO_CACHE=1`（文档里就是这么写的）
#    被静默当成"关"，反向对照一跑就露出（控制 ③ 实测命中缓存 = 假绿）。文档说了 `=1`，代码就必须认 `1`。
case "${ALICE_BATTERY_NO_CACHE:-}" in ""|0|no|false|NO|False) NO_CACHE=no ;; *) NO_CACHE=yes ;; esac
TIMEOUT_SEC="${ALICE_HEADLESS_TIMEOUT:-1200}"

while [ $# -gt 0 ]; do
    case "$1" in
        core|full|single:*|module:*|list-modules) MODE="$1" ;;
        --dev)              BACKEND="dev" ;;
        --prod)             BACKEND="prod" ;;
        --keep-world)       KEEP_WORLD=yes ;;
        --reuse-world)      REUSE_WORLD=yes ;;   # 不重置世界（持久化/两轮实验用，如 death-persistence-e2e）
        --no-build)         NO_BUILD=yes ;;
        --no-cache)         NO_CACHE=yes ;;      # 强制真跑（忽略 CORE 结果缓存，见文件头）
        --install)          DO_INSTALL=yes ;;
        --timeout)          shift; TIMEOUT_SEC="$1" ;;
        -h|--help)          sed -n '2,72p' "${BASH_SOURCE[0]}"; exit 0 ;;
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
[JEI物品管理器] jei-1.20.1-forge-15.58.0.209.jar
xaeroworldmap-forge-1.20.1-1.46.0.jar"   # D-324：Xaero 世界地图是纯客户端模组（专用服务端不该加载）

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
    # ⚠️ 必须自己建父目录：`run/` 整个被 .gitignore ⇒ **在全新检出（云端）里 run/ 根本不存在**，
    #    而本地这台机器的 run/ 常年存在 ⇒ 这个前提**从没被暴露过**（2026-09-24 云端首跑实测踩到：
    #    `cp: cannot create directory '.../run/world-pristine': No such file or directory`）。
    mkdir -p "$(dirname "$PRISTINE")" || die "无法创建 $(dirname "$PRISTINE")"
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

# ---------------------------------------------------------------- CORE 结果缓存（见文件头）
CACHE_DIR="$REPO/run/.cache"
CACHE_FILE="$CACHE_DIR/core-verdict.txt"

# 指纹 = **判决的全部输入**。任何一项变了 ⇒ 不匹配 ⇒ 真跑。
# ⚠️ 宁可比实际需要**更宽**（多算一个偶尔变化的输入 ⇒ 只是少命中），也**不许更窄**（少算 ⇒ 假绿）。
core_fingerprint() {
    {
        # ① Alice 源码 + 工具链 + 构建脚本（候选/夹具/数据包都在这里）
        ( cd "$REPO" && find src tools build.gradle settings.gradle gradle.properties \
              gradle/wrapper/gradle-wrapper.properties -type f -print0 2>/dev/null \
            | sort -z | xargs -0 -r sha256sum )
        # ② 世界母本：5 个 CORE 步（clear_retry/write_budget/scaffold/clear_guard/mine_regression）
        #    的 START_FOOT 是绝对坐标，完全依赖它的地形 ⇒ **它是输入，不是环境**
        [ -d "$PRISTINE" ] && ( cd "$PRISTINE" && find . -type f ! -name 'session.lock' -print0 \
            | sort -z | xargs -0 -r sha256sum )
        # ③ 上游模组 jar（alice 自己排除在外：它由 ① 的 src 决定）
        [ -d "$CLIENT_MODS" ] && ( cd "$CLIENT_MODS" && find . -maxdepth 1 -type f -name '*.jar' \
              ! -name 'alice-*.jar' ! -name '*.bak.*' -print0 | sort -z | xargs -0 -r sha256sum )
        printf 'client_only=%s\n' "${ALICE_HEADLESS_CLIENT_ONLY-$CLIENT_ONLY_DEFAULT}"
        printf 'jvm=%s\n' "$(java -version 2>&1 | head -1)"
        # ④ 服务端属性（把脚本自己会改的 difficulty 归一 + **去掉注释行**——第一条注释是
        #    `Properties.store()` 写的**保存时间戳**，每次 boot 都变 ⇒ 不做这步缓存永远不命中，见实测教训）
        [ -f "$SERVER_DIR/server.properties" ] \
            && sed -e '/^#/d' -e '/^[[:space:]]*$/d' -e 's/^difficulty=.*/difficulty=peaceful/' \
                   "$SERVER_DIR/server.properties" | sha256sum
        # ⑤ Forge 库清单（换 Forge/库布局 ⇒ 变）
        [ -f "$FORGE_ARGS" ] && sha256sum "$FORGE_ARGS"
        # ⑥ `--no-build` 时**真正被测的是现成工件**，不是源码 ⇒ 必须把它算进来
        [ "$NO_BUILD" = "yes" ] && [ -f "$ARTIFACT" ] && sha256sum "$ARTIFACT"
    } 2>/dev/null
}

# 只有"默认那一轮"才配用缓存：A/B 开关、保留世界、dev 后端、单步/模块模式都**必须真跑**。
if [ "$MODE" = "core" ] && [ "$BACKEND" = "prod" ] && [ "$NO_CACHE" != "yes" ] \
   && [ "$KEEP_WORLD" = "no" ] && [ "${REUSE_WORLD:-no}" != "yes" ] && [ "$NO_BUILD" != "yes" ] \
   && [ -z "${ALICE_EXTRA_JVM_ARGS:-}" ] && [ "${ALICE_KEEP_ALICE_DATA:-0}" != "1" ]; then
    FP="$(core_fingerprint | sha256sum | cut -d' ' -f1)"
    say "CORE 指纹（src+tools+世界母本+上游模组+JVM）：${FP:0:12}"
    CACHED_FP="$(sed -n 's/^fingerprint=//p' "$CACHE_FILE" 2>/dev/null | head -1)"
    CACHED_VERDICT="$(sed -n 's/^verdict=//p' "$CACHE_FILE" 2>/dev/null | head -1)"
    if [ -n "$FP" ] && [ "$CACHED_FP" = "$FP" ] && [ "$CACHED_VERDICT" = "PASS" ]; then
        CACHED_META="$(sed -n 's/^stamp=//p' "$CACHE_FILE" | head -1) ticks=$(sed -n 's/^ticks=//p' "$CACHE_FILE" | head -1)"
        say "──── 结果 ────（本轮**没有跑服务端**）"
        say "verdict=PASS exit=0 用时=0s 缓存复用（指纹=${FP:0:12} 与上次绿轮一致；上次真跑 ${CACHED_META}）"
        say "⚠️ 这不是新证据：要真跑用 --no-cache（或 ALICE_BATTERY_NO_CACHE=1）—— 改任何源码/工具/世界母本/模组都会自动失效"
        exit 0
    fi
    if [ -f "$CACHE_FILE" ]; then
        say "缓存不可用（缓存指纹=${CACHED_FP:0:12} 判决=${CACHED_VERDICT:-<空>}）⇒ 真跑"
    else
        say "无缓存 ⇒ 真跑"
    fi
fi

mkdir -p "$RUN_DIR" "$MODS" "$(dirname "$WORLD")"
if [ "${REUSE_WORLD:-no}" = "yes" ]; then
    # `--reuse-world`：**不重置世界** —— 给"两轮"实验用（第一轮留下落盘状态，第二轮读回来验证）
    say "保留上一轮世界（--reuse-world）：$WORLD"
    [ -d "$WORLD" ] || die "--reuse-world 但世界目录不存在：$WORLD（先跑一轮）"
else
    say "重置世界：$PRISTINE → $WORLD"
    rm -rf "$WORLD"
    cp -r "$PRISTINE" "$WORLD" || die "复制世界失败"
fi
# 场景数据包**每轮以仓库版为准**（母本里那份可能过期；HANDOVER 里原本是"手动复制 + /reload"）
#
# ⚠️ `D-412`（2026-09-23 实测，**静默且能骗过 `scene rc=`**）：`datapacks/` 下**每个子目录都是一个活数据包**。
#    历史备份被命名成 `alice_test.bak.<时间戳>` 留在同一个目录里 ⇒ 它们**同样提供 `alice_test` 命名空间**，
#    且加载顺序在 `alice_test` **之后** ⇒ **旧场景盖住新场景**，而 `rc` 照样是 855（命令确实执行了，
#    只是落在**旧坐标**上，那里的区块又没被握 ⇒ 什么都不落地）。结果：夹具拿"空世界"判断，报内核失败码。
#    ⇒ 复制前**清掉一切同命名空间的旧包**，并**断言只剩一个提供者**（宁可响亮失败，不要静默用旧的）。
rm -rf "$WORLD/datapacks/alice_test"
cp -r tools/test-scenes/alice_test "$WORLD/datapacks/alice_test" || die "装场景数据包失败"
for other in "$WORLD"/datapacks/*/; do
    [ -d "$other/data/alice_test" ] || continue
    [ "$(basename "$other")" = "alice_test" ] && continue
    rm -rf "$other"
    say "⚠️ 清掉同命名空间的旧数据包：$(basename "$other")（它会**静默盖住**本轮场景 —— D-412）"
done
providers=0
for d in "$WORLD"/datapacks/*/; do
    [ -d "$d/data/alice_test" ] && providers=$((providers + 1))
done
[ "$providers" = "1" ] || die "场景数据包提供者应为 1 个，实测 $providers 个（同命名空间的包会互相盖住 —— D-412）"
# **第三方认领也一律从「夹具世界」里清掉**（`D-409`/`D-413`）：
#   夹具世界是**玩家存档的副本** ⇒ 夹具会**继承玩家的 FTB Chunks 认领**，而认领内的破坏/放置
#   会被 FTB **静默取消**（`gameMode.destroyBlock` 返回 false）⇒ 夹具拿到"树砍不动"的世界，
#   **却报成内核失败码**（`lumber_job` 连红 35 轮的真因）。与上面"清 `alice_*.dat`"**同一条理由**：
#   **夹具世界是给 Alice 的代码用的，不是给第三方模组状态用的**。要测第三方保护本身，
#   `break_refused` 会在运行期**自己造**一个认领（它不依赖预存认领）。
#   ⚠️ 只动**本轮副本**（`$WORLD`）—— 绝不碰母本与客户端存档；`ALICE_KEEP_FTB_CLAIMS=1` 可保留。
if [ "${ALICE_KEEP_FTB_CLAIMS:-0}" != "1" ] && [ -d "$WORLD/ftbchunks" ]; then
    ftb_files="$(find "$WORLD/ftbchunks" -maxdepth 1 -type f -name '*.snbt' | wc -l)"
    rm -f "$WORLD"/ftbchunks/*.snbt
    say "已清第三方认领（$ftb_files 份 ftbchunks/*.snbt ⇒ 本轮夹具世界无认领；D-409/D-413）"
fi
# **Alice 的持久化状态一律清零**（`world/data/alice_*.dat`：假人 / 转移账本 / 区域状态 /
# 权限 / 安全区 / 决策状态 / 收集授权 / 世界改动账本）。两个理由：
#  ① **存档假人会让起服崩溃**：`BotManager.onServerStarted → restoreFromWorld → spawn`
#     在 `ServerStartedEvent` 里**同步**生成玩家 ⇒ `PlayerList.placeNewPlayer` 给新玩家下发
#     命令树 ⇒ WorldEdit 的 `ForgePlayer.<init>` 执行
#     `ThreadSafeCache.getInstance().getOnlineIds().add(uuid)`（字节码 line 69），
#     而此刻 WorldEdit 自己的 `ServerStartedEvent` handler 还没跑（缓存未初始化 ⇒ 不可变集合）
#     ⇒ `UnsupportedOperationException` ⇒ **服务端 tick 循环崩**（实测 crash-report 实证）。
#  ② 跨轮次残留（尤其 51 KB 的转移账本）会污染判决，无头基线必须是干净起点。
if [ "${ALICE_KEEP_ALICE_DATA:-0}" = "1" ]; then
    # **持久化实验用**（D-235）：保留世界里的 `alice_*.dat`（默认清掉是为了"干净起点"，
    # 但那也让"结清是否落盘"永远看不见）。配合 `ALICE_EXTRA_JVM_ARGS=-Dalice.headless.saveOnHalt=true`
    # 使用：跑完后解压 `$WORLD/data/alice_*.dat` 读回真实落盘状态。
    say "ALICE_KEEP_ALICE_DATA=1 ⇒ 保留 $WORLD/data/alice_*.dat（持久化实验）"
else
    rm -f "$WORLD"/data/alice_*.dat
fi

# ---------------------------------------------------------------- 模组
say "装模组 → $MODS"
rm -f "$MODS"/*.jar
if [ "$BACKEND" = "prod" ]; then
    if [ "$NO_BUILD" = "no" ]; then
        say "构建 Alice 工件（./gradlew build）…"
        # ⚠️ 2026-09-20 实测：构建卡死 30+ 分钟的真因是 **Gradle 在等网络**
        # （`jstack` 见 `Socket.connect`、`ss` 见 `SYN-SENT` 对 :443 永不返回）⇒ 网络不通时用
        # `ALICE_GRADLE_OFFLINE=1` 走离线构建（依赖缓存是热的；缺依赖会**响亮失败**而不是静默等待）。
        ./gradlew build --no-daemon ${ALICE_GRADLE_OFFLINE:+--offline} -q > /tmp/alice-headless-build.log 2>&1 \
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
# ⚠️ 旧写法带 `[ -f "$SERVER_DIR/server.properties" ]` 守卫 ⇒ **在全新服务端目录上静默跳过**
#    （该文件是服务端**首次启动时**才生成的）。2026-09-24 云端首跑实测后果：服务端自建
#    `difficulty=easy` ⇒ CORE 的 `damage_event_visible` 读到额外伤害源（`hits=4 total=4.0`）
#    ⇒ **假红 40/41**；本地因为文件早已存在、且早被这段代码钉过，所以**从未暴露**。
#    ⇒ 现在**先把文件建出来再钉**（服务端启动时会补齐其余默认项）。
if [ "$BACKEND" = "prod" ]; then
    PROPS="$SERVER_DIR/server.properties"
    mkdir -p "$SERVER_DIR"
    [ -f "$PROPS" ] || : > "$PROPS"
    if grep -qE '^difficulty=' "$PROPS"; then
        sed -i 's/^difficulty=.*/difficulty=peaceful/' "$PROPS"
    else
        printf 'difficulty=peaceful\n' >> "$PROPS"
    fi
    say "夹具洁净度：difficulty=$(grep -E '^difficulty=' "$PROPS" | head -1 | cut -d= -f2)（已钉死为测试前提）"
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
        VERDICT="$(grep -ao 'verdict=[A-Za-z_]*' "$RESULT" | head -1 | cut -d= -f2)"
    elif [ -f "$LOG" ]; then
        VERDICT="$(grep -ao 'Headless\] RESULT verdict=[A-Za-z_]*' "$LOG" | head -1 | sed 's/.*verdict=//')"
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
    list_modules) say "模块清单：$(grep -aoE 'MODULES (ids|expected)=[a-zA-Z0-9_,:]*' /tmp/alice-headless-server.log 2>/dev/null | tail -2)"
                  CODE=0 ;;
    unknown_step) say "步名不存在（服务端已给出已知步数与相近候选，见上方 [Headless] 未知步名 一行）"; CODE=6 ;;
    *)        say "无法识别的判决：$VERDICT"; CODE=5 ;;
esac

# 生效前提（**不钉的项也打印**：未来两端再有分歧，一眼可见，不必靠猜）
if [ "$BACKEND" = "prod" ] && [ -f "$SERVER_DIR/server.properties" ]; then
    say "前提(effective)：$(grep -E '^(difficulty|spawn-monsters|pvp|allow-flight|online-mode|spawn-protection|level-type)=' "$SERVER_DIR/server.properties" | tr '\n' ' ')"
fi
say "──── 结果 ────"
say "verdict=${VERDICT:-<无>} exit=$CODE 用时=${ELAPSED}s 进程退出码=$SRV_EXIT${SRV_HUNG:+  进程_hung=yes}"
grep -a 'Regression\] SUMMARY' "$LOG" 2>/dev/null | tail -1 | cut -c1-600
grep -aoE 'MODULES ids=[a-z0-9_,]*' /tmp/alice-headless-server.log 2>/dev/null | tail -1
# **每轮自动留档服务端 stdout**（2026-09-17 两次实测教训：起下一轮会**覆盖** /tmp/alice-headless-server.log
# ⇒ 上一轮的证据（夹具逐行事实、失败理由、SUMMARY）会**无声消失**，事后无法复核 ✗）。
# 这条纪律不写在散文里 —— 直接做成脚本行为：**跑完就复制一份**，并把归档路径打在结果块里 ✓。
ARCHIVE_DIR="${REPO}/run/headless-logs"
mkdir -p "$ARCHIVE_DIR"
ARCHIVE="${ARCHIVE_DIR}/$(date +%Y%m%d-%H%M%S)-$(printf '%s' "$MODE" | tr ':/' '__').log"
if [ -f /tmp/alice-headless-server.log ]; then
    cp /tmp/alice-headless-server.log "$ARCHIVE"
fi
say "日志：$LOG（服务端 stdout：/tmp/alice-headless-server.log ⇒ **已归档** $ARCHIVE）"
[ "$KEEP_WORLD" = "no" ] || say "（--keep-world：$WORLD 已保留）"

# ---------------------------------------------------------------- 写结果缓存（**只写 PASS**）
# ⚠️ 只缓存 PASS：红/降级/无判决一律不写（也就永远不会被复用 ⇒ 不存在"把红记成绿"）。
# 用 tmp + mv ⇒ 不会留下半截文件（真半截了也只是指纹不匹配 ⇒ 真跑）。
if [ -n "${FP:-}" ] && [ "$CODE" -eq 0 ]; then
    mkdir -p "$CACHE_DIR"
    {
        printf 'fingerprint=%s\n' "$FP"
        printf 'verdict=PASS\n'
        printf 'stamp=%s\n' "$(date +%Y-%m-%dT%H:%M:%S)"
        printf 'ticks=%s\n' "$(grep -aoE 'ticks=[0-9]+' "$ARCHIVE" 2>/dev/null | tail -1 | cut -d= -f2)"
        printf 'elapsed=%ss\n' "$ELAPSED"
        printf 'artifact_sha256=%s\n' "$(sha256sum "$ARTIFACT" 2>/dev/null | cut -d' ' -f1)"
    } > "$CACHE_FILE.tmp" 2>/dev/null && mv "$CACHE_FILE.tmp" "$CACHE_FILE"
    say "已写结果缓存：$CACHE_FILE（指纹=${FP:0:12} verdict=PASS；改源码/工具/世界母本/模组即自动失效）"
fi
exit "$CODE"
