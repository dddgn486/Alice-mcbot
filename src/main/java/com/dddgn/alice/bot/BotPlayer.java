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

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
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
        // ✅ 击退修复：恢复被清空的击退速度
        if (needRestoreKnockback) {
            this.setDeltaMovement(savedKnockbackVelocity);
            needRestoreKnockback = false;
        }
        
        // 1. 设置输入
        controller.onUpdate();
        
        // 2. 原版逻辑
        super.tick();
        
        // 3. 物理计算
        this.aiStep();
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
