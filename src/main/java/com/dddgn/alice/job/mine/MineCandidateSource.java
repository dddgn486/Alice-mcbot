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

    public MineCandidateSource(Target target) {
        this(target, SCAN_RADIUS);
    }

    public MineCandidateSource(Target target, int radius) {
        this.target = java.util.Objects.requireNonNull(target, "target");
        this.radius = Math.max(1, radius);
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

    /** 一次扫描多目标：`sets` 顺序与传入 `targets` 一致。 */
    public static MultiScan candidatesForTargets(ServerPlayer bot, GoalSpec spec,
                                                 List<Target> targets, int sourceRadius) {
        ServerLevel level = (ServerLevel) bot.level();
        // 扫描范围口径与单目标路径**逐字相同**：来源半径与 GoalSpec 半径取小
        int scan = Math.min(sourceRadius, Math.max(1, spec.radius()));
        BlockPos center = spec.center();
        var safeZones = SafeZoneData.get(level.getServer());
        List<List<Candidate>> viable = new ArrayList<>();
        List<List<String>> rejected = new ArrayList<>();
        int[] considered = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            viable.add(new ArrayList<>());
            rejected.add(new ArrayList<>());
        }
        int reads = 0;
        int unscanned = 0;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dy = -scan; dy <= scan; dy++) {
                for (int dz = -scan; dz <= scan; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    // ⭐ D-329 ① / D-331 同类：**未加载的格一律不读**（`getBlockState` 会同步加载区块）。
                    // 只记"未扫"，绝不冒充"没矿"；调用方据此把该区域视为**尚未感知**（等靠近/记忆累积）。
                    if (!level.hasChunkAt(pos)) {
                        unscanned++;
                        UNSCANNED.incrementAndGet();
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    reads++;
                    BLOCK_READS.incrementAndGet();
                    for (int i = 0; i < targets.size(); i++) {
                        if (!targets.get(i).matches(state)) {
                            continue;   // 不匹配的方块不是"被拒候选"，只是背景——不刷理由码
                        }
                        considered[i]++;
                        // ⭐ 保护区这一层走**区域级授权面**（`D-338` 附注七③：与破坏/放置闸门**同一个函数**）。
                        // 挖矿是 `L0`（`D-338` ④"挖矿 = 野外采集，不发生在保护区内"）⇒ 认领区块里的矿
                        // **照旧被拒**（没有任务区时拒绝码逐字仍是 `protected_area`）。
                        String reason = com.dddgn.alice.protection.ZoneAuthority.candidateRefusal(level,
                                bot.getUUID(), pos, safeZones.protectionReason(level, pos),
                                WriteReason.EXPECTED_TARGET);
                        if (reason != null) {
                            rejected.get(i).add(id(pos) + ":" + reason);
                            continue;
                        }
                        if (!BlockInteraction.breakable(bot, level, pos,
                                WriteGrant.of("mine-plan", WriteReason.EXPECTED_TARGET))) {
                            rejected.get(i).add(id(pos) + ":unbreakable");
                            continue;
                        }
                        viable.get(i).add(new Candidate(pos, "block", features(bot, pos, state)));
                    }
                }
            }
        }
        List<CandidateSet> sets = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            if (considered[i] == 0) {
                rejected.get(i).add("scan(radius=" + scan + " @" + center.toShortString() + "):not_found");
            }
            sets.add(new CandidateSet(viable.get(i), rejected.get(i)));
        }
        return new MultiScan(sets, reads, unscanned);
    }

    /** 单目标路径（Job 用）：与多目标共用同一份扫描逻辑。 */
    @Override
    public CandidateSet candidates(ServerPlayer bot, GoalSpec spec) {
        MultiScan res = candidatesForTargets(bot, spec, List.of(target), radius);
        return res.sets().get(0);
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
