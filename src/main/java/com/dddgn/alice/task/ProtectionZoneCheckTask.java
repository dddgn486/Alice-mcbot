package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

    private enum Phase { ZONE, RELEASE, PERSIST, MIGRATE, BLACKLIST, CLEANUP, DONE }

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
        advance(Phase.BLACKLIST);
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
