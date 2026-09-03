package com.dddgn.alice.bot;

/**
 * BotController 使用示例和测试。
 * 
 * <p>演示如何使用新的输入驱动架构替代旧的直接调用 travel() 方式。
 */
public final class BotControllerExample {
    
    private BotControllerExample() {
    }
    
    /**
     * 示例1：简单的前进移动。
     * 
     * <pre>
     * ❌ 旧方案（SoftMovementPrimitive）：
     * bot.travel(new Vec3(0, 0, 1));  // 直接调用 travel()
     * 
     * ✅ 新方案（BotController）：
     * bot.controller().setForward(1.0F);  // 只设置输入
     * // ServerPlayer.tick() 会自动处理
     * </pre>
     */
    public static void exampleMoveForward(BotPlayer bot) {
        // 设置向前移动
        bot.controller().setForward(1.0F);
        
        // 就这样！不需要调用 travel()
        // BotPlayer.tick() 会在下一个 tick 调用 controller.onUpdate()
        // 然后 super.tick() 会读取输入并调用 travel()
    }
    
    /**
     * 示例2：跳跃（拟人化单次跳跃）。
     * 
     * <pre>
     * ❌ 旧方案：
     * bot.setJumping(true);  // 长按，导致兔子跳
     * 
     * ✅ 新方案：
     * bot.controller().jumpOnce();  // 单次跳跃，更自然
     * </pre>
     */
    public static void exampleJump(BotPlayer bot) {
        // 单次跳跃（持续 2 tick）
        bot.controller().jumpOnce();
        
        // 跳跃会在接下来的 2 个 tick 内生效
        // 落地后自动停止，不会连跳
    }
    
    /**
     * 示例3：停止移动。
     * 
     * <pre>
     * ❌ 旧方案：
     * bot.setDeltaMovement(Vec3.ZERO);  // 直接清除速度
     * bot.xxa = 0;
     * bot.zza = 0;
     * 
     * ✅ 新方案：
     * bot.controller().stopMovement();  // 统一接口
     * </pre>
     */
    public static void exampleStopMovement(BotPlayer bot) {
        // 停止所有移动
        bot.controller().stopMovement();
        
        // 清除了所有输入：forward、strafing、jumping、sneaking、sprinting
    }
    
    /**
     * 示例4：潜行移动。
     */
    public static void exampleSneakForward(BotPlayer bot) {
        // 开启潜行
        bot.controller().setSneaking(true);
        
        // 设置前进（速度会自动减为 0.3 倍）
        bot.controller().setForward(1.0F);
        
        // 潜行移动中...
    }
    
    /**
     * 示例5：疾跑。
     */
    public static void exampleSprint(BotPlayer bot) {
        // 开启疾跑
        bot.controller().setSprinting(true);
        
        // 设置前进
        bot.controller().setForward(1.0F);
        
        // 疾跑中...
        // 注意：疾跑和潜行互斥，设置疾跑会自动关闭潜行
    }
    
    /**
     * 示例6：对角线移动。
     */
    public static void exampleDiagonalMove(BotPlayer bot) {
        // 同时设置前进和右移
        bot.controller().setForward(1.0F);
        bot.controller().setStrafing(1.0F);
        
        // 会向右前方 45° 移动
    }
    
    /**
     * 示例7：在任务中使用（模拟简单的 WalkToController）。
     */
    public static class SimpleWalkToTask {
        private final BotPlayer bot;
        private final double targetX;
        private final double targetZ;
        
        public SimpleWalkToTask(BotPlayer bot, double targetX, double targetZ) {
            this.bot = bot;
            this.targetX = targetX;
            this.targetZ = targetZ;
        }
        
        public boolean tick() {
            // 计算方向
            double dx = targetX - bot.getX();
            double dz = targetZ - bot.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            
            // 检查是否到达
            if (distance < 0.5) {
                bot.controller().stopMovement();
                return true;  // 完成
            }
            
            // 计算朝向
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bot.setYRot(yaw);
            bot.setYHeadRot(yaw);
            
            // ✅ 设置前进输入（不调用 travel）
            bot.controller().setForward(1.0F);
            
            // 如果前方有障碍，尝试跳跃
            if (shouldJump(bot)) {
                bot.controller().jumpOnce();
            }
            
            return false;  // 未完成
        }
        
        private boolean shouldJump(BotPlayer bot) {
            // TODO: 检查前方是否有台阶
            return false;
        }
    }
    
    /**
     * 示例8：状态查询。
     */
    public static void exampleQueryState(BotPlayer bot) {
        BotController controller = bot.controller();
        
        // 查询当前输入状态
        float forward = controller.getForward();
        float strafing = controller.getStrafing();
        boolean sneaking = controller.isSneaking();
        boolean sprinting = controller.isSprinting();
        boolean jumping = controller.isJumping();
        
        // 检查是否有任何活跃输入
        boolean hasMovement = controller.hasActiveMovement();
        
        // 获取调试字符串
        String debugInfo = controller.getInputStateString();
        System.out.println(debugInfo);
    }
}
