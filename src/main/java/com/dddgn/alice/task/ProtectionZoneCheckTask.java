package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.compat.ftbchunks.FtbChunksClaims;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.network.ProtectionActionPacket;
import com.dddgn.alice.network.ProtectionClaimsPacket;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.protection.ClaimSources;
import com.dddgn.alice.protection.ProtectionClaimService;
import com.dddgn.alice.protection.ProtectionMapGeometry;
import com.dddgn.alice.protection.SafeZoneData;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **保护区自检**（D-313，2026-09-18）—— 电池步 {@code protection_zones}。
 *
 * <p>它证明什么：保护区从"水平圆形半径"改成 ⭐ **区块级 2D 认领**（忽略 Y、覆盖全高度）之后，
 * 服务端真相仍然是**一句话**：<b>认领的区块 = 不许动；没认领 = 放行</b>，且**旧数据不静默丢**。
 *
 * <p>四组判据（都在**纯查询**上做 —— 本夹具**不建地形、不写方块、不传送**，因此不可能污染别的步）：
 * <ol>
 *   <li>**区块级 + 全高度**：认领 bot 所在区块后，同一区块 `y=minBuildHeight` 与 `y=maxBuildHeight-1`
 *       都返回 {@code protected_area}（Y 无关的证据），相邻区块**不在**认领集合里（认领有边界）；</li>
 *   <li>**取消即时生效**：取消后同一位置立刻放行；</li>
 *   <li>**单一安全入口**：{@link BlockBreakSafety} 的两条策略（明确目标 / 清障）都能看到这条拒绝
 *       （保护区是**独立闸门**，不是 `WritePolicyMatrix` 的第三层）；</li>
 *   <li>**存/读往返 + 旧格式迁移**：`save → load` 后认领与黑名单逐字回来；旧格式
 *       {@code areas=[{dimension,x,y,z,radius}]} 加载时自动换算成区块集合、**计数可查**（不静默丢），
 *       换算规则 = **与该圆相交即认领**（期望值在夹具里**独立算**，不复用生产实现的函数）。</li>
 * </ol>
 *
 * <p><b>2/2（勾选界面，D-314）再加两组</b>（同样零世界写入 ⇒ 放进无头电池，每轮都跑）：
 * <ol start="5">
 *   <li>**协议契约**：C2S 动作包 / S2C 快照的编解码往返；动作包**没有维度字段**（维度由服务端裁定）；
 *       超量批包**拒收**、S2C 数量撒谎**夹住**；批量应用真的落库 / 幂等 / 不碰别的维度 / 越界不落库；
 *       快照按「离玩家最近」截断且 `truncated` 如实置位；</li>
 *   <li>**界面几何**：网格边长恒为**正奇数**、格 ⇄ 区块**可逆**（17×17 恰好 289 个互不相同的区块）、
 *       中心格 = 玩家所在区块、网格外点击返回 {@code null}。这一组专门拦「点左边认领了右边」这类
 *       bug —— 它们本来要花一个客户端轮次才能发现（见 {@link ProtectionMapGeometry}）。</li>
 * </ol>
 *
 * <p>⚠️ **自清理是判据的一部分**：认领/黑名单都会**持久化**（`SavedData`）⇒ 夹具结束时必须回到进入前的状态
 * 并**断言**它（否则会毒化后续步骤的挖掘/伐木判据，那种红最难查）。失败路径同样走收尾。
 *
 * <p>⚠️ 为什么用 `bot` 所在区块做"位置级"断言：那里**一定已加载**（区块票据）⇒ 不会因为
 * "区块未加载"顺手把世界生成出去，也不会读到虚空。Y 方向的断言用同一区块的极值高度（认领命中在
 * 读方块之前返回 ⇒ 不需要那两处已加载）。
 */
public final class ProtectionZoneCheckTask implements Task {

    /** 单次加载的自定义 `SavedData` 上做往返，不需要世界 tick；留一点余量给日志。 */
    private static final int BUDGET_TICKS = 150;

    private enum Phase { ZONE, RELEASE, PERSIST, MIGRATE, PROTOCOL, EXTERNAL, GEOMETRY, BLACKLIST, CLEANUP, DONE }

    private final BotPlayer bot;
    private final net.minecraft.server.level.ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private Phase phase = Phase.ZONE;
    private int ticks;
    private int checks;
    private boolean done;

    // 进入前的现场（用于精确复原 + 断言复原成功）
    private ResourceLocation dimension;
    private int hereChunkX;
    private int hereChunkZ;
    private boolean hereWasClaimed;
    private int chunksBefore;
    private ResourceLocation supportBlockId;
    private TagKey<Block> supportTag;
    private int probeChunkX = Integer.MIN_VALUE;
    private boolean addedBlockRule;
    private boolean addedTagRule;
    /** 本夹具注册的假外部认领源（收尾必须摘掉：它会**影响后续步骤**的判据）。 */
    private ClaimSources.Source fakeSource;

    public ProtectionZoneCheckTask(BotPlayer bot, net.minecraft.server.level.ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "ProtectionZoneCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("自检必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        switch (phase) {
            case ZONE -> zonePhase();
            case RELEASE -> releasePhase();
            case PERSIST -> persistPhase();
            case MIGRATE -> migratePhase();
            case PROTOCOL -> protocolPhase();
            case EXTERNAL -> externalPhase();
            case GEOMETRY -> geometryPhase();
            case BLACKLIST -> blacklistPhase();
            case CLEANUP -> finish();
            default -> {
                return finish();
            }
        }
        return Task.Status.RUNNING;
    }

    // ==================== 各相位 ====================

    /** 认领 bot 所在区块 ⇒ 该区块全高度拒绝、相邻区块不受影响、单一安全入口看得到。 */
    private void zonePhase() {
        ServerLevel level = bot.serverLevel();
        SafeZoneData data = SafeZoneData.get(level.getServer());
        BlockPos here = bot.blockPosition();
        dimension = level.dimension().location();
        hereChunkX = here.getX() >> 4;
        hereChunkZ = here.getZ() >> 4;
        long hereKey = ChunkPos.asLong(hereChunkX, hereChunkZ);
        long neighborKey = ChunkPos.asLong(hereChunkX + 1, hereChunkZ);
        chunksBefore = data.claimedChunkCount();
        hereWasClaimed = data.claims(dimension).contains(hereKey);

        // 前提自证：位置级断言必须站在**已加载**区块里，否则判据可能读到虚空/顺手生成世界
        check("前提：bot 所在区块已加载（位置级断言的前提；实际 hasChunkAt=" + level.hasChunkAt(here) + "）",
                level.hasChunkAt(here));
        // 前提自证：黑名单判据稍后要用"当前没有任何规则命中"，否则会被 protected_area 遮住
        check("前提：起点在认领之前**没有**任何保护命中（实际 "
                        + desc(data.protectionReason(level, here)) + "）",
                data.protectionReason(level, here) == null);

        if (hereWasClaimed) {   // 极少数情况：别的用例已经认领过 ⇒ 先取消，收尾再恢复
            data.unclaim(level, hereChunkX, hereChunkZ);
        }
        check("认领 bot 所在区块必须成功（chunk " + hereChunkX + ", " + hereChunkZ + "）",
                data.claim(level, hereChunkX, hereChunkZ));
        check("认领后计数 +1（" + chunksBefore + " → " + data.claimedChunkCount() + "）",
                data.claimedChunkCount() == chunksBefore + 1);

        BlockPos low = new BlockPos(here.getX(), level.getMinBuildHeight(), here.getZ());
        BlockPos high = new BlockPos(here.getX(), level.getMaxBuildHeight() - 1, here.getZ());
        check("认领后该位置被判 protected_area（实际 " + desc(data.protectionReason(level, here)) + "）",
                "protected_area".equals(data.protectionReason(level, here)));
        check("**Y 无关**：同一区块最低处也判 protected_area（y=" + level.getMinBuildHeight() + "，实际 "
                        + desc(data.protectionReason(level, low)) + "）",
                "protected_area".equals(data.protectionReason(level, low)));
        check("**Y 无关**：同一区块最高处也判 protected_area（y=" + (level.getMaxBuildHeight() - 1) + "，实际 "
                        + desc(data.protectionReason(level, high)) + "）",
                "protected_area".equals(data.protectionReason(level, high)));
        check("认领有边界：相邻区块**不在**认领集合里（chunk " + (hereChunkX + 1) + ", " + hereChunkZ + "）",
                !data.claims(dimension).contains(neighborKey));
        check("两个位置都落在已认领区块 ⇒ sharesArea=true（保守移动边界口径仍成立）",
                data.sharesArea(level, here, here.above()));

        // 单一安全入口（保护区 = 独立闸门；两条策略都要看得到）
        check("BlockBreakSafety 明确目标策略必须拒绝（实际 "
                        + desc(BlockBreakSafety.explicitTargetRefusal(bot, here)) + "）",
                "protected_area".equals(BlockBreakSafety.explicitTargetRefusal(bot, here)));
        check("BlockBreakSafety 清障策略也必须拒绝（清障更保守，实际 "
                        + desc(BlockBreakSafety.refusal(bot, here, WriteReason.PATH_ACCESS)) + "）",
                "protected_area".equals(BlockBreakSafety.refusal(bot, here, WriteReason.PATH_ACCESS)));
        BotLog.info("[Protection] 已认领区块 {} , {}（忽略 Y ⇒ 全高度）⇒ {}",
                hereChunkX, hereChunkZ, data.summary());
        advance(Phase.RELEASE);
    }

    /** 取消认领 ⇒ 同一位置立刻放行（"取消即时生效"）。 */
    private void releasePhase() {
        ServerLevel level = bot.serverLevel();
        SafeZoneData data = SafeZoneData.get(level.getServer());
        BlockPos here = bot.blockPosition();
        long hereKey = ChunkPos.asLong(hereChunkX, hereChunkZ);

        check("取消认领必须成功（chunk " + hereChunkX + ", " + hereChunkZ + "）",
                data.unclaim(level, hereChunkX, hereChunkZ));
        check("取消后认领集合里不再有这个区块", !data.claims(dimension).contains(hereKey));
        check("取消后同一位置立刻放行（实际 " + desc(data.protectionReason(level, here)) + "）",
                data.protectionReason(level, here) == null);
        check("取消后计数回到进入前的值（" + data.claimedChunkCount() + " = " + chunksBefore + "）",
                data.claimedChunkCount() == chunksBefore);
        BotLog.info("[Protection] 取消认领后放行 ✓（同一位置，无需重载）");
        advance(Phase.PERSIST);
    }

    /** 持久化契约：`save → load` 后认领与黑名单逐字回来（格式版本 2）。 */
    private void persistPhase() {
        ServerLevel level = bot.serverLevel();
        SafeZoneData data = SafeZoneData.get(level.getServer());
        BlockPos here = bot.blockPosition();
        BlockPos support = supportPos();
        ResourceLocation supportId = blockId(level, support);

        // ⚠️ 往返用的认领区块要**避开** bot 所在区块：支撑方块就在那个区块里，
        // 而 `protectionReason` 先判区域（`protected_area`）⇒ 会把方块规则的判据遮住（本夹具首轮就踩了）
        int probeChunkX = hereChunkX + 8;
        data.claim(level, probeChunkX, hereChunkZ);
        boolean blockAdded = supportId != null && data.addBlock(supportId);
        addedBlockRule = blockAdded;
        CompoundTag saved = data.save(new CompoundTag());
        SafeZoneData reloaded = SafeZoneData.load(saved);

        check("存/读往返：认领的区块条数一致（写 " + data.claimedChunkCount() + " / 读 "
                        + reloaded.claimedChunkCount() + "）",
                reloaded.claimedChunkCount() == data.claimedChunkCount());
        check("存/读往返：该区块读回后**仍然拒绝**（位置全高度口径；实际 "
                        + desc(reloaded.protectionReason(level, new BlockPos((probeChunkX << 4) + 1, 64,
                        (hereChunkZ << 4) + 1))) + "）",
                "protected_area".equals(reloaded.protectionReason(level,
                        new BlockPos((probeChunkX << 4) + 1, 64, (hereChunkZ << 4) + 1))));
        check("存/读往返：黑名单（方块规则）也逐字回来（support=" + desc(supportId) + "，实际 "
                        + desc(reloaded.protectionReason(level, support)) + "）",
                blockAdded && "protected_block".equals(reloaded.protectionReason(level, support)));
        check("新格式**不触发**迁移（migrated=" + reloaded.migratedLegacyAreas()
                        + " dropped=" + reloaded.droppedLegacyAreas() + "）",
                reloaded.migratedLegacyAreas() == 0 && reloaded.droppedLegacyAreas() == 0);

        // 收尾（本相位自己造的东西自己拆）
        data.unclaim(level, probeChunkX, hereChunkZ);
        if (blockAdded) {
            data.removeBlock(supportId);
            addedBlockRule = false;
        }
        BotLog.info("[Protection] 存/读往返 ✓（新格式 version=2；黑名单随行）");
        advance(Phase.MIGRATE);
    }

    /** 旧格式（圆形半径）⇒ 区块集合：换算正确 + **计数可查**（响亮，不静默丢）。 */
    private void migratePhase() {
        // 期望值在**夹具里独立算**：圆 (0,64,0) r=8 与哪些区块相交 ⇒ 区块 x ∈ {-1,0}、z ∈ {-1,0}（4 个）
        Set<Long> expected = new LinkedHashSet<>();
        for (int cx = -1; cx <= 0; cx++) {
            for (int cz = -1; cz <= 0; cz++) {
                expected.add(ChunkPos.asLong(cx, cz));
            }
        }
        CompoundTag legacy = legacyTag("minecraft:overworld", 0, 64, 0, 8);
        SafeZoneData migrated = SafeZoneData.load(legacy);

        check("旧格式必须被迁移（migrated=" + migrated.migratedLegacyAreas() + "）",
                migrated.migratedLegacyAreas() == 1);
        check("迁移后区块集合 = 与圆相交的区块（期望 " + expected.size() + " 个，实际 "
                        + migrated.claimedChunkCount() + "：" + migrated.claimedChunkList(
                        ResourceLocation.parse("minecraft:overworld")) + "）",
                migrated.claimedChunkCount() == expected.size()
                        && migrated.claims(ResourceLocation.parse("minecraft:overworld")).containsAll(expected));
        check("迁移结果**位置级**也成立（区块 (0,0) 内的位置 ⇒ protected_area，实际 "
                        + desc(migrated.protectionReason(bot.serverLevel(), new BlockPos(0, 64, 0))) + "）",
                "protected_area".equals(migrated.protectionReason(bot.serverLevel(), new BlockPos(0, 64, 0))));
        check("迁移计数**人能看见**（summary=" + migrated.summary() + "）",
                migrated.summary().contains("migrated_legacy=1"));

        // 坏条目：不能静默丢（维度解析失败 ⇒ 计数 + 告警）
        CompoundTag bad = legacyTag("这不是一个合法的维度 id", 0, 64, 0, 8);
        SafeZoneData withBad = SafeZoneData.load(bad);
        check("坏旧条目必须**计数上报**而不是静默丢（dropped=" + withBad.droppedLegacyAreas() + "）",
                withBad.droppedLegacyAreas() == 1 && withBad.claimedChunkCount() == 0);

        BotLog.info("[Protection] 迁移 ✓：旧圆形（r=8）⇒ {} 个区块；坏条目 dropped={}",
                migrated.claimedChunkCount(), withBad.droppedLegacyAreas());
        advance(Phase.PROTOCOL);
    }

    /**
     * **协议契约**（D-314）：勾选界面的三包（S2C 快照 / C2S 批量动作 / C2S 空请求）里，
     * 凡是"客户端可以撒谎"或"维度归属"的地方都在这里被钉住。
     *
     * <p>没有客户端也能判的部分：编解码往返（netty 缓冲在本侧就能跑）、服务端权威应用、
     * 越界/超量/上限拒绝、快照截断口径。**判不了的部分**（网格画得对不对、点击手感）留给客户端轮次。
     */
    private void protocolPhase() {
        ServerLevel level = bot.serverLevel();
        SafeZoneData data = SafeZoneData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        ResourceLocation nether = ResourceLocation.parse("minecraft:the_nether");
        int probeX = hereChunkX + 8;
        int probeZ = hereChunkZ + 8;
        long probeKey = ChunkPos.asLong(probeX, probeZ);

        // ① C2S 批量动作包：编解码往返（异常算失败，不打死整轮电池）
        ProtectionActionPacket back = null;
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            ProtectionActionPacket.encode(new ProtectionActionPacket(true, new long[]{probeKey}), buffer);
            back = ProtectionActionPacket.decode(buffer);
        } catch (RuntimeException broken) {
            BotLog.warn("[Protection] 动作包往返异常: {}", broken.toString());
        }
        check("C2S 动作包编解码往返：动作 + 区块键逐字回来",
                back != null && back.claim() && back.chunkKeys().length == 1
                        && back.chunkKeys()[0] == probeKey);
        // 维度不是客户端说了算（D-307）：包里只有「动作 + 区块键」两个分量
        check("C2S 动作包**没有维度字段**（维度由服务端按发送者当前维度裁定；实际分量数="
                        + ProtectionActionPacket.class.getRecordComponents().length + "）",
                ProtectionActionPacket.class.getRecordComponents().length == 2);

        // ② 超量包必须**在解码期按数量拒收**（不是夹住，也不是「读越界顺手抛」）
        // ⚠️ 判据必须写**异常类型**：只写「有没有抛」会被"缓冲读越界"这种假象蒙过
        // —— 这一条是反向对照实测抓出来的（夹住实现同样会抛异常，只是抛的是另一种）
        RuntimeException oversizeFailure = null;
        try {
            FriendlyByteBuf hostile = new FriendlyByteBuf(Unpooled.buffer());
            hostile.writeBoolean(true);
            hostile.writeVarInt(ProtectionClaimService.MAX_BATCH + 1);
            ProtectionActionPacket.decode(hostile);
        } catch (RuntimeException expected) {
            oversizeFailure = expected;
        }
        check("超量批包（> MAX_BATCH=" + ProtectionClaimService.MAX_BATCH + "）必须**按数量拒收**（实际 "
                        + (oversizeFailure == null ? "没抛异常" : oversizeFailure.getClass().getSimpleName()) + "）",
                oversizeFailure instanceof DecoderException);

        // ③ 维度归属：同一批键交给**另一个 level** ⇒ 只落在那个维度（overworld 一根手指都不碰）
        ServerLevel netherLevel = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        int netherBefore = data.claims(nether).size();
        int overworldBefore = data.claims(dimension).size();
        if (netherLevel != null) {
            ProtectionClaimService.apply(netherLevel, true, new long[]{probeKey});
        }
        check("维度由**服务端传入的 level** 裁定（overworld 集合 " + overworldBefore + " ⇒ "
                        + data.claims(dimension).size() + "；overworld 命中="
                        + data.claims(dimension).contains(probeKey) + " / the_nether 命中="
                        + data.claims(nether).contains(probeKey) + "）",
                netherLevel != null && data.claims(nether).contains(probeKey)
                        && !data.claims(dimension).contains(probeKey)
                        && data.claims(dimension).size() == overworldBefore);
        if (netherLevel != null) {
            ProtectionClaimService.apply(netherLevel, false, new long[]{probeKey});
        }
        check("维度归属用例**自清理**：the_nether 认领数回到进入前（" + netherBefore + " = "
                        + data.claims(nether).size() + "）", data.claims(nether).size() == netherBefore);

        // ④ 服务端权威应用：真的落库 / 幂等 / 越界不落库
        ProtectionClaimService.Report first = ProtectionClaimService.apply(level, true, new long[]{probeKey});
        check("批量认领必须真的落库（" + first.summary() + "）",
                first.changed() == 1 && first.applied() == 1 && first.rejected() == 0);
        BlockPos probeLow = new BlockPos((probeX << 4) + 1, level.getMinBuildHeight(), (probeZ << 4) + 1);
        check("落库后该区块**任意 Y** 都判 protected_area（y=" + level.getMinBuildHeight() + "，实际 "
                        + desc(data.protectionReason(level, probeLow)) + "）",
                "protected_area".equals(data.protectionReason(level, probeLow)));
        ProtectionClaimService.Report again = ProtectionClaimService.apply(level, true, new long[]{probeKey});
        check("幂等：同一批再提交一次 changed=0（" + again.summary() + "）",
                again.changed() == 0 && again.applied() == 1);

        long farKey = ChunkPos.asLong(ProtectionClaimService.MAX_ABS_CHUNK + 1, 0);
        ProtectionClaimService.Report outOfRange = ProtectionClaimService.apply(level, true, new long[]{farKey});
        check("越界区块必须被拒且**不落库**（" + outOfRange.summary() + "）",
                outOfRange.rejected() == 1 && outOfRange.applied() == 0
                        && !data.claims(dimension).contains(farKey));
        check("认领上限口径（纯函数）：刚好到上限放行 / 超一个拒绝",
                !ProtectionClaimService.exceedsLimit(100,
                        ProtectionClaimService.MAX_CHUNKS_PER_DIMENSION - 100)
                        && ProtectionClaimService.exceedsLimit(100,
                        ProtectionClaimService.MAX_CHUNKS_PER_DIMENSION - 99));

        // ④ 取消认领走同一条路径（界面右键 = 这一批）
        ProtectionClaimService.Report dropped = ProtectionClaimService.apply(level, false, new long[]{probeKey});
        check("批量取消认领必须真的落库（" + dropped.summary() + "）", dropped.changed() == 1);
        BlockPos probeMid = new BlockPos((probeX << 4) + 1, 64, (probeZ << 4) + 1);
        check("取消后立刻放行（实际 " + desc(data.protectionReason(level, probeMid)) + "）",
                data.protectionReason(level, probeMid) == null);

        // ⑤ S2C 快照：往返 + 数量撒谎时**夹住**（客户端宽容，不把人踢下线）
        ProtectionClaimsPacket snapshotBack = null;
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            ProtectionClaimsPacket.encode(new ProtectionClaimsPacket(dimension, false, new long[]{probeKey}),
                    buffer);
            snapshotBack = ProtectionClaimsPacket.decode(buffer);
        } catch (RuntimeException broken) {
            BotLog.warn("[Protection] 快照往返异常: {}", broken.toString());
        }
        check("S2C 快照编解码往返：维度 + truncated + 区块键逐字回来",
                snapshotBack != null && dimension.equals(snapshotBack.dimension()) && !snapshotBack.truncated()
                        && snapshotBack.chunkKeys().length == 1 && snapshotBack.chunkKeys()[0] == probeKey);
        ProtectionClaimsPacket clamped = null;
        try {
            FriendlyByteBuf lying = new FriendlyByteBuf(Unpooled.buffer());
            lying.writeResourceLocation(dimension);
            lying.writeBoolean(false);
            lying.writeVarInt(-1);      // 本地列：撒谎
            lying.writeVarInt(-1);      // 外部列：同样撒谎（D-316 起快照有两列）
            clamped = ProtectionClaimsPacket.decode(lying);
        } catch (RuntimeException unexpected) {
            BotLog.warn("[Protection] 快照夹住失败: {}", unexpected.toString());
        }
        check("S2C 数量撒谎（两列都 -1）必须**夹住**（都读成 0 个），而不是把客户端踢下线",
                clamped != null && clamped.chunkKeys().length == 0 && clamped.externalChunkKeys().length == 0);

        // ⑥ 快照有界：按「离玩家最近」截断 + truncated 如实置位（纯函数，无世界写入）
        Set<Long> synthetic = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            synthetic.add(ChunkPos.asLong(i, 0));
        }
        ProtectionClaimsPacket near = ProtectionClaimService.selectNearest(dimension, synthetic, 0, 0, 5);
        boolean allNearest = true;
        for (long key : near.chunkKeys()) {
            if (ChunkPos.getX(key) > 4) {
                allNearest = false;
            }
        }
        check("快照有界：20 个认领取 5 个 ⇒ 恰好 5 个、都是**最近**的、truncated=true",
                near.chunkKeys().length == 5 && near.truncated() && allNearest);
        ProtectionClaimsPacket whole = ProtectionClaimService.selectNearest(dimension, synthetic, 0, 0, 100);
        check("上限够大 ⇒ 全发且 truncated=false",
                whole.chunkKeys().length == 20 && !whole.truncated());

        BotLog.info("[Protection] 协议契约 ✓：动作包(无维度字段/超量拒收) · 落库({}) · 快照(往返/夹住/截断)",
                first.summary());
        advance(Phase.EXTERNAL);
    }

    /**
     * **外部认领源**（D-316）：FTB Chunks 这类"别人的地盘"怎么接进我们的闸门。
     *
     * <p>这一组**完全离线可判**：真模组不在场也能验（这就是"缝 + 假源"的价值）——
     * 服务端只按 {@link ClaimSources.Source} 问，夹具注册一个假源即可。
     * 真适配器（反射调 FTB API）只在这里断言"**缺模组时必须空转**"，它的真实行为留给客户端轮次。
     */
    private void externalPhase() {
        ServerLevel level = bot.serverLevel();
        SafeZoneData data = SafeZoneData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        BlockPos here = bot.blockPosition();
        long fakeKey = ChunkPos.asLong(hereChunkX, hereChunkZ);
        int sourcesBefore = ClaimSources.size();

        fakeSource = new FakeClaimSource("fake_claim", hereChunkX, hereChunkZ);
        ClaimSources.register(fakeSource);
        check("注册一个外部认领源 ⇒ 来源表 +1（" + sourcesBefore + " → " + ClaimSources.size() + "）",
                ClaimSources.size() == sourcesBefore + 1);
        check("外部认领的区块 ⇒ 理由码 protected_fake_claim（实际 "
                        + desc(data.protectionReason(level, here)) + "）",
                "protected_fake_claim".equals(data.protectionReason(level, here)));
        check("外部认领在**唯一入口**上生效：BlockBreakSafety 两条策略都看得到（实际 "
                        + desc(BlockBreakSafety.explicitTargetRefusal(bot, here)) + " / "
                        + desc(BlockBreakSafety.refusal(bot, here, WriteReason.PATH_ACCESS)) + "）",
                "protected_fake_claim".equals(BlockBreakSafety.explicitTargetRefusal(bot, here))
                        && "protected_fake_claim".equals(
                        BlockBreakSafety.refusal(bot, here, WriteReason.PATH_ACCESS)));

        BlockPos outside = new BlockPos(here.getX() + 32, here.getY(), here.getZ() + 32);
        check("没被外部认领的区块**不受影响**（不许过度拦截；该点在 chunk "
                        + (outside.getX() >> 4) + "," + (outside.getZ() >> 4) + "，实际 "
                        + desc(data.protectionReason(level, outside)) + "）",
                data.protectionReason(level, outside) == null);
        check("⭐ **不复制**：外部认领**没有**进我们的存档（claimedChunkCount=" + data.claimedChunkCount()
                        + " = " + chunksBefore + "；本地集合命中="
                        + data.claims(dimension).contains(fakeKey) + "）",
                data.claimedChunkCount() == chunksBefore && !data.claims(dimension).contains(fakeKey));

        ProtectionClaimsPacket snapshot = ProtectionClaimService.snapshot(bot);
        check("S2C 快照把它放进**外部**那一列（本地列不放 ⇒ 客户端才能标出来源；实际 external="
                        + snapshot.externalChunkKeys().length + " local=" + snapshot.chunkKeys().length + "）",
                containsKey(snapshot.externalChunkKeys(), fakeKey)
                        && !containsKey(snapshot.chunkKeys(), fakeKey));

        // ⚠️ 失败方向：第三方升级导致查询抛异常时**跳过 + 告警**，不许冒泡（否则 bot 会在全世界都不能动）
        ClaimSources.register(THROWING_SOURCE);
        boolean survived = true;
        try {
            survived = data.protectionReason(level, outside) == null;
        } catch (RuntimeException | LinkageError blown) {
            survived = false;
            BotLog.warn("[Protection] 异常源把异常冒泡出来了: {}", blown.toString());
        }
        check("抛异常的外部源不许打死服务端（跳过它，其余照常；实际 survived=" + survived + "）", survived);
        ClaimSources.unregister(THROWING_SOURCE);

        check("前提：无头服务端**没有**装 FTB Chunks ⇒ 适配器自认 available=false（实际 "
                        + FtbChunksClaims.available() + "）",
                !FtbChunksClaims.available());
        check("模组不在场时适配器恒返回 false（行为与没有兼容时**完全一致**）",
                !FtbChunksClaims.INSTANCE.isClaimed(level, new ChunkPos(hereChunkX, hereChunkZ)));

        ClaimSources.unregister(fakeSource);
        fakeSource = null;
        check("自清理：摘掉外部源后理由码复原（实际 " + desc(data.protectionReason(level, here))
                        + "），来源表回到 " + sourcesBefore + "（实际 " + ClaimSources.size() + "）",
                data.protectionReason(level, here) == null && ClaimSources.size() == sourcesBefore);
        BotLog.info("[Protection] 外部认领源 ✓：唯一入口生效 / 不复制进存档 / 快照分列 / 异常不冒泡 / 缺模组空转");
        advance(Phase.GEOMETRY);
    }

    /** 假的外部认领源（夹具用：只认一个区块）。 */
    private static final class FakeClaimSource implements ClaimSources.Source {
        private final String id;
        private final long only;

        FakeClaimSource(String id, int chunkX, int chunkZ) {
            this.id = id;
            this.only = ChunkPos.asLong(chunkX, chunkZ);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isClaimed(ServerLevel level, ChunkPos pos) {
            return ChunkPos.asLong(pos.x, pos.z) == only;
        }
    }

    /** 每次查询都抛异常的源（钉住"异常不许冒泡"这条）。 */
    private static final ClaimSources.Source THROWING_SOURCE = new ClaimSources.Source() {
        @Override
        public String id() {
            return "throwing_probe";
        }

        @Override
        public boolean isClaimed(ServerLevel level, ChunkPos pos) {
            throw new IllegalStateException("夹具故意抛出的外部源故障");
        }
    };

    private static boolean containsKey(long[] keys, long wanted) {
        for (long key : keys) {
            if (key == wanted) {
                return true;
            }
        }
        return false;
    }

    /**
     * **界面几何**（D-314）：格子 ⇄ 区块的换算。
     *
     * <p>这一组是"用离线判据换掉一个客户端轮次"的落点 —— 网格点错格、边界外点击误伤、
     * 中心格不是玩家所在区块，这些都属于"只有真人才能发现"的观感类 bug，但它们的**数学部分**
     * 完全可以在这里穷举断言（{@link ProtectionMapGeometry} 没有任何客户端依赖）。
     */
    private void geometryPhase() {
        boolean oddAndBounded = true;
        StringBuilder seen = new StringBuilder();
        for (int available : new int[]{60, 100, 142, 168, 200, 320, 1000}) {
            int grid = ProtectionMapGeometry.fitGrid(available, 9);
            seen.append(grid).append(' ');
            if (grid % 2 == 0 || grid < ProtectionMapGeometry.MIN_GRID
                    || grid > ProtectionMapGeometry.MAX_GRID) {
                oddAndBounded = false;
            }
        }
        check("网格边长恒为**正奇数**且落在 [" + ProtectionMapGeometry.MIN_GRID + ","
                + ProtectionMapGeometry.MAX_GRID + "]（实际 " + seen.toString().trim() + "）", oddAndBounded);

        int tinyCell = ProtectionMapGeometry.fitCell(20, 25);
        int hugeCell = ProtectionMapGeometry.fitCell(100_000, 9);
        check("单格边长被夹在 [" + ProtectionMapGeometry.MIN_CELL + "," + ProtectionMapGeometry.MAX_CELL
                        + "]（实际 " + tinyCell + " / " + hugeCell + "）",
                tinyCell == ProtectionMapGeometry.MIN_CELL && hugeCell == ProtectionMapGeometry.MAX_CELL);

        ProtectionMapGeometry geometry = new ProtectionMapGeometry(hereChunkX, hereChunkZ, 17, 12, 100, 40);
        long centerKey = ChunkPos.asLong(hereChunkX, hereChunkZ);
        check("中心格 = 玩家所在区块（期望 " + ChunkPos.getX(centerKey) + "," + ChunkPos.getZ(centerKey)
                        + "，实际 " + describeKey(geometry.keyAtCell(8, 8)) + "）",
                geometry.keyAtCell(8, 8) == centerKey);
        check("左上角格 = 中心 - half（实际 " + describeKey(geometry.keyAtCell(0, 0)) + "）",
                geometry.keyAtCell(0, 0) == ChunkPos.asLong(hereChunkX - 8, hereChunkZ - 8));

        Set<Long> keys = new LinkedHashSet<>();
        boolean invertible = true;
        for (int row = 0; row < 17; row++) {
            for (int column = 0; column < 17; column++) {
                long key = geometry.keyAtCell(column, row);
                keys.add(key);
                if (geometry.columnOf(ChunkPos.getX(key)) != column
                        || geometry.rowOf(ChunkPos.getZ(key)) != row) {
                    invertible = false;
                }
            }
        }
        check("17×17 格 ⇒ 恰好 289 个**互不相同**的区块（无重叠、无空洞；实际 " + keys.size() + "）",
                keys.size() == 289);
        check("格 ⇄ 区块**可逆**（columnOf/rowOf 与 keyAt 互为逆运算）", invertible);
        check("鼠标落在中心格正中 ⇒ 玩家所在区块",
                geometry.keyAtPixel(geometry.cellLeft(8) + 6.0, geometry.cellTop(8) + 6.0) == centerKey);
        check("网格外点击一律返回 null（左/上/右下三个方向都不误伤别的控件）",
                geometry.keyAtPixel(99.0, 39.0) == null && geometry.keyAtPixel(-50.0, -50.0) == null
                        && geometry.keyAtPixel(geometry.right() + 5.0, geometry.bottom() + 5.0) == null);
        check("网格右下角**最后一个像素**仍命中最后一格",
                geometry.keyAtPixel(geometry.right() - 2.0, geometry.bottom() - 2.0) == geometry.keyAtCell(16, 16));

        // ⭐ D-317：**两个坐标空间不许混淆**。客户端实测崩过一次：`keyAt(mouseX, mouseY)` 里鼠标是 int，
        // Java 重载解析把"像素"喂给了"格索引"那个重载 ⇒ 索引越界崩在渲染线程。现在两个空间两个名字，
        // 并且这条判据钉住"像素空间必须自己夹边界"（喂像素级数字不许当格号用）。
        check("**像素空间自己夹边界**：把像素级数字（400,300）喂进去必须返回 null，而不是当成格号",
                geometry.keyAtPixel(400.0, 300.0) == null && geometry.keyAtPixel(0.0, 0.0) == null);
        check("两个坐标空间含义**不同**（同一对数字 100,40）：keyAtCell 是**远处区块**、keyAtPixel 是**左上第一格**",
                geometry.keyAtCell(100, 40) == ChunkPos.asLong(hereChunkX - 8 + 100, hereChunkZ - 8 + 40)
                        && geometry.keyAtPixel(100.0, 40.0) == ChunkPos.asLong(hereChunkX - 8, hereChunkZ - 8));

        // ⭐ 门禁（D-317）：**同名重载不许复活**。这一类的代价是一次客户端崩溃（渲染线程越界），
        // 离线判据照不到"调用点选错重载" ⇒ 用反射把"两套坐标空间必须两个名字"钉成判据。
        Set<String> methodNames = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (java.lang.reflect.Method method : ProtectionMapGeometry.class.getDeclaredMethods()) {
            if (!methodNames.add(method.getName())) {
                duplicated.add(method.getName());
            }
        }
        check("几何类**不许有同名重载**（像素空间与格索引空间必须两个名字；实际 " + methodNames.size()
                + " 个方法，重名=" + duplicated + "）", duplicated.isEmpty());

        // ============ D-315：地形细化的纯逻辑（子格平铺 / 采样点 / 中心向外采样序）============
        check("子格数按格子大小定档：cell≥12 ⇒ 3，否则 2（实际 cell=" + geometry.cell() + " ⇒ sub="
                        + geometry.sub() + "；另测 12/11：" + ProtectionMapGeometry.fitSub(12) + "/"
                        + ProtectionMapGeometry.fitSub(11) + "）",
                geometry.sub() == ProtectionMapGeometry.fitSub(geometry.cell())
                        && ProtectionMapGeometry.fitSub(12) == ProtectionMapGeometry.MAX_SUB
                        && ProtectionMapGeometry.fitSub(11) == ProtectionMapGeometry.MIN_SUB);

        ProtectionMapGeometry tile = new ProtectionMapGeometry(0, 0, 17, 12, 100, 40);
        boolean tiledExactly = tile.sub() == 3;
        for (int column = 0; column < 17 && tiledExactly; column++) {
            for (int s = 0; s < tile.sub(); s++) {
                int expectLeft = 100 + column * 12 + s * 4;
                if (tile.subLeft(column, s) != expectLeft || tile.subRight(column, s) != expectLeft + 4) {
                    tiledExactly = false;
                }
                if (s < tile.sub() - 1 && tile.subRight(column, s) != tile.subLeft(column, s + 1)) {
                    tiledExactly = false;       // 相邻子格必须**首尾相接**（无缝、无重叠）
                }
            }
            if (tile.subRight(column, tile.sub() - 1) != tile.subLeft(column, 0) + 12) {
                tiledExactly = false;           // 最后一个子格必须正好落在格子右边界
            }
        }
        check("cell=12/sub=3 ⇒ 子格边界 0/4/8/12 且首尾相接、末块正好到格边（整格精确平铺）", tiledExactly);

        ProtectionMapGeometry odd = new ProtectionMapGeometry(0, 0, 17, 9, 0, 0);
        boolean oddTiled = odd.sub() == 2 && odd.subLeft(0, 0) == 0 && odd.subLeft(0, 1) == 4
                && odd.subRight(0, 1) == 9 && odd.subRight(0, 0) == odd.subLeft(0, 1);
        check("cell=9 不能被 sub=2 整除时**也不留缝**（0/4/9 ⇒ 块宽 4/5 交替）", oddTiled);

        boolean samplesOk = true;
        for (int s = ProtectionMapGeometry.MIN_SUB; s <= ProtectionMapGeometry.MAX_SUB; s++) {
            int previous = -1;
            for (int i = 0; i < s; i++) {
                int local = ProtectionMapGeometry.sampleLocal(s, i);
                // 判据 = **属于自己那个子块**（i 号子块覆盖本区块第 i 段 16/s 列）+ 严格递增 + 落在 0..15。
                // ⚠️ 不照抄"正中"那个公式（那是实现细节、抄了就是自指）；"列必须落在画它的那一格里"
                // 才是**会出错**的那条：用固定步长（如 i*8）在 sub=3 时会采到第 16 列 ⇒ 立刻红。
                if (local < i * 16 / s || local >= (i + 1) * 16 / s || local <= previous) {
                    samplesOk = false;
                }
                previous = local;
            }
        }
        check("子格采样列**落在自己那一格内**且严格递增（sub=2 ⇒ 4,12；sub=3 ⇒ 2,8,13；实际 "
                        + ProtectionMapGeometry.sampleLocal(2, 0) + "," + ProtectionMapGeometry.sampleLocal(2, 1) + " | "
                        + ProtectionMapGeometry.sampleLocal(3, 0) + "," + ProtectionMapGeometry.sampleLocal(3, 1) + ","
                        + ProtectionMapGeometry.sampleLocal(3, 2) + "）",
                samplesOk);

        int[] order = ProtectionMapGeometry.centreOutOrder(17);
        Set<Integer> seenCells = new LinkedHashSet<>();
        boolean ringsMonotonic = true;
        int previousRadius = -1;
        for (int cellIndex : order) {
            seenCells.add(cellIndex);
            int column = cellIndex % 17;
            int row = cellIndex / 17;
            int radius = Math.max(Math.abs(row - 8), Math.abs(column - 8));
            if (radius < previousRadius) {
                ringsMonotonic = false;         // 必须真的按环推进：不许先跳到远处再回头
            }
            previousRadius = radius;
        }
        check("中心向外采样序：长度 = grid²（" + order.length + "）、互不相同（" + seenCells.size()
                        + "）、首元素 = 正中心格（实际 " + order[0] + " vs " + (8 * 17 + 8) + "）",
                order.length == 289 && seenCells.size() == 289 && order[0] == 8 * 17 + 8);
        check("采样序的**切比雪夫距离单调不减**（先算玩家周围、远处才补）", ringsMonotonic);

        BotLog.info("[Protection] 界面几何 ✓：{}（中心格=玩家区块；289 格双射；网格外返回 null；"
                        + "子格 sub={} 精确平铺；采样序 289 格由中心向外）",
                geometry.describe(), tile.sub());
        advance(Phase.BLACKLIST);
    }

    private static String describeKey(long chunkKey) {
        return ChunkPos.getX(chunkKey) + "," + ChunkPos.getZ(chunkKey);
    }

    /** 黑名单语义**不变**（回归）：方块 ID 与标签两条规则仍然命中，且可取消。 */
    private void blacklistPhase() {
        ServerLevel level = bot.serverLevel();
        SafeZoneData data = SafeZoneData.get(level.getServer());
        BlockPos support = supportPos();
        supportBlockId = blockId(level, support);
        supportTag = firstTag(level, support);

        check("前提：支撑方块有可用的方块 ID（实际 " + desc(supportBlockId) + "）", supportBlockId != null);
        check("前提：黑名单判据前该位置**没有**保护命中（否则会被 protected_area 遮住；实际 "
                        + desc(data.protectionReason(level, support)) + "）",
                data.protectionReason(level, support) == null);
        if (supportBlockId != null) {
            addedBlockRule = data.addBlock(supportBlockId);
            check("方块规则：加入 " + supportBlockId + " ⇒ protected_block（实际 "
                            + desc(data.protectionReason(level, support)) + "）",
                    "protected_block".equals(data.protectionReason(level, support)));
            data.removeBlock(supportBlockId);
            addedBlockRule = false;
            check("方块规则取消后放行（实际 " + desc(data.protectionReason(level, support)) + "）",
                    data.protectionReason(level, support) == null);
        }
        check("前提：支撑方块至少属于一个方块标签（否则标签判据无法构造前提；实际 "
                        + desc(supportTag == null ? null : supportTag.location()) + "）", supportTag != null);
        if (supportTag != null) {
            addedTagRule = data.addTag(supportTag.location());
            check("标签规则：加入 #" + supportTag.location() + " ⇒ protected_tag（实际 "
                            + desc(data.protectionReason(level, support)) + "）",
                    "protected_tag".equals(data.protectionReason(level, support)));
            data.removeTag(supportTag.location());
            addedTagRule = false;
            check("标签规则取消后放行（实际 " + desc(data.protectionReason(level, support)) + "）",
                    data.protectionReason(level, support) == null);
        }
        BotLog.info("[Protection] 黑名单回归 ✓（方块 / 标签两条规则各自命中并可取消）");
        advance(Phase.CLEANUP);
    }

    // ==================== 收尾 ====================

    private Task.Status finish() {
        ServerLevel level = bot.serverLevel();
        SafeZoneData data = SafeZoneData.get(level.getServer());
        // 失败路径同样走这里：把本夹具造过的东西全部拆掉，并**断言**回到进入前的状态
        data.unclaim(level, hereChunkX, hereChunkZ);
        if (probeChunkX != Integer.MIN_VALUE) {
            data.unclaim(level, probeChunkX, hereChunkZ);
        }
        if (addedBlockRule && supportBlockId != null) {
            data.removeBlock(supportBlockId);
            addedBlockRule = false;
        }
        if (addedTagRule && supportTag != null) {
            data.removeTag(supportTag.location());
            addedTagRule = false;
        }
        if (fakeSource != null) {
            ClaimSources.unregister(fakeSource);      // 假外部源绝不能留给后面的步骤
            fakeSource = null;
        }
        if (hereWasClaimed) {
            data.claim(level, hereChunkX, hereChunkZ);      // 进入前就有 ⇒ 复原（精确复原，不是"清空"）
        }
        check("自清理：认领计数必须回到进入前的值（" + data.claimedChunkCount() + " = " + chunksBefore + "）",
                data.claimedChunkCount() == chunksBefore);
        bot.controller().stopMovement();      // 复位：夹具不动 bot，但保持"结束即停输入"的同一条纪律
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[Protection] SUMMARY checks={} failures={} {} → {}（{}）",
                checks, failures.size(), failures, pass ? "PASS" : "FAIL", data.summary());
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[alice] 保护区自检 " + (pass ? "PASS" : "FAIL " + failures)));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    private void advance(Phase next) {
        phase = next;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }

    private BlockPos supportPos() {
        return bot.blockPosition().below();
    }

    private static ResourceLocation blockId(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() ? null : ForgeRegistries.BLOCKS.getKey(state.getBlock());
    }

    private static TagKey<Block> firstTag(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock().builtInRegistryHolder().tags().findFirst().orElse(null);
    }

    private static String desc(Object value) {
        return value == null ? "无" : value.toString();
    }

    /** 造一个**旧格式**（v1：圆形半径）的存档标签，用于迁移契约测试。 */
    private static CompoundTag legacyTag(String dimensionId, int x, int y, int z, int radius) {
        CompoundTag area = new CompoundTag();
        area.putString("dimension", dimensionId);
        area.putInt("x", x);
        area.putInt("y", y);
        area.putInt("z", z);
        area.putInt("radius", radius);
        ListTag areas = new ListTag();
        areas.add(area);
        CompoundTag root = new CompoundTag();
        root.put("areas", areas);
        root.put("blocks", new ListTag());
        root.put("tags", new ListTag());
        return root;
    }
}
