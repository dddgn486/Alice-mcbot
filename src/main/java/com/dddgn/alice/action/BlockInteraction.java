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

    public enum PlaceResult {
        PLACED,
        NO_OPTION,
        /** 执行期写入预算耗尽（D-106）：**未写入**，调用方须如实上报。 */
        BUDGET_EXHAUSTED,
        /**
         * **指定方块**不在快捷栏（{@link #placeAt(ServerPlayer, ServerLevel, BlockPos, boolean,
         * WriteGrant, Block)} 专用）：**未写入**。
         *
         * <p>单独一个值而不是复用 `NO_OPTION`：两者病因完全不同 —— `NO_OPTION` = 没有可用的支撑面，
         * `NO_ITEM` = 手上根本没有那种方块。混成一个值会让"为什么没放成"在下游无法区分。
         */
        NO_ITEM,
        /**
         * ⭐ **区域级授权拒绝**（`D-338` 附注七③）：该格在**保护区**内，而**任务区/等级**不允许放
         * （代表码见 `ZoneAuthority`：`protected_area` / `zone_read_only` / `zone_place_quota`…）：
         * **未写入**、**不消耗物品与预算**。与 `BUDGET_EXHAUSTED` 分开：一个是"额度用尽"，
         * 一个是"这块地没授权" —— 归因完全不同。
         */
        ZONE_DENIED,
        /**
         * ⭐ **通道层格拒绝**（`I5` 放置面，2026-09-25 用户裁定）：该格是**本作业自己的通道层格**
         * （脚位格 / 头位格）⇒ **未写入**、**不消耗物品与预算**。
         *
         * <p>与 `ZONE_DENIED` 分开的理由与 `NO_ITEM` 那条一样：病因与归因完全不同 ——
         * 一个是"这块地没授权"（地皮/归属），一个是"**这是我自己要反复走的那条路的格，填了我就回不去**"
         * （作业形状）。代表码 {@link TaskTargetProtection#CHANNEL_CODE}。
         */
        CHANNEL_DENIED
    }

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
     * 快捷栏里有没有"对该方块算正确工具"的物品（原版 {@code isCorrectToolForDrops} 口径）。
     *
     * <p>用途（D-119）：区分"能用现有工具慢慢挖"与"挖了也不掉落"。原版规则下
     * {@code requiresCorrectToolForDrops} 的方块（石头/圆石/矿石…）**徒手破坏不掉落**，
     * 所以任务必须先做这个**只读**判定并如实失败，而**不是**给 bot 发一把工具。
     */
    public static boolean hasCorrectTool(ServerPlayer bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
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

    /**
     * 快捷栏中**持指定方块**的槽位；没有则 -1。
     *
     * <p>对照 Baritone {@code BuilderProcess:563-570}：builder 放的是**计划里的那个方块**，
     * 槽位由"**按想要的方块状态**去匹配"得到（`valid(...)`），**不是**"随便挑一个能放的"。
     * 本方法就是那个"按方块匹配"的一半；`findPlaceableSlot` 是另一半（一次性方块白名单，
     * 服务于 PILLAR/STEP/SUPPORT 这类"有得垫就行"的语义）。**两种语义必须分开**：
     * 2026-09-13 实测事故 —— 用 `findPlaceableSlot` 去放工作台，它挑中了背包里的圆石，
     * 于是"放工作站"变成了"放了一块圆石"。
     *
     * <p>只查**快捷栏**（0..8）：从主背包搬东西到快捷栏是另一个能力（Baritone 走
     * `InventoryBehavior.attemptToPutOnHotbar`），未实现前**如实报 `NO_ITEM`**，不假装能做。
     */
    public static int findSlotForBlock(ServerPlayer bot, net.minecraft.world.level.block.Block wanted) {
        if (wanted == null) {
            return -1;
        }
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == wanted) {
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
     * 在 {@code placeAt} 放置**一个一次性方块**（对齐 Baritone attemptToPlaceABlock +
     * `acceptableThrowawayItems`）：槽位来自 {@link #findPlaceableSlot}（`alice:throwaway` 白名单）。
     *
     * <p>**要放"指定的"方块（工作台/箱子/机器…）请用带 {@code wanted} 的重载** ——
     * 本方法是"有得垫就行"的语义，它会**换掉主手**（切到白名单里第一个槽）。
     *
     * <p>流程：触及检查 → 选可放置方块 → 扫水平+下的邻面找支撑 → 计算面中心 →
     * 视线校验（命中该支撑块且面朝 placeAt）→ 转向 → 使用物品。
     *
     * @return {@link PlaceResult#PLACED} 表示已发起放置；{@link PlaceResult#NO_OPTION} 表示不可行
     */
    public static PlaceResult placeAt(ServerPlayer bot, ServerLevel level, BlockPos placeAt, boolean sneak,
                                      WriteGrant grant) {
        return placeAt(bot, level, placeAt, sneak, grant, findPlaceableSlot(bot));
    }

    /**
     * 在 {@code placeAt} 放置**指定的**方块（对照 Baritone `BuilderProcess:563-570`）。
     *
     * <p>槽位来源是{@link #findSlotForBlock 按方块匹配}，**不是**"手边随便一个能放的"。
     * 这条区分是 2026-09-13 的客户端实测事故换来的：`StationPlacement` 第一版用了"放一个一次性方块"
     * 的原语去放工作台 ⇒ 它按白名单挑中了背包里的**圆石**，"放工作站"于是变成了"放了一块圆石"
     * （日志 `[Ledger] place 46,64,303 minecraft:cobblestone←minecraft:air [TEMP CRAFT_STATION_PLACE]`）。
     * 两个语义都对，**但必须分开**。
     *
     * @return {@link PlaceResult#PLACED} 已落地；{@link PlaceResult#NO_ITEM} 手上没有该方块（未写入）；
     *         {@link PlaceResult#NO_OPTION} 无可用支撑面；{@link PlaceResult#BUDGET_EXHAUSTED} 预算耗尽
     */
    public static PlaceResult placeAt(ServerPlayer bot, ServerLevel level, BlockPos placeAt, boolean sneak,
                                      WriteGrant grant, net.minecraft.world.level.block.Block wanted) {
        if (wanted == null) {
            return PlaceResult.NO_OPTION;
        }
        int slot = findSlotForBlock(bot, wanted);
        if (slot < 0) {
            BotLog.warn("[BlockInteraction] place 指定方块不在快捷栏 wanted={} pos={} by={}（不换别的方块凑）",
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(wanted),
                    placeAt.toShortString(), grant == null ? "-" : grant.describe());
            return PlaceResult.NO_ITEM;
        }
        return placeAt(bot, level, placeAt, sneak, grant, slot);
    }

    /**
     * 放置的**唯一实现体**：槽位由调用方给定（两个公开入口各自决定"放哪个方块"）。
     *
     * <p>槽位 {@code < 0} = 没有可放的方块 ⇒ {@code NO_OPTION}（保持既有调用者的行为不变）。
     */
    private static PlaceResult placeAt(ServerPlayer bot, ServerLevel level, BlockPos placeAt, boolean sneak,
                                       WriteGrant grant, int slot) {
        // 账本要在放置**之前**拿到原状态（J6-a：精确恢复原状的前提）
        BlockState previousState = level.getBlockState(placeAt);
        // 执行期写入预算（D-106）：任务级放置预算用满 → 提前拒绝（不消耗物品、不试面）
        if (!WriteBudget.placeAllowed(bot)) {
            WriteBudget.notePlaceRefusal(bot, placeAt, grant);
            BotLog.warn("[WRITE-REFUSED] place pos={} by={} reason=write_budget_exhausted {}",
                    placeAt.toShortString(), grant == null ? "-" : grant.describe(), WriteBudget.describe(bot));
            return PlaceResult.BUDGET_EXHAUSTED;
        }
        // ⭐ 区域级授权面（`D-338` 附注七③）：**保护区内放置**这条闸门**今天本来不存在**
        //（`D-338` 核对表里的缺口）⇒ 在这里补上。判据与破坏侧**同一个函数**（`ZoneAuthority`）。
        // ⚠️ 没有任务区时拒绝码逐字仍是 `protected_area`；野外/未认领 ⇒ 不拦、不留痕。
        String zoneRefusal = com.dddgn.alice.protection.ZoneAuthority.regionRefusal(level, bot.getUUID(), placeAt,
                com.dddgn.alice.protection.SafeZoneData.get(level.getServer()).protectionReason(level, placeAt),
                grant == null ? null : grant.reason(), com.dddgn.alice.protection.ZoneAuthority.Act.PLACE);
        if (zoneRefusal != null) {
            BotLog.warn("[WRITE-REFUSED] place pos={} by={} reason={}",
                    placeAt.toShortString(), grant == null ? "-" : grant.describe(), zoneRefusal);
            return PlaceResult.ZONE_DENIED;
        }
        // ⭐ `I5` 放置面（2026-09-25）：**最后一道闸门** —— 与破坏侧 `beginBreak` 同一个理由：
        // 不能只指望所有调用点都记得先问 `placementRefusal`（"靠调用点自觉"的守卫迟早漏一处）。
        // 这一格是本作业自己的通道层格 ⇒ 填了就等于把自己那条路切断（真机证据见 `CHANNEL_CODE`）。
        String channelRefusal = placementRefusal(bot, placeAt);
        if (channelRefusal != null) {
            BotLog.warn("[WRITE-REFUSED] place pos={} by={} reason={}（填了我自己的通道层格 ⇒ 回程会被自己切断）",
                    placeAt.toShortString(), grant == null ? "-" : grant.describe(), channelRefusal);
            return PlaceResult.CHANNEL_DENIED;
        }
        if (!reachable(bot, placeAt)) {
            return PlaceResult.NO_OPTION;
        }
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
            // 预算计数放在**真正落地之后**：放置尝试会轮换支撑面，失败不占额度。
            // 顶部已用 placeAllowed 判过，这里只计数（若仍被拒说明有并发/错位，如实记日志不掩盖）
            if (WriteBudget.consumePlace(bot, level, placeAt, grant) == WriteBudget.Verdict.REFUSED) {
                BotLog.warn("[WriteBudget] place_after_check_refused pos={} by={} {}",
                        placeAt.toShortString(), grant == null ? "-" : grant.describe(),
                        WriteBudget.describe(bot));
            }
            WriteAudit.placeWrite(level, placeAt, level.getBlockState(placeAt), grant);
            // 账本记录（J6-a）：动作层是唯一看得见"每一次修改"的地方（含内核 PILLAR 放的方块）
            com.dddgn.alice.ledger.WorldModLedger.recordPlacement(level, bot.getUUID(), grant, placeAt,
                    previousState, level.getBlockState(placeAt));
            // ⭐ 区内放置计数（`L1` 的"≤8 次"配额，`D-338` 附注七②）：只统计落在**自己任务区**里的放置
            com.dddgn.alice.protection.TaskZoneRegistry.recordZonePlacement(level, bot.getUUID(), placeAt);
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

    /**
     * 放置拒绝原因（null = 允许）。**与 {@link #breakRefusal} 严格同形**（唯一定义处，
     * 搜索与执行共用 ⇒ 不会"计划说能过、执行到一半才被拒"）。
     *
     * <p>⭐ `I5` 放置面（2026-09-25 用户裁定「先治根因，让他不会在作业区放方块」）：
     * **本作业自己的通道层格不许被放方块**。真机证据与因果链见
     * {@link TaskTargetProtection#CHANNEL_CODE}。
     *
     * <p>⚠️ **刻意与 `breakRefusal` 的 `PATH_ACCESS` 白名单不同：放置侧不做任何理由豁免。**
     * 破坏侧要豁免是因为"破坏"有完全合法的用途（挖穿通道本身就是挖）；而放置侧没有 ——
     * 通道层格按定义（`I1`：可连通；`I2`：支撑存在）**必须保持可通行**，
     * 因此"把方块放进通道层格"不存在合法调用者。真正合法的"补地板"落在**支撑格**（脚位格下面那一格），
     * 不在本谓词的集合里 ⇒ 不会被误伤。
     */
    public static String placementRefusal(ServerPlayer bot, BlockPos placeAt) {
        return TaskTargetProtection.placementRefusalFor(bot, placeAt);
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
        // ⭐ `D-362`：**清障不得吃掉任务目标**（用户 2026-09-20 修正口径："要修的是清障与任务目标的区分"）。
        // 放在这里 = 搜索（SurfaceMovementProvider）与执行（BreakAnd*Execution / PathSession 复检）**同时**生效
        // ⇒ 不会"计划说能过、执行到一半才被拒"。对照 Baritone `MovementHelper.avoidBreaking:68`（⇒ `:590` COST_INF）。
        if (grant != null && grant.reason() == WriteReason.PATH_ACCESS) {
            String protectedTarget = TaskTargetProtection.refusalFor(bot, pos);
            if (protectedTarget != null) {
                return protectedTarget;
            }
        }
        return com.dddgn.alice.protection.BlockBreakSafety.refusal(bot, pos, grant.reason());
    }

    /**
     * 该方块能否被本 bot 破坏。**策略由授权里的理由派生**（D-082）：
     * `EXPECTED_TARGET/DESCEND_FOOT/BULK_EDIT` 走明确目标策略，其余走更保守的清障策略。
     */
    public static boolean breakable(ServerPlayer bot, ServerLevel level, BlockPos pos, WriteGrant grant) {
        // 执行期写入预算（D-106）：任务级破坏预算用满后，**搜索与执行同时**不再把破坏当选项
        // （规划期与执行期同一个判据，避免"计划说能过、执行到一半才被拒"）
        if (!WriteBudget.breakAllowed(bot, grant)) {
            return false;
        }
        return breakRefusal(bot, level, pos, grant) == null;
    }

    /**
     * 破坏该方块的预计 tick 数（对照 Baritone {@code MovementHelper.getMiningDurationTicks}）。
     * 用于规划期成本，不修改世界。
     *
     * <p>⭐ **`D-385`（2026-09-21）：补上 vanilla 的两项"状态惩罚"**。
     *
     * <p><b>事实（可核，javap 实测 1.20.1 官方映射字节码）</b>：执行侧每 tick 累加
     * `BlockState.getDestroyProgress`（{@code BlockBreakSession.java:100}），它的分子
     * `Player.getDigSpeed` 里带两项除法 ——
     * <pre>
     *   isEyeInFluid(WATER) &amp;&amp; !EnchantmentHelper.hasAquaAffinity(玩家) ⇒ f /= 5.0f
     *   !onGround()                                                 ⇒ f /= 5.0f
     * </pre>
     * 而**原式只等于「站在地上 + 眼不在水里」那一档** ⇒ 眼在水里时估计乐观 **5×**、
     * 眼在水里**且**离地（水下挖矿 / 落体挖）时乐观 **25×** ⇒ 规划器把水下挖掘当陆地速度
     * ⇒ **该放不放、过度挖**（不止逃生：任何水边/水下挖矿都欠估）。
     *
     * <p><b>为什么写在这里</b>：规划与执行共用这一个函数 ⇒ **唯一来源**，不产生第二份口径；
     * ⚠️ 这不是"水里优先放置"的特判，只是把 vanilla 的公式补全 ⇒ 规划器**自然**偏向放置。
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
        // `D-385`：惩罚**先乘**（与 vanilla 同序：先除挖掘速度、再累加进度），再取 `max(1, …)` 下限
        // ⇒ 极软方块也不会把惩罚吃掉。
        double ticks = seconds * 20.0D * stateBreakPenaltyMultiplier(bot);
        return Math.max(1.0D, ticks);
    }

    /**
     * vanilla `Player.getDigSpeed` 的**状态惩罚倍数**（`D-385`；唯一定义处 —— 别在别处再抄一份）。
     *
     * <p>对照 Baritone：`MovementHelper.getMiningDurationTicks:580-606` **同样**只算"站在地上、眼不在水里"
     * 那一档（Baritone 靠 `canPlaceAgainst` 之类回避水下挖掘，Alice 不回避 ⇒ 必须补这两项）。
     *
     * <p>谓词与常数都直接来自 vanilla（`isEyeInFluid` / `EnchantmentHelper.hasAquaAffinity` /
     * `onGround`），判据见电池步 `mining_water_break_cost`：它把本函数的输出与 vanilla 的
     * `1.0F / BlockState.getDestroyProgress`（= 执行侧真正的每 tick 进度）逐状态比对。
     *
     * <p>⚠️ **两处与 vanilla 字面不同，都是为了"规划期提问时刻 ≠ 破坏时刻"**：
     * <ol>
     *   <li><b>离地那一项用「脚下有没有耐久支撑」而不是单看 `onGround()`</b>（见正文注释的证据：
     *       传送到场景起点那一 tick，bot 站在石头上而标志位是 false ⇒ 全图破坏边误罚 5×）；</li>
     *   <li><b>用的是 bot 的当前状态，不是"破坏发生时的状态"</b> —— 搜索里被估值的边可能离 bot 很远
     *       （当前在水里 ⇒ 远处干燥墙的破坏边也会 ×5）。这是已知近似，回收条件见 `D-385` §六。</li>
     * </ol>
     *
     * @return 1（陆地且在地面/有支撑）· 5（眼在水里 XOR 离地）· 25（眼在水里且离地）
     */
    private static double stateBreakPenaltyMultiplier(ServerPlayer bot) {
        if (bot == null) {
            return 1.0D;
        }
        double multiplier = 1.0D;
        if (bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)
                && !net.minecraft.world.item.enchantment.EnchantmentHelper.hasAquaAffinity(bot)) {
            multiplier *= 5.0D;
        }
        // ⚠️ **Alice 主动偏离 vanilla**（见方法注释的证据）：vanilla 只看 `onGround()` 这一个**标志位**，
        // 而规划期的提问时刻可能比真正的破坏早很多 tick（整条边在搜索里被估值时 bot 还在起点），
        // 标志位是**上一 tick 的滞后状态**（真机实测：传送到场景起点那一 tick，
        // bot 明明**站在石头上**（脚下=Stone）而 `onGround=false` ⇒ 全图破坏边被误罚 5×
        // ⇒ CORE `break_course` 从「破墙过去 52 tick」翻成「搭柱翻墙 65 tick」）。
        // ⇒ Alice 只在「**没有耐久支撑**」时才收这一项：那才是"破坏时仍会在空中"的可预测事实
        //（水中浮着 ⇒ 脚下是水 ⇒ 照收 ✓）。
        if (!bot.onGround() && !hasSupportBelow(bot)) {
            multiplier *= 5.0D;
        }
        return multiplier;
    }

    /**
     * 脚下有没有**耐久支撑**（实心、非流体、有碰撞形状）——`D-385` 里"离地"那一半的判据。
     *
     * <p>为什么不用 `bot.onGround()` 单独判：那是**标志位**，由上一次 `move()` 写入，
     * 传送/生成/落体当 tick 都可能是陈旧的（证据见 {@link #stateBreakPenaltyMultiplier}）。
     * 几何事实（脚下那一格是什么）与"破坏发生时会不会站在地上"才是同一件事。
     */
    private static boolean hasSupportBelow(ServerPlayer bot) {
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        return isSolidForPlacement(bot.serverLevel(), foot.below());
    }

    /**
     * 开启一个按 tick 推进的破坏会话（推荐路径）；登记审计。
     *
     * <p>**执行期写入预算闸门（D-106）**：这是唯一实际发生破坏的入口，因此预算判定放在这里
     * ——被拒时**不写世界、不登记审计、不开会话**，返回 {@code null}，调用方必须处理
     * （内核路径统一映射为 {@code WRITE_BUDGET_EXHAUSTED} 失败码）。
     */
    public static BlockBreakSession beginBreak(ServerPlayer bot, ServerLevel level, BlockPos pos,
                                              WriteGrant grant) {
        // ⭐ `D-362`：**最后一道闸门**也要拦"清障吃任务目标"——这是真正写世界的那一步，
        // 不能只指望所有调用点都记得先问 `breakable`（那种"靠调用点自觉"的守卫迟早漏一处）。
        if (grant != null && grant.reason() == WriteReason.PATH_ACCESS) {
            String protectedTarget = TaskTargetProtection.refusalFor(bot, pos);
            if (protectedTarget != null) {
                BotLog.warn("[WRITE-REFUSED] break pos={} by={} reason={}（清障不得吃掉任务目标；"
                                + "对照 Baritone MovementHelper.avoidBreaking:68）",
                        pos.toShortString(), grant.describe(), protectedTarget);
                // ⭐ `RC3`：这一格若"带着数据"（容器/方块实体/流体），把它记成**被拦下的不可逆写入**
                // （⇒ 闭合读数里的 `lossy=+0` 才有"没弄丢东西"的含义；非不可逆方块上是空操作）。
                com.dddgn.alice.ledger.WorldModLedger.recordLossyRefusal(level, pos,
                        level.getBlockState(pos), protectedTarget, grant.describe());
                return null;
            }
        }
        if (WriteBudget.consumeBreak(bot, level, pos, grant) == WriteBudget.Verdict.REFUSED) {
            BotLog.warn("[WRITE-REFUSED] break pos={} by={} reason=write_budget_exhausted {}",
                    pos.toShortString(), grant == null ? "-" : grant.describe(), WriteBudget.describe(bot));
            com.dddgn.alice.ledger.WorldModLedger.recordLossyRefusal(level, pos,
                    level.getBlockState(pos), WriteBudget.EXHAUSTED_CODE,
                    grant == null ? "unknown" : grant.describe());
            return null;
        }
        WriteAudit.breakWrite(level, pos, level.getBlockState(pos), grant);
        return BlockBreakSession.begin(bot, level, pos, grant);
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
        if (!WriteBudget.placeAllowed(bot)) {
            WriteBudget.notePlaceRefusal(bot, pos, grant);
            BotLog.warn("[WRITE-REFUSED] bulk_place pos={} by={} reason=write_budget_exhausted {}",
                    pos.toShortString(), grant == null ? "-" : grant.describe(), WriteBudget.describe(bot));
            return false;
        }
        String protectedReason = com.dddgn.alice.protection.ZoneAuthority.regionRefusal(level, bot.getUUID(), pos,
                com.dddgn.alice.protection.SafeZoneData.get(level.getServer()).protectionReason(level, pos),
                grant == null ? null : grant.reason(), com.dddgn.alice.protection.ZoneAuthority.Act.PLACE);
        if (protectedReason != null) {
            BotLog.warn("[WRITE-REFUSED] place pos={} by={} reason={}",
                    pos.toShortString(), grant.describe(), protectedReason);
            return false;
        }
        // D-326：**第三方保护层**。本方法是世界底层写入（不触发 Forge 放置事件）⇒ FTB 认领看不见它
        // （门禁实测：别人队伍的认领内批量放置会成功且世界真的被改）⇒ 写之前得**主动问一句**。
        String thirdParty = com.dddgn.alice.protection.ThirdPartyProtection.refusalReason(bot, pos);
        if (thirdParty != null) {
            BotLog.warn("[WRITE-REFUSED] place pos={} by={} reason={}",
                    pos.toShortString(), grant.describe(), thirdParty);
            return false;
        }
        BlockState previousState = level.getBlockState(pos);
        WriteAudit.placeWrite(level, pos, state, grant);
        level.setBlock(pos, state, 3);
        com.dddgn.alice.ledger.WorldModLedger.recordPlacement(level, bot.getUUID(), grant, pos,
                previousState, state);
        com.dddgn.alice.protection.TaskZoneRegistry.recordZonePlacement(level, bot.getUUID(), pos);
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
        if (WriteBudget.consumeBreak(bot, level, pos, grant) == WriteBudget.Verdict.REFUSED) {
            BotLog.warn("[WRITE-REFUSED] bulk_break pos={} by={} reason=write_budget_exhausted {}",
                    pos.toShortString(), grant == null ? "-" : grant.describe(), WriteBudget.describe(bot));
            return false;
        }
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
        // D-326：第三方保护层（`Level.destroyBlock` 不触发 Forge 破坏事件 ⇒ FTB 认领看不见这里）
        String thirdParty = com.dddgn.alice.protection.ThirdPartyProtection.refusalReason(bot, pos);
        if (thirdParty != null) {
            BotLog.warn("[WRITE-REFUSED] break pos={} by={} reason={}",
                    pos.toShortString(), grant.describe(), thirdParty);
            return false;
        }
        BlockState before = level.getBlockState(pos);
        boolean destroyed = level.destroyBlock(pos, dropItems, bot);
        BlockState after = level.getBlockState(pos);
        // ⭐ D-323：与单方块破坏会话同一条判据 —— **世界没变就不算破坏过**（同样不许谎报成功）。
        // 边界：`before` 本来就是空气（道路施工重复扫到空格）时 `destroyBlock` 返回 false 但**不算拒绝**
        // （幂等成功）——那是"无事可做"，不是"被拦下"。
        if (after == before && !before.isAir()) {
            BotLog.warn("[WRITE-REFUSED] bulk_break pos={} by={} reason=world_unchanged"
                            + "（destroyBlock={} 方块仍是 {}）",
                    pos.toShortString(), grant == null ? "-" : grant.describe(), destroyed,
                    before.getBlock().getName().getString());
            // ⭐ `RC4`：这条路上预算也是**事前**扣的（`consumeBreak` 在 617 行）⇒ 世界没变就退回，
            // 与 `BlockBreakSession.fail(...)` 同一条原则（"没发生的写入不许留在账上"）。
            WriteBudget.refundBreak(bot, pos, grant, "world_unchanged");
            return false;
        }
        WriteAudit.breakWrite(level, pos, before, grant);
        // ⭐ `RC3`：批量破坏是**另一条真的改世界的路**（`level.destroyBlock`）⇒ 同一套不可逆记账。
        com.dddgn.alice.ledger.WorldModLedger.recordLossyWrite(level, pos, before,
                grant == null ? "bulk-edit" : grant.describe());
        return true;
    }
}
