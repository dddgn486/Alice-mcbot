package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Alice 假人实体:直接继承 {@link ServerPlayer}(mc_aiplayer 同款方案)。
 * <p>
 * 相比旧实现(Forge FakePlayer,无网络连接 → 客户端不可见 + tick 部分路径 NPE),
 * 玩家化假人通过 {@link BotManager#spawn} 里的
 * {@code PlayerList.placeNewPlayer(伪造Connection, this)} 注册:
 * <ul>
 *   <li>connection 字段被 PlayerList 自动填充 → tick 无 NPE(替代旧 NPE 吞异常);</li>
 *   <li>进入 PlayerList → 广播给所有玩家 → <b>客户端可见</b>(解决审查点 R6);</li>
 *   <li>移动/物理/交互全部走原版玩家逻辑。</li>
 * </ul>
 * ⚠️ 审查点 R9:假人被视为真实玩家(占服务器人数、名字需唯一、下线需从 PlayerList 移除);
 * 多人服的权限与审计约束后续里程碑处理。
 * 
 * <p><b>BotController 集成</b>（借鉴 mc_aiplayer ActionPack）：
 * <ul>
 *   <li>BotController 负责管理输入状态（forward、strafing、jumping）</li>
 *   <li>在 tick() 开始前调用 controller.onUpdate()，设置输入字段</li>
 *   <li>super.tick() 会读取这些字段并调用 travel()</li>
 *   <li>避免了直接调用 travel() 导致的重复调用问题</li>
 * </ul>
 */
public class BotPlayer extends ServerPlayer {

    private BotController controller;
    
    // ✅ 击退修复：保存击退速度，下一个 tick 恢复
    private Vec3 savedKnockbackVelocity = Vec3.ZERO;
    private boolean needRestoreKnockback = false;
    private int physicsProbeTicks;
    private int physicsTravelCalls;
    /**
     * 实体 tick 累计次数（进程内单调递增，2026-09-13 D-174）。
     *
     * <p>用途：段卡死诊断里区分"**实体根本没被 tick**"（输入设了也没人消费）与"tick 了但没位移"
     * —— 这两类原因的修法完全不同。只作为只读诊断量，不参与任何逻辑判断。
     */
    private long entityTickCount;
    /** `travel()` 累计调用次数（同上；正常每 tick 1~2 次 —— 本类 `tick()` 会显式再调一次 `aiStep()`）。 */
    private long travelInvocationCount;

    /** 累计实体 tick 次数（只读诊断量）。 */
    public long entityTickCount() {
        return entityTickCount;
    }

    /** 累计 `travel()` 调用次数（只读诊断量）。 */
    public long travelInvocationCount() {
        return travelInvocationCount;
    }

    /** 上一次同步"玩家区块票"时所在的 {@link net.minecraft.core.SectionPos}；null = 还没同步过。 */
    private net.minecraft.core.SectionPos lastTicketSection;
    /** 玩家票同步次数（只读诊断量；用于区分"没同步过"与"同步了但依然冻结"）。 */
    private long chunkTicketSyncs;

    /** 玩家票同步次数（只读诊断量）。 */
    public long chunkTicketSyncs() {
        return chunkTicketSyncs;
    }

    /**
     * **让假人的玩家区块票跟随它自己**（T2 根因修复，2026-09-14）。
     *
     * <p><b>为什么必须有这个方法</b>：1.20.1 里玩家票（{@code TicketType.PLAYER}）**只由客户端上行移动包推进** ——
     * {@code ServerGamePacketListenerImpl.handleMovePlayer → ServerChunkCache.move → ChunkMap.move →
     * DistanceManager.removePlayer/addPlayer}。假人的连接是 {@link FakeConnection}（netty {@code EmbeddedChannel}）：
     * 既收不到移动包，也**不在 {@code ServerConnectionListener} 的连接表里**（{@code tick()} 永不被调用）。
     * ⇒ 票**冻结在"假人第一次被 track 时的位置"**，假人也就不再被实体 tick 表驱动
     * （{@code serverLevel().isPositionEntityTicking(foot) == false}），表现为
     * **"任务在跑、输入设了、bot 一格不动、且没有任何报错"**（{@code entityTicksInSegment=0}）。
     *
     * <p><b>证据</b>（无头 CORE 实测，2026-09-14）：47 条 {@code [Bot] entity_tick_missing … entityTicking=false
     * connTicks=0}；失败步骤与"距出生点 Chebyshev 距离"一一对应（出生点 {@code TicketType.START}
     * 只覆盖 d≤9），z≈300–420 的步骤全灭、≤8 格的全绿。客户端之所以一直没暴露：真人站在夹具区，
     * 他的 PLAYER 票把加载与 entity-ticking 都补上了 —— **绿是被真人掩盖的**。
     *
     * <p><b>为什么是这条链</b>：{@code ServerChunkCache.move(ServerPlayer)} 是 {@code public}，正是真人
     * {@code handleMovePlayer} 走的那一条 ⇒ 假人与真人在"持票"这件事上等价，而不是给无头开小灶。
     *
     * <p>只在 {@code SectionPos} 变化时调用：{@code ChunkMap.move} 会遍历被 track 的实体并走区块下发路径
     * （对假人而言包会被 {@link FakeConnection} 丢弃，但仍有序列化成本），每 tick 都调没有意义。
     *
     * @return 本次是否真的同步了（跨越了 16 格边界）
     */
    public boolean syncPlayerChunkTicket() {
        net.minecraft.core.SectionPos now = net.minecraft.core.SectionPos.of(blockPosition());
        if (now.equals(lastTicketSection)) {
            return false;
        }
        lastTicketSection = now;
        chunkTicketSyncs++;
        serverLevel().getChunkSource().move(this);
        return true;
    }

    /**
     * **传送感知（D-180，用户要求：只加报告，不改行为）**。
     *
     * <p>为什么需要：bot 被传送（`/tp`、夹具传送、任何 `teleportTo`）时，它自己与决策层**都无从得知**——
     * 2026-09-13 实测过后果：常驻伐木 Job 在 bot 被传送到 198 格外后仍照常作业（现已由 D-179 拦住）。
     * 这里只做**事实记录与上报**：计数 + 最近一次的 from/to/tick，并在决策事件环里留一条（不含任何自动动作）。
     */
    private int teleportCount;
    /** 其中**真的发生位移**的次数（原地"传送"是夹具复位，不算位移事件）。 */
    private int teleportDisplacementCount;
    private net.minecraft.core.BlockPos lastTeleportFrom;
    private net.minecraft.core.BlockPos lastTeleportTo;
    private long lastTeleportTick;
    private double lastTeleportDistance;

    /** 传送累计次数（只读）。 */
    public int teleportCount() {
        return teleportCount;
    }

    /** 最近一次传送的起点（未发生过为 null）。 */
    public net.minecraft.core.BlockPos lastTeleportFrom() {
        return lastTeleportFrom;
    }

    /** 最近一次传送的终点（未发生过为 null）。 */
    public net.minecraft.core.BlockPos lastTeleportTo() {
        return lastTeleportTo;
    }

    /** 最近一次传送发生的 server tick（未发生过为 -1）。 */
    public long lastTeleportTick() {
        return lastTeleportTick;
    }

    /** 最近一次传送的直线距离（未发生过为 0）。 */
    public double lastTeleportDistance() {
        return lastTeleportDistance;
    }

    /**
     * 记录一次传送（唯一入口：两个 `teleportTo` 重载都汇到这里）。
     *
     * <p>**区分"位移"与"原地复位"**：夹具常用 `teleportTo` 把 bot 摆回同格（实测 `distance=0.1`、
     * `from == to`），那种"传送"不是事实上的位移，若照样记日志/入事件环，报告里会出现
     * "bot 被传送 30,64,209 → 30,64,209" 这种噪声、反而掩盖真正需要解释的漂移。所以：
     * 全部计数；**只有位移 ≥1 格**才写日志 + 入决策事件环，并更新"最近位移"字段。
     */
    private void noteTeleport(double toX, double toY, double toZ) {
        net.minecraft.core.BlockPos from = this.blockPosition();
        net.minecraft.core.BlockPos to = net.minecraft.core.BlockPos.containing(toX, toY, toZ);
        teleportCount++;
        double distance = Math.sqrt(this.distanceToSqr(toX, toY, toZ));
        if (distance < 1.0D) {
            return;   // 原地复位：只计数（见上面的理由）
        }
        teleportDisplacementCount++;
        lastTeleportFrom = from;
        lastTeleportTo = to;
        lastTeleportTick = getServer() == null ? -1L : getServer().getTickCount();
        lastTeleportDistance = distance;
        BotLog.info("[Bot] teleported from={} to={} distance={} tick={} count={} (displacement={})",
                from.toShortString(), to.toShortString(),
                String.format(java.util.Locale.ROOT, "%.1f", distance),
                lastTeleportTick, teleportCount, teleportDisplacementCount);
        com.dddgn.alice.decision.DecisionEvents.record(this, "TELEPORT", "info",
                "bot 被传送 " + from.toShortString() + " → " + to.toShortString(),
                "distance=" + String.format(java.util.Locale.ROOT, "%.1f", distance)
                        + " tick=" + lastTeleportTick);
    }

    /** 发生**位移**的传送次数（原地复位不计；只读）。 */
    public int teleportDisplacementCount() {
        return teleportDisplacementCount;
    }

    @Override
    public boolean teleportTo(net.minecraft.server.level.ServerLevel level, double x, double y, double z,
                              java.util.Set<net.minecraft.world.entity.RelativeMovement> relatives,
                              float yaw, float pitch) {
        noteTeleport(x, y, z);
        return super.teleportTo(level, x, y, z, relatives, yaw, pitch);
    }

    @Override
    public void teleportTo(double x, double y, double z) {
        noteTeleport(x, y, z);
        super.teleportTo(x, y, z);
    }

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        // D-025：对齐真实玩家台阶物理。ServerPlayer 构造默认 setMaxUpStep(1.0F)
        // （字节码实证），会使假人直接踩上整格方块，而真实玩家（LocalPlayer，
        // LivingEntity 默认 0.6f）必须跳跃。为保持与真人/B aritone 参考语义一致，
        // 显式降为 0.6：一格方块成为真正的障碍，上升必须走跳跃路径。
        this.setMaxUpStep(0.6F);
        this.controller = new BotController(this);
    }

    /**
     * 获取 Bot 控制器。
     * 
     * @return BotController 实例
     */
    public BotController controller() {
        return controller;
    }

    /**
     * ✅ 关键修复：使 travel() 在服务端正常执行。
     * <p>
     * LivingEntity.travel() 入口检查：
     * <pre>
     * if (this.isEffectiveAi() || this.isControlledByLocalInstance()) {
     *     // 执行物理计算
     * } else {
     *     return; // ❌ 直接返回
     * }
     * </pre>
     * 
     * 服务端 ServerPlayer 默认值：
     * <ul>
     *   <li>isEffectiveAi() = false (Entity 默认)</li>
     *   <li>isControlledByLocalInstance() = level().isClientSide() = false</li>
     *   <li>结果：travel() 直接返回，不执行物理！</li>
     * </ul>
     * 
     * 解决方案：重写 isEffectiveAi() 返回 true，让服务端 Bot 执行物理计算。
     * <p>
     * ⚠️ 注意：ServerPlayer 不使用 Mob AI 系统（GoalSelector/TargetSelector），
     * 返回 true 只影响 travel() 入口检查，不会触发 AI 路径。
     */
    @Override
    public boolean isEffectiveAi() {
        return true;
    }
    
    @Override
    public void tick() {
        entityTickCount++;   // D-174：只读诊断量（段卡死时判断"实体到底有没有被 tick"）
        // ✅ 击退修复：恢复被清空的击退速度
        if (needRestoreKnockback) {
            this.setDeltaMovement(savedKnockbackVelocity);
            needRestoreKnockback = false;
        }

        boolean probe = physicsProbeTicks > 0;
        Vec3 beforePos = position();
        Vec3 beforeVelocity = getDeltaMovement();
        boolean beforeOnGround = onGround();
        physicsTravelCalls = 0;

        // 1. 设置输入
        controller.onUpdate();
        if (probe) {
            BotLog.info("[PhysicsProbe] tick={} stage=before_super input={} pos={} velocity={} onGround={}",
                    physicsProbeTicks, controller.getInputStateString(), fmt(beforePos), fmt(beforeVelocity), beforeOnGround);
        }

        // 2. Forge ServerPlayer 原版实体更新
        super.tick();
        if (probe) {
            BotLog.info("[PhysicsProbe] tick={} stage=after_super pos={} velocity={} onGround={}",
                    physicsProbeTicks, fmt(position()), fmt(getDeltaMovement()), onGround());
        }

        // 3. Bot 专用服务端物理推进
        this.aiStep();
        if (probe) {
            BotLog.info("[PhysicsProbe] tick={} stage=after_aiStep pos={} velocity={} onGround={} travelCalls={}",
                    physicsProbeTicks, fmt(position()), fmt(getDeltaMovement()), onGround(), physicsTravelCalls);
            physicsProbeTicks--;
        }
    }

    @Override
    public void travel(Vec3 travelVector) {
        physicsTravelCalls++;
        travelInvocationCount++;   // D-174：只读诊断量
        super.travel(travelVector);
    }

    /** 开启有限 tick 的物理观测，不改变 Bot 的物理调用顺序。 */
    public void startPhysicsProbe(int ticks) {
        physicsProbeTicks = Math.max(0, ticks);
        BotLog.info("[PhysicsProbe] started bot={} ticks={}", getName().getString(), physicsProbeTicks);
    }
    
    private String fmt(Vec3 v) {
        return String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }
    
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return super.hurt(source, amount);
    }
    
    @Override
    public void knockback(double strength, double x, double z) {
        super.knockback(strength, x, z);
        
        // ✅ 击退修复：保存击退速度，下一个 tick 恢复
        // 原因：ServerPlayer 在 tick 间隙会清空 deltaMovement（客户端同步机制）
        // 解决：在下一个 tick 开始时立即恢复击退速度
        savedKnockbackVelocity = this.getDeltaMovement();
        needRestoreKnockback = true;
    }
    
    @Override
    public net.minecraft.world.InteractionResult interactAt(net.minecraft.world.entity.player.Player player, 
                                                             net.minecraft.world.phys.Vec3 hitPos, 
                                                             net.minecraft.world.InteractionHand hand) {
        // 右键 bot 打开背包（仅服务端）
        if (!this.level().isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.dddgn.alice.gui.BotInventoryService.open(serverPlayer, this);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }
}
