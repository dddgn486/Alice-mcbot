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

    @Override
    public CandidateSet candidates(ServerPlayer bot, GoalSpec spec) {
        ServerLevel level = (ServerLevel) bot.level();
        // 扫描范围取"来源半径"与"目标规格半径"的较小者：Job 的 GoalSpec 是权威约束
        int scan = Math.min(radius, Math.max(1, spec.radius()));
        BlockPos center = spec.center();
        var safeZones = SafeZoneData.get(level.getServer());

        List<Candidate> viable = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int considered = 0;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dy = -scan; dy <= scan; dy++) {
                for (int dz = -scan; dz <= scan; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!target.matches(state)) {
                        continue;   // 不匹配的方块不是"被拒候选"，只是背景——不刷理由码
                    }
                    considered++;
                    String reason = safeZones.protectionReason(level, pos);
                    if (reason != null) {
                        rejected.add(id(pos) + ":" + reason);
                        continue;
                    }
                    // 破坏判定按**声明的理由**派生策略（D-082）：挖掘目标 = EXPECTED_TARGET。
                    // 注意 `breakableExplicit` 已在 D-082 删除——策略不再由"调哪个方法"隐式决定。
                    if (!BlockInteraction.breakable(bot, level, pos,
                            WriteGrant.of("mine-plan", WriteReason.EXPECTED_TARGET))) {
                        rejected.add(id(pos) + ":unbreakable");
                        continue;
                    }
                    viable.add(new Candidate(pos, "block", features(bot, pos, state)));
                }
            }
        }
        if (considered == 0) {
            rejected.add("scan(radius=" + scan + " @" + center.toShortString() + "):not_found");
        }
        return new CandidateSet(viable, rejected);
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
