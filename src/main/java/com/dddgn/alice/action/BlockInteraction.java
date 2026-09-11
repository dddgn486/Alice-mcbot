package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 可复用的方块交互原语（对齐 Baritone）。
 *
 * <p>这是挖掘/放置语义的**唯一入口**：工具选择、触及距离、朝向、破坏进度、放置面选择。
 * 现有 legacy 的 {@code level.destroyBlock(...)} 瞬间销毁调用点应逐步改走本层。
 *
 * <p>参考 Baritone：
 * <ul>
 *   <li>工具选择 {@code MovementHelper.switchToBestToolFor:628-643}；</li>
 *   <li>放置面选择 {@code MovementHelper.attemptToPlaceABlock:791-843}
 *       （直放 → 扫 5 个邻面（水平+下，不含上）→ 算面中心 → 视线校验 → 使用物品）；</li>
 *   <li>破坏 {@code Movement.prepared:153-193} + 原版 {@code ServerPlayerGameMode} 进度。</li>
 * </ul>
 */
public final class BlockInteraction {

    /** 放置候选支撑面方向（Baritone：水平 + 下，不含上）。 */
    private static final List<Direction> SUPPORT_SIDES = List.of(
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN);

    /**
     * "一次性"方块（垫脚 / 台阶 / 挖矿支撑用的可损失方块）——**数据驱动**（`alice:throwaway` 标签）。
     *
     * <p>对照 Baritone {@code selectThrowawayForLocation}（Alice 的等价简化）。
     *
     * <p>**为什么改成标签**（2026-09-11，用户裁定"只做标签化、集合内容不变"）：
     * 原为硬编码 7 个原版方块的清单——**任何模组方块都用不了**，整合包里会出现
     * "有石头却垫不了脚、`PILLAR`/`PLACE_STEP` 生成不出来"（即 D-099 那类故障的模组版）。
     * 标签化后**不改代码**即可扩展：整合包/模组在自己的
     * {@code data/<ns>/tags/blocks/throwaway.json} 里追加即可（`replace:false` 会合并）。
     * 本轮**只整理、不扩张**：标签内容与原清单完全一致；将来要放宽，把通用标签
     * （{@code #minecraft:base_stone_overworld} / {@code #forge:cobblestone} 之类）加进标签文件即可。
     */
    public static final TagKey<Block> THROWAWAY = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("alice", "throwaway"));

    public enum PlaceResult { PLACED, NO_OPTION }

    private BlockInteraction() {
    }

    // ==================== 触及与朝向 ====================

    /** 方块触及距离（Forge 玩家默认 4.5）。 */
    public static double blockReach(ServerPlayer bot) {
        return bot.getBlockReach();
    }

    /** 是否在触及距离内（Baritone RotationUtils.reachable 的距离部分）。 */
    public static boolean reachable(ServerPlayer bot, BlockPos pos) {
        return bot.getEyePosition().distanceTo(pos.getCenter()) <= blockReach(bot) + 0.5D;
    }

    /** 朝向方块的面（Baritone 用 Direction.getNearest(eye→center)）。 */
    public static Direction faceToward(ServerPlayer bot, BlockPos pos) {
        Vec3 eye = bot.getEyePosition();
        return Direction.getNearest(
                pos.getX() + 0.5D - eye.x,
                pos.getY() + 0.5D - eye.y,
                pos.getZ() + 0.5D - eye.z);
    }

    /** 转身面向方块中心；头/身/俯仰一起写，避免头身不一致。 */
    public static void faceBlock(ServerPlayer bot, BlockPos pos) {
        faceTowards(bot, pos.getCenter());
    }

    /** 面向任意世界坐标点（放置面中心用）。 */
    public static void faceTowards(ServerPlayer bot, Vec3 target) {
        Vec3 eye = bot.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setXRot(pitch);
    }

    // ==================== 工具 ====================

    /** 快捷栏中对该方块破坏速度最快的槽位；无法破坏（速度 0）返回 -1。 */
    public static int findBestToolSlot(ServerPlayer bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        Inventory inventory = bot.getInventory();
        int bestSlot = -1;
        float bestSpeed = 0.0F;
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSpeed > 0.0F ? bestSlot : -1;
    }

    /**
     * 切换到挖掘该方块的最佳工具（空手速度视为 1.0）。
     *
     * <p>**改选中槽必须同时广播手持物品**（2026-09-10 修：用户实测"bot 看起来拿着镐子，
     * 砍树却很快"）：服务端换了工具（破坏速度按斧子算 → 快），但客户端仍渲染旧物品（镐），
     * 重进存档才刷新。原因是这里只改了 `inventory.selected` 这个服务端字段，
     * 没有复用 {@code BotManager.syncMainHand}（它广播 `ClientboundSetEquipmentPacket`，
     * 且**刻意绕过 FakeConnection**——FakeConnection 会丢弃装备包以免搞乱玩家快捷栏）。
     */
    public static void switchToBestToolFor(ServerPlayer bot, BlockPos pos) {
        int slot = findBestToolSlot(bot, pos);
        if (slot >= 0 && slot != bot.getInventory().selected) {
            bot.getInventory().selected = slot;
            com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        }
    }

    // ==================== 放置 ====================

    /** 该方块能否作为放置支撑面（实心、非空气、非流体）。 */
    public static boolean isSolidForPlacement(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * 快捷栏中可放置的方块槽位：**只接受一次性方块白名单**
     * （对照 Baritone {@code Settings.acceptableThrowawayItems:230-235}）。
     * <p>不再兜底"任意 BlockItem"：否则火把/花/告示牌会被拿去"搭台阶"，
     * 放置"成功"但目标仍不可站，执行器只能重试到超时。
     */
    public static int findPlaceableSlot(ServerPlayer bot) {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (blockItem.getBlock().defaultBlockState().is(THROWAWAY)) {
                return slot;
            }
        }
        return -1;
    }

    /** 快捷栏中一次性方块的总数量（FALL 的"PILLAR 返回"守卫用）。 */
    public static int countThrowaway(ServerPlayer bot) {
        Inventory inventory = bot.getInventory();
        int total = 0;
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (blockItem.getBlock().defaultBlockState().is(THROWAWAY)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 在 {@code placeAt} 放置一个方块（对齐 Baritone attemptToPlaceABlock）。
     *
     * <p>流程：触及检查 → 选可放置方块 → 扫水平+下的邻面找支撑 → 计算面中心 →
     * 视线校验（命中该支撑块且面朝 placeAt）→ 转向 → 使用物品。
     *
     * @return {@link PlaceResult#PLACED} 表示已发起放置；{@link PlaceResult#NO_OPTION} 表示不可行
     */
    public static PlaceResult placeAt(ServerPlayer bot, ServerLevel level, BlockPos placeAt, boolean sneak,
                                      WriteGrant grant) {
        // 账本要在放置**之前**拿到原状态（J6-a：精确恢复原状的前提）
        BlockState previousState = level.getBlockState(placeAt);
        if (!reachable(bot, placeAt)) {
            return PlaceResult.NO_OPTION;
        }
        int slot = findPlaceableSlot(bot);
        if (slot < 0) {
            return PlaceResult.NO_OPTION;
        }
        for (Direction supportSide : SUPPORT_SIDES) {
            BlockPos against = placeAt.relative(supportSide);
            if (!isSolidForPlacement(level, against)) {
                continue;
            }
            Direction clickFace = supportSide.getOpposite();
            Vec3 faceCenter = Vec3.atCenterOf(against)
                    .add(Vec3.atLowerCornerOf(clickFace.getNormal()).scale(0.5D));
            // 说明：Baritone 在客户端会用 rayTraceTowards 校验"该面确实可见"。
            // Alice 是服务端直接构造 BlockHitResult 调用 gameMode.useItemOn（等价于客户端上报的命中包），
            // 不依赖本地射线；且站在方块顶面时射线必然先命中顶面，做可见性校验会永远失败。
            faceTowards(bot, faceCenter);
            if (sneak) {
                bot.setShiftKeyDown(true);
            }
            if (bot.getInventory().selected != slot) {
                bot.getInventory().selected = slot;
                com.dddgn.alice.bot.BotManager.syncMainHand(bot);   // 同 switchToBestToolFor：显示必须跟上
            }
            ItemStack stack = bot.getInventory().getItem(slot);
            BlockHitResult hit = new BlockHitResult(faceCenter, clickFace, against, false);
            InteractionResult result = bot.gameMode.useItemOn(bot, level, stack, InteractionHand.MAIN_HAND, hit);
            if (!result.consumesAction()) {
                // 服务端拒绝该面 → 继续尝试其他候选面（对照 Baritone BlockPlaceHelper:48-52）
                continue;
            }
            if (level.getBlockState(placeAt).canBeReplaced()) {
                // 返回"成功"但方块未落地：以服务器世界为准，继续尝试
                continue;
            }
            bot.swing(InteractionHand.MAIN_HAND);
            WriteAudit.placeWrite(level, placeAt, level.getBlockState(placeAt), grant);
            // 账本记录（J6-a）：动作层是唯一看得见"每一次修改"的地方（含内核 PILLAR 放的方块）
            com.dddgn.alice.ledger.WorldModLedger.recordPlacement(level, bot.getUUID(), grant, placeAt,
                    previousState, level.getBlockState(placeAt));
            return PlaceResult.PLACED;
        }
        return PlaceResult.NO_OPTION;
    }

    /** 是否存在可用的放置支撑面（水平+下的邻面里有实心块）。 */
    public static boolean hasPlacementFace(ServerLevel level, BlockPos placeAt) {
        for (Direction supportSide : SUPPORT_SIDES) {
            if (isSolidForPlacement(level, placeAt.relative(supportSide))) {
                return true;
            }
        }
        return false;
    }

    // ==================== 破坏 ====================

    /**
     * 破坏拒绝原因（null = 允许）。**策略由 {@link WriteGrant#reason()} 派生**（D-082），
     * 调用点不再通过"调哪个方法"隐式选择策略。
     */
    public static String breakRefusal(ServerPlayer bot, ServerLevel level, BlockPos pos, WriteGrant grant) {
        if (level.getBlockState(pos).isAir()) {
            return "already_air";
        }
        return com.dddgn.alice.protection.BlockBreakSafety.refusal(bot, pos, grant.reason());
    }

    /**
     * 该方块能否被本 bot 破坏。**策略由授权里的理由派生**（D-082）：
     * `EXPECTED_TARGET/DESCEND_FOOT/BULK_EDIT` 走明确目标策略，其余走更保守的清障策略。
     */
    public static boolean breakable(ServerPlayer bot, ServerLevel level, BlockPos pos, WriteGrant grant) {
        return breakRefusal(bot, level, pos, grant) == null;
    }

    /**
     * 破坏该方块的预计 tick 数（对照 Baritone {@code MovementHelper.getMiningDurationTicks}）。
     * 用于规划期成本，不修改世界。
     */
    public static double estimateBreakTicks(ServerPlayer bot, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0.0D;
        }
        if (!state.getFluidState().isEmpty()) {
            // 对照 Baritone getMiningDurationTicks:588-590：流体不可挖 → 代价无穷
            return Double.POSITIVE_INFINITY;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return Double.POSITIVE_INFINITY;
        }
        if (hardness == 0.0F) {
            return 1.0D;
        }
        int slot = findBestToolSlot(bot, pos);
        ItemStack stack = slot >= 0 ? bot.getInventory().getItem(slot) : ItemStack.EMPTY;
        float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
        boolean canHarvest = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        double seconds = canHarvest
                ? (double) hardness * 1.5D / Math.max(speed, 1.0E-4F)
                : (double) hardness * 5.0D / Math.max(speed, 1.0E-4F);
        return Math.max(1.0D, seconds * 20.0D);
    }

    /** 开启一个按 tick 推进的破坏会话（推荐路径）；登记审计。 */
    public static BlockBreakSession beginBreak(ServerPlayer bot, ServerLevel level, BlockPos pos,
                                              WriteGrant grant) {
        WriteAudit.breakWrite(level, pos, level.getBlockState(pos), grant);
        return BlockBreakSession.begin(bot, level, pos);
    }

    /**
     * 批量地形编辑用：立即放置方块（道路施工等非寻路场景）。
     *
     * <p>与 {@link #placeAt} 的区别：**不消耗背包物品**、不做支撑面射线，直接写世界。
     * 正因如此它没有天然的资源约束，**授权与保护区检查必须在这里做**：
     * 2026-09-10 勘测发现道路施工有两套实现都在裸调 {@code level.setBlock}，
     * 既不查保护区也无任何凭证（D-082 修复）。
     *
     * @return true = 已放置；false = 被保护区拒绝（**未写入**）
     */
    public static boolean placeBulkEdit(ServerPlayer bot, ServerLevel level, BlockPos pos, BlockState state,
                                        WriteGrant grant) {
        String protectedReason = com.dddgn.alice.protection.SafeZoneData.get(level.getServer())
                .protectionReason(level, pos);
        if (protectedReason != null) {
            BotLog.warn("[WRITE-REFUSED] place pos={} by={} reason={}",
                    pos.toShortString(), grant.describe(), protectedReason);
            return false;
        }
        BlockState previousState = level.getBlockState(pos);
        WriteAudit.placeWrite(level, pos, state, grant);
        level.setBlock(pos, state, 3);
        com.dddgn.alice.ledger.WorldModLedger.recordPlacement(level, bot.getUUID(), grant, pos,
                previousState, state);
        return true;
    }

    /**
     * 批量地形编辑用：立即销毁方块（道路施工等非寻路场景）。
     *
     * <p>语义与 {@code level.destroyBlock} 相同，但集中到本层以便统一审计；
     * **拒绝判定已收进本方法**（见方法体），调用方不再需要自行预检。
     *
     * @return true = 已破坏；false = 被拒绝（**未写入**）
     */
    public static boolean breakForBulkEdit(ServerPlayer bot, ServerLevel level, BlockPos pos, boolean dropItems,
                                           WriteGrant grant) {
        // 闸门收进本方法（R2b，闭合 G9）：2026-09-10 勘测发现道路施工两套实现里
        // RoadBuildTask **根本没做任何保护区检查**，只判 `getDestroyProgress > 0`，
        // 而原先的 javadoc 把检查责任"外推给调用方"——等于没有闸门。
        // 现在按授权里的理由派生策略（BULK_EDIT → 明确目标策略：保护区/不可破坏/流体拒绝）。
        String refusal = breakRefusal(bot, level, pos, grant);
        if (refusal != null) {
            BotLog.warn("[WRITE-REFUSED] break pos={} by={} reason={}",
                    pos.toShortString(), grant.describe(), refusal);
            return false;
        }
        WriteAudit.breakWrite(level, pos, level.getBlockState(pos), grant);
        level.destroyBlock(pos, dropItems, bot);
        return true;
    }
}
