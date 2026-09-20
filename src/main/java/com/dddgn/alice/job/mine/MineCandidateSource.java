package com.dddgn.alice.job.mine;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.CandidateSource;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 挖掘候选来源（L3 决策缝之一，切片 J5）：**第二个 {@link CandidateSource} 实现**。
 *
 * <p>取代原先的 {@code decision/AutoMineDecision}（78 行孤岛：自己扫描、自己挑最近、
 * 自己打日志，绕开了 `CandidateSet`/`Selection`/`DecisionTrace`/终止语义这一整套）。
 * 现在它只回答"有哪些候选、哪些被拒为什么"，**挑选交给 {@code SelectionPolicy}**——
 * 于是挖掘与伐木共用同一条决策缝（换策略即可换行为，LLM 接入点也就只有一处）。
 *
 * <p>沿用既有命令的两种目标写法（口径不变）：**标签**（如 {@code minecraft:coal_ores}）
 * 或 **方块 ID**（如 {@code minecraft:stone}——stone 没有同名标签，必须走方块模式）。
 */
public final class MineCandidateSource implements CandidateSource {

    /** 扫描半径（沿用 `AutoMineDecision.SCAN_RADIUS` 的取值，一次命令触发、ms 级）。 */
    public static final int SCAN_RADIUS = 24;

    /** 目标：标签 或 单个方块（二者互斥）。 */
    public record Target(TagKey<Block> tag, Block block) {

        public static Target ofTag(TagKey<Block> tag) {
            return new Target(java.util.Objects.requireNonNull(tag, "tag"), null);
        }

        public static Target ofBlock(Block block) {
            return new Target(null, java.util.Objects.requireNonNull(block, "block"));
        }

        /** 把命令里的字符串解析成目标：先按标签，无同名标签再按方块 ID；都不行返回 null。 */
        public static Target parse(ServerLevel level, ResourceLocation id) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
            boolean tagExists = level.registryAccess().registryOrThrow(Registries.BLOCK)
                    .getTag(tag).isPresent();
            if (tagExists) {
                return ofTag(tag);
            }
            Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id);
            if (block == null || block == Blocks.AIR) {
                return null;
            }
            return ofBlock(block);
        }

        public boolean matches(BlockState state) {
            return tag != null ? state.is(tag) : state.getBlock() == block;
        }

        public String describe() {
            if (tag != null) {
                return "#" + tag.location();
            }
            return String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block));
        }
    }

    private final Target target;
    private final int radius;
    /**
     * 本来源的总考察格数预算（默认 {@link #CELL_BUDGET_TOTAL}）。
     *
     * <p>**夹具专用旋钮**（同 `MineJob` 的 `identityCheckOverride` 先例）：把预算压到很小，
     * 就能**确定性**地触发 `S3` 那条分支（"搜索受限"而不是"没矿"），而不必造一个真有 24 万格的世界。
     */
    private final int cellBudgetTotal;

    public MineCandidateSource(Target target) {
        this(target, SCAN_RADIUS);
    }

    public MineCandidateSource(Target target, int radius) {
        this(target, radius, CELL_BUDGET_TOTAL);
    }

    public MineCandidateSource(Target target, int radius, int cellBudgetTotal) {
        this.target = java.util.Objects.requireNonNull(target, "target");
        this.radius = Math.max(1, radius);
        this.cellBudgetTotal = Math.max(1, cellBudgetTotal);
    }

    /** 本来源的总预算（判据用：夹具要断言"预算被真的用上"，而不是断言常量）。 */
    public int cellBudgetTotal() {
        return cellBudgetTotal;
    }

    @Override
    public String name() {
        return "mine:" + target.describe();
    }

    public Target target() {
        return target;
    }

    /** 该格现在仍是本 Job 的目标方块吗——执行期身份复检用（§6.2c⑤，与伐木同一条纪律）。 */
    public boolean matchesTarget(ServerLevel level, BlockPos pos) {
        return target.matches(level.getBlockState(pos));
    }

    /**
     * **一次扫描 · 多目标**（队列第②项，2026-09-17）：合并"每个目标扫一遍"的冗余。
     *
     * <p>现状问题：`CandidateMenu` 对 11 个目标各 `new MineCandidateSource(...).candidates(...)` 一次，
     * 每次都是**一整遍三重循环**（(2r+1)³ 次 `getBlockState`）⇒ 11 × 4913 ≈ 5.4 万次读取/建菜单
     * （勘测 11 实测"菜单 30~90ms"的来源之一）。
     * 合并后：**一遍**扫描，命中的方块分发到各自目标的候选集 —— **结果集逐字不变**（只是不再重复扫世界）。
     *
     * @param blockReads 本次扫描实际发生的 `getBlockState` 次数（判据用：必须 ≈ (2r+1)³，不是它的 N 倍）
     */
    public record MultiScan(List<CandidateSet> sets, int blockReads, int unscanned) {
    }

    /** **诊断计数**（队列第②项判据）：自上次 `resetBlockReads()` 起，扫描真实发生的 `getBlockState` 次数。
     *  放在底层是为了**无论走单目标还是多目标路径都记真值** —— 判据才不会被我自己的实现骗过去。 */
    private static final java.util.concurrent.atomic.AtomicLong BLOCK_READS =
            new java.util.concurrent.atomic.AtomicLong();

    public static void resetBlockReads() {
        BLOCK_READS.set(0L);
    }

    public static long blockReads() {
        return BLOCK_READS.get();
    }

    /**
     * ⭐ **未加载而跳过**的格数（`D-329` ① 用户裁定：**只扫已加载**；未加载记"未扫"，**不许冒充"没矿"**）。
     *
     * <p><b>为什么必须有它（`D-331` 同类缺陷）</b>：`Level.getBlockState` 对未加载区块会**同步加载**
     * （`getChunkAt`）⇒ 扫描一旦越出加载半径，就等于在服务端 tick 线程上替内核"偷偷加载"那些区块
     * （几百格外 = 真地形生成 + 磁盘 I/O）。守卫之后**不读方块**，而是把它记成"未扫"。
     * <p><b>判据不变式</b>：`blockReads + unscanned == 扫描体积` —— 夹具断言这一条（读数少了必须是被跳过的，不是漏扫）。
     */
    private static final java.util.concurrent.atomic.AtomicLong UNSCANNED =
            new java.util.concurrent.atomic.AtomicLong();

    public static void resetUnscanned() {
        UNSCANNED.set(0L);
    }

    public static long unscanned() {
        return UNSCANNED.get();
    }

    // ==================== 扫描会话（`D-329` §2：S4 分片 + S3 预算） ====================

    /**
     * **单次 {@code advance()} 的考察格数上限**（`S4`：单 tick 不许做 `(2r+1)³` 全量 ——
     * r=24 时那是 **117,649** 次考察，全压在服务端 tick 线程上）。
     *
     * <p>取值依据：一个区块 16×16 = 256 格 ⇒ 8192 格 ≈ 32 个区块的整列；
     * 未加载的格连 `getBlockState` 都不读（`hasChunkAt` 守卫），所以这是"最坏情况"的上界。
     */
    public static final int CELL_BUDGET_PER_TICK = 8192;

    /**
     * **一次扫描的总考察格数上限**（`S3`：`SEARCH_LIMIT ≠ UNREACHABLE`）。
     *
     * <p>用尽且**没扫完** ⇒ 只能报"搜索受限"（`MineJob` 的 `search_incomplete`），
     * **不许**被当成"这里没矿"（那会把"没看见"说成"没有"），**更不许**据此授权任何"那就挖过去"
     * 的写入 —— `D-076` 写死了：搜索预算从来不是写入授权。
     *
     * <p>默认值 > r=24 的整卷（117,649）⇒ **默认生产路径永不截断**；它存在的意义是
     * ① 大范围/低预算调用方的**显式契约**，② 夹具能**确定性**触发那条分支（构造小预算会话即可）。
     */
    public static final int CELL_BUDGET_TOTAL = 240_000;

    /**
     * 一次 `advance()` 的结果。
     *
     * @param visitedThisCall **本次**考察的格数（判据：单次 ≤ 上限）
     * @param reads      **累计**真正 `getBlockState` 的次数（与旧 `MultiScan.blockReads` **同口径** ⇒ 既有判据不用改）
     * @param unscanned  **累计**因未加载而跳过的格数（同 `MultiScan.unscanned` 口径）
     * @param done      整卷扫完
     * @param truncated **被总预算截断**（没扫完就不许再扫）⇒ 调用方只能如实说"搜索受限"
     */
    public record Progress(List<CandidateSet> sets, int visitedThisCall, int reads, int unscanned,
                           boolean done, boolean truncated) {
        public Progress {
            sets = List.copyOf(sets);
        }
    }

    /**
     * **可续跑的扫描会话**（`S4`）：确定性游标 + 单次调用格数上限 + 跨调用幂等合并去重。
     *
     * <p>顺序对齐 Baritone `cache/WorldScanner.java`（本文件头部已登记对照）：
     * ① `:56` Y 段按**距玩家高度**排序（这里 `0, +1, -1, +2, -2, …`；上先于下 ——
     * 顺带不制造"一直往下挖"的偏置，见 `D-329` ④）；② `:60-89` 由近及远**逐壳**推进；
     * ③ `:146-180` 以**区块**为工作单元（未加载 ⇒ 记"未扫"、一个方块都不读）。
     *
     * <p><b>不变量（夹具断言）</b>：`visited == reads + unscanned`；扫完时 `visited == (2r+1)³`；
     * 单次调用 `visited ≤ perCallBudget`；游标单调 ⇒ **同一格永不重复考察**（`produced` 是第二道保险）。
     */
    public static final class ScanSession {

        private final List<Target> targets;
        private final int scan;
        private final BlockPos center;
        private final int perCallBudget;
        private final int totalBudget;
        private final List<List<Candidate>> viable = new ArrayList<>();
        private final List<List<String>> rejected = new ArrayList<>();
        private final List<java.util.Set<Long>> produced = new ArrayList<>();
        private final int[] considered;

        private int visited;
        private int reads;
        private int unscanned;
        private int dyIndex;
        private int shell;
        private int ring;
        private boolean done;
        private boolean truncated;
        /** 诊断（判据用）：历次 `advance()` 里**单次考察格数**的最大值。 */
        private int maxCallVisited;

        public ScanSession(List<Target> targets, int sourceRadius, int specRadius, BlockPos center) {
            this(targets, sourceRadius, specRadius, center, CELL_BUDGET_PER_TICK, CELL_BUDGET_TOTAL);
        }

        public ScanSession(List<Target> targets, int sourceRadius, int specRadius, BlockPos center,
                           int perCallBudget, int totalBudget) {
            this.targets = List.copyOf(targets);
            // 扫描范围口径与既有实现**逐字相同**：来源半径与 GoalSpec 半径取小
            this.scan = Math.min(Math.max(1, sourceRadius), Math.max(1, specRadius));
            this.center = center.immutable();
            this.perCallBudget = Math.max(1, perCallBudget);
            this.totalBudget = Math.max(1, totalBudget);
            this.considered = new int[this.targets.size()];
            for (int i = 0; i < this.targets.size(); i++) {
                viable.add(new ArrayList<>());
                rejected.add(new ArrayList<>());
                produced.add(new java.util.HashSet<>());
            }
        }

        /** 整卷格数 `(2r+1)³`（扫完时 `visited` 必须等于它）。 */
        public long volume() {
            long side = 2L * scan + 1;
            return side * side * side;
        }

        public int radius() {
            return scan;
        }

        public boolean done() {
            return done;
        }

        /** 被总预算截断（= "搜索受限"，不是"没矿"）。 */
        public boolean truncated() {
            return truncated;
        }

        public int visited() {
            return visited;
        }

        public int reads() {
            return reads;
        }

        public int unscanned() {
            return unscanned;
        }

        public int maxCallVisited() {
            return maxCallVisited;
        }

        /** 当前已累积的候选集（`sets` 顺序与传入 `targets` 一致）。 */
        public List<CandidateSet> sets() {
            List<CandidateSet> out = new ArrayList<>(targets.size());
            for (int i = 0; i < targets.size(); i++) {
                out.add(new CandidateSet(viable.get(i), rejected.get(i)));
            }
            return out;
        }

        /**
         * 推进一段（最多 `perCallBudget` 格、且不超过总预算），返回累积结果的快照。
         *
         * <p>**幂等**：同一格只考察一次；重复调用只会把 `visited` 往前推，不会重复入集。
         */
        public Progress advance(ServerPlayer bot) {
            ServerLevel level = (ServerLevel) bot.level();
            var safeZones = SafeZoneData.get(level.getServer());
            int budget = (int) Math.min(perCallBudget, Math.max(0, totalBudget - visited));
            int did = 0;
            while (did < budget && !done) {
                if (dyIndex > 2 * scan) {
                    // 整卷扫完：**"没找到"只在这里、且只对"考察过 0 个目标方块"的目标**下结论
                    finish();
                    break;
                }
                int packed = ringPacked(shell, ring);
                int dy = dyAt(dyIndex);
                BlockPos pos = center.offset(dxOf(packed), dy, dzOf(packed));
                visit(level, bot, safeZones, pos);
                did++;
                ring++;
                if (ring >= ringCount(shell)) {
                    ring = 0;
                    shell++;
                    if (shell > scan) {
                        shell = 0;
                        dyIndex++;
                    }
                }
            }
            if (!done && visited >= totalBudget) {
                truncated = true;      // S3：只能"搜索受限" —— 不许被读成"没矿"，更不许授权挖过去
            }
            if (did > maxCallVisited) {
                maxCallVisited = did;
            }
            return new Progress(sets(), did, reads, unscanned, done, truncated);
        }

        /** 扫完的收尾：**只**给"一个目标方块都没考察到"的目标加 `not_found`（未加载的格不算"没找到"）。 */
        private void finish() {
            done = true;
            for (int i = 0; i < targets.size(); i++) {
                if (considered[i] == 0) {
                    rejected.get(i).add("scan(radius=" + scan + " @" + center.toShortString() + "):not_found");
                }
            }
        }

        private void visit(ServerLevel level, ServerPlayer bot, SafeZoneData safeZones, BlockPos pos) {
            visited++;
            // ⭐ D-329 ① / D-331 同类：**未加载的格一律不读**（`getBlockState` 会同步加载区块）。
            // 只记"未扫"，绝不冒充"没矿"；调用方据此把该区域视为**尚未感知**（等靠近/记忆累积）。
            if (!level.hasChunkAt(pos)) {
                unscanned++;
                UNSCANNED.incrementAndGet();
                return;
            }
            BlockState state = level.getBlockState(pos);
            reads++;
            BLOCK_READS.incrementAndGet();
            for (int i = 0; i < targets.size(); i++) {
                if (!targets.get(i).matches(state)) {
                    continue;   // 不匹配的方块不是"被拒候选"，只是背景——不刷理由码
                }
                considered[i]++;
                String reason = viabilityRefusal(level, bot, safeZones, pos);
                if (reason != null) {
                    rejected.get(i).add(id(pos) + ":" + reason);
                    continue;
                }
                // 去重（第二道保险）：游标保证每格只来一次，但会话若被复用/游标若被回退，这里挡住重复入集
                if (produced.get(i).add(pos.asLong())) {
                    viable.get(i).add(new Candidate(pos, "block", features(bot, pos, state)));
                }
            }
        }

        /**
         * **候选的当前可行性**（`null` = 可做，否则返回拒绝理由码）—— 扫描与**决策前复检**共用同一处。
         *
         * <p>⭐ 为什么必须能被**重复**调用（这次改动的真教训）：候选**位置**是"扫描那一刻"的快照，
         * 但**可破坏性/授权是当时的世界事实** —— 预算、权限、方块本身都可能已经变了。
         * 旧版每次选择都**重扫一遍世界**，所以它天然是"当前"的；改成"扫一次、跨 tick 累积"之后，
         * 这份快照会**过期**（实测 `mine_budget`：预算被压到 0，第一格用尽预算后，剩下 5 格在旧版里
         * 当场变成 `:unbreakable` ⇒ 归因 `write_budget_exhausted`；不重验的快照则让它们继续"可选"，
         * 最后 5 格报运行期 `TARGET_NOT_BREAKABLE` ⇒ 归因退化成总括码 ⇒ **判据红**）。
         *
         * <p>⚠️ 这是 `D-348` 同一条纪律的另一面：**别把代理判据当世界事实**。
         */
        private static String viabilityRefusal(ServerLevel level, ServerPlayer bot,
                                               SafeZoneData safeZones, BlockPos pos) {
            // ⭐ 保护区这一层走**区域级授权面**（`D-338` 附注七③：与破坏/放置闸门**同一个函数**）。
            // 挖矿是 `L0`（`D-338` ④"挖矿 = 野外采集，不发生在保护区内"）⇒ 认领区块里的矿
            // **照旧被拒**（没有任务区时拒绝码逐字仍是 `protected_area`）。
            String reason = com.dddgn.alice.protection.ZoneAuthority.candidateRefusal(level,
                    bot.getUUID(), pos, safeZones.protectionReason(level, pos),
                    WriteReason.EXPECTED_TARGET);
            if (reason != null) {
                return reason;
            }
            if (!BlockInteraction.breakable(bot, level, pos,
                    WriteGrant.of("mine-plan", WriteReason.EXPECTED_TARGET))) {
                return "unbreakable";
            }
            return null;
        }

        /**
         * **决策前复检**：用**当前**世界重算"这些候选现在还能不能做"，返回一份**派生**候选集。
         *
         * <p>不改会话自己的累积状态（世界可能又变回去，例如预算被重新配置）—— 与旧版"每次 `select` 重扫"
         * **语义等价**，只是不重复扫方块（扫方块才是分片要省掉的那部分开销）。
         */
        public CandidateSet revalidate(ServerPlayer bot, int targetIndex) {
            ServerLevel level = (ServerLevel) bot.level();
            var safeZones = SafeZoneData.get(level.getServer());
            List<Candidate> stillViable = new ArrayList<>();
            List<String> rejectedNow = new ArrayList<>(rejected.get(targetIndex));
            for (Candidate candidate : viable.get(targetIndex)) {
                String refusal = viabilityRefusal(level, bot, safeZones, candidate.anchor());
                if (refusal == null) {
                    stillViable.add(candidate);
                } else {
                    rejectedNow.add(id(candidate.anchor()) + ":" + refusal);
                }
            }
            return new CandidateSet(stillViable, rejectedNow);
        }

        /** 单目标会话的复检（`MineJob` 用）。 */
        public CandidateSet revalidate(ServerPlayer bot) {
            return revalidate(bot, 0);
        }

        /**
         * Y 偏移的**确定性顺序**：`0, +1, -1, +2, -2, …`（先同层，再上一层，再下一层）。
         *
         * <p>对齐 Baritone `WorldScanner.java:56`（Y 段按距玩家高度排序 ⇒ `:149` 先扫与玩家同层的段）；
         * ⚠️ Alice 有意取"**上先于下**"：谁先被扫到谁先成为候选，而 `D-329` ④ 认定当前有"向下偏置"，
         * 所以这里不再给"向下"任何先手（这是**顺序**层面的对齐，不是把偏置改成反向偏置）。
         */
        static int dyAt(int index) {
            if (index <= 0) {
                return 0;
            }
            int k = (index + 1) / 2;
            return (index % 2 == 1) ? k : -k;
        }

        /** 水平切面上**第 `s` 层方环**的格数：`s=0 ⇒ 1`，否则 `8s`。 */
        static int ringCount(int s) {
            return s <= 0 ? 1 : 8 * s;
        }

        /**
         * 方环上第 `idx` 个位置，打包成 `(dx & 0xff) | ((dz & 0xff) << 8)`（避免每格一次数组分配）。
         *
         * <p>环序固定：起点 `(-s,-s)` → 沿 `+x` → 沿 `+z` → 沿 `-x` → 沿 `-z`（不回起点）。
         */
        static int ringPacked(int s, int idx) {
            if (s <= 0) {
                return 0;
            }
            int side = 2 * s;
            int dx;
            int dz;
            if (idx <= 0) {
                dx = -s;
                dz = -s;
            } else {
                int i = idx - 1;
                if (i < side) {
                    dx = -s + i + 1;
                    dz = -s;
                } else if ((i -= side) < side) {
                    dx = s;
                    dz = -s + i + 1;
                } else if ((i -= side) < side) {
                    dx = s - i - 1;
                    dz = s;
                } else {
                    i -= side;
                    dx = -s;
                    dz = s - i - 1;
                }
            }
            return (dx & 0xff) | ((dz & 0xff) << 8);
        }

        static int dxOf(int packed) {
            int v = packed & 0xff;
            return v > 127 ? v - 256 : v;
        }

        static int dzOf(int packed) {
            int v = (packed >>> 8) & 0xff;
            return v > 127 ? v - 256 : v;
        }
    }

    /** 一次扫描多目标（菜单/单目标共用）：**一次跑完整卷**（配额与旧行为逐字一致）。 */
    public static MultiScan candidatesForTargets(ServerPlayer bot, GoalSpec spec,
                                                 List<Target> targets, int sourceRadius) {
        ScanSession session = new ScanSession(targets, sourceRadius, Math.max(1, spec.radius()),
                spec.center(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        Progress p = session.advance(bot);
        return new MultiScan(p.sets(), p.reads(), p.unscanned());
    }

    /** 单目标路径（Job 用）：与多目标共用同一份扫描逻辑。 */
    @Override
    public CandidateSet candidates(ServerPlayer bot, GoalSpec spec) {
        MultiScan res = candidatesForTargets(bot, spec, List.of(target), radius);
        return res.sets().get(0);
    }

    /** ⭐ `S4`：**分片扫描会话**（`MineJob` 用它，逐 tick 推进、不再一 tick 全量）。 */
    public ScanSession newSession(GoalSpec spec) {
        return new ScanSession(List.of(target), radius, Math.max(1, spec.radius()), spec.center(),
                CELL_BUDGET_PER_TICK, cellBudgetTotal);
    }

    /**
     * ⭐ **夹具专用**：显式指定「单次上限 / 总预算」的分片会话。
     *
     * <p>判据必须能**确定性**地压小预算 —— 否则 `S3`（截断）、`S4`（分片）两条分支要么跑不到，
     * 要么得造一个有 24 万格的世界才能跑到（那就不叫判据，叫碰运气）。
     */
    public ScanSession newSession(GoalSpec spec, int perCallBudget, int totalBudget) {
        return new ScanSession(List.of(target), radius, Math.max(1, spec.radius()), spec.center(),
                perCallBudget, totalBudget);
    }
    private static Map<String, String> features(ServerPlayer bot, BlockPos pos, BlockState state) {
        Map<String, String> features = new LinkedHashMap<>();
        features.put("d", String.format(java.util.Locale.ROOT, "%.1f",
                Math.sqrt(bot.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))));
        features.put("block", String.valueOf(
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock())));
        features.put("y", Integer.toString(pos.getY()));
        return features;
    }

    private static String id(BlockPos pos) {
        return "block@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
