package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;

/**
 * Bot 动作控制器（借鉴 mc_aiplayer 的 ActionPack 设计）。
 * 
 * <h3>核心设计原则</h3>
 * <p>与 mc_aiplayer 一致：<b>只设置输入字段，不直接调用 travel()</b></p>
 * <ul>
 *   <li>BotController.onUpdate() 在每 tick 开始前被调用，设置输入字段</li>
 *   <li>原版 ServerPlayer.tick() 会读取这些字段并调用 travel()</li>
 *   <li>避免 travel() 被重复调用的问题</li>
 * </ul>
 * 
 * <h3>与旧的 SoftMovementPrimitive 的区别</h3>
 * <pre>
 * ❌ 旧方案：直接调用 travel()
 * bot.travel(new Vec3(0, 0, 1));  // 问题：与 ServerPlayer.tick() 重复调用
 * 
 * ✅ 新方案：只设置输入
 * controller.setForward(1.0F);    // 设置输入
 * // ServerPlayer.tick() 会自动调用 travel()
 * </pre>
 * 
 * @see <a href="mc_aiplayer">io.github.zoyluo.aibot.action.ActionPack</a>
 */
public final class BotController {
    
    private final BotPlayer bot;
    
    // ==================== 输入状态 ====================
    
    /** 前后移动输入 [-1.0, 1.0]，正值向前，负值向后 */
    private float forward = 0.0F;
    
    /** 左右平移输入 [-1.0, 1.0]，正值向右，负值向左 */
    private float strafing = 0.0F;
    
    /** 是否潜行 */
    private boolean sneaking = false;
    
    /** 是否疾跑 */
    private boolean sprinting = false;
    
    /** 是否长按跳跃键 */
    private boolean jumping = false;
    
    /** 单次跳跃剩余 tick 数（用于拟人化跳跃） */
    private int jumpTicks = 0;
    
    // ==================== 构造函数 ====================
    
    public BotController(BotPlayer bot) {
        this.bot = bot;
    }
    
    // ==================== 核心更新方法 ====================
    
    /**
     * 在每 tick 开始前调用，将输入状态同步到 ServerPlayer 的字段。
     * 
     * <p><b>重要</b>：必须在 ServerPlayer.tick() 之前调用，
     * 因为 tick() 内部会读取这些字段并调用 travel()。
     * 
     * <p>调用位置：BotPlayer.tick() 的第一行
     */
    public void onUpdate() {
        // 1. 计算速度系数（潜行减速）
        float velocity = sneaking ? 0.3F : 1.0F;
        
        // 2. 设置移动输入（zza = 前后，xxa = 左右）
        bot.zza = forward * velocity;
        bot.xxa = strafing * velocity;
        
        // 3. 设置跳跃状态（长按或单次跳跃）
        boolean jumpNow = jumping || jumpTicks > 0;
        bot.setJumping(jumpNow);
        
        // 4. 单次跳跃计数递减
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }
    
    // ==================== 移动控制接口 ====================
    
    /**
     * 设置前后移动输入。
     * 
     * @param value [-1.0, 1.0]，正值向前，负值向后
     */
    public void setForward(float value) {
        this.forward = clampInput(value);
    }
    
    /**
     * 设置左右平移输入。
     * 
     * @param value [-1.0, 1.0]，正值向右，负值向左
     */
    public void setStrafing(float value) {
        this.strafing = clampInput(value);
    }
    
    /**
     * 设置潜行状态。
     * 
     * @param sneaking 是否潜行
     */
    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        bot.setShiftKeyDown(sneaking);
        
        // 潜行和疾跑互斥
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }
    
    /**
     * 设置疾跑状态。
     * 
     * @param sprinting 是否疾跑
     */
    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        bot.setSprinting(sprinting);
        
        // 潜行和疾跑互斥
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }
    
    /**
     * 设置跳跃状态（长按）。
     * 
     * <p>注意：一般不推荐使用长按跳跃，推荐使用 jumpOnce() 实现拟人化跳跃。
     * 
     * @param jumping 是否长按跳跃键
     */
    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }
    
    /**
     * 单次跳跃（拟人化，推荐）。
     * 
     * <p>设计原理（借鉴 mc_aiplayer）：
     * <ul>
     *   <li>❌ 旧方案：setJumping(true) 长按跳键 → 落地后连跳（兔子跳），不自然</li>
     *   <li>✅ 新方案：jumpOnce() 持续 2 tick → 一个台阶只跳一次，更拟人化</li>
     * </ul>
     */
    public void jumpOnce() {
        this.jumpTicks = 2;  // 持续 2 tick
        BotLog.info("controller_jump_once bot={}", bot.getName().getString());
    }
    
    /**
     * 停止所有移动。
     * 
     * <p>清除所有输入状态，包括移动、跳跃、潜行、疾跑。
     */
    public void stopMovement() {
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        this.sneaking = false;
        this.sprinting = false;
        
        bot.setJumping(false);
        bot.setShiftKeyDown(false);
        bot.setSprinting(false);
        
        BotLog.info("controller_stop_movement bot={}", bot.getName().getString());
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 是否有任何活跃的移动输入。
     * 
     * @return true 如果有任何非零输入
     */
    public boolean hasActiveMovement() {
        return forward != 0.0F
                || strafing != 0.0F
                || jumping
                || jumpTicks > 0
                || sneaking
                || sprinting;
    }
    
    /**
     * 获取当前前后输入。
     */
    public float getForward() {
        return forward;
    }
    
    /**
     * 获取当前左右输入。
     */
    public float getStrafing() {
        return strafing;
    }
    
    /**
     * 是否正在潜行。
     */
    public boolean isSneaking() {
        return sneaking;
    }
    
    /**
     * 是否正在疾跑。
     */
    public boolean isSprinting() {
        return sprinting;
    }
    
    /**
     * 是否正在跳跃。
     */
    public boolean isJumping() {
        return jumping || jumpTicks > 0;
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 限制输入值在 [-1.0, 1.0] 范围内。
     */
    private static float clampInput(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
    
    // ==================== 调试信息 ====================
    
    /**
     * 获取当前输入状态的字符串表示（用于调试）。
     */
    public String getInputStateString() {
        return String.format("BotController[forward=%.2f strafing=%.2f sneaking=%s sprinting=%s jumping=%s jumpTicks=%d]",
                forward, strafing, sneaking, sprinting, jumping, jumpTicks);
    }
}
