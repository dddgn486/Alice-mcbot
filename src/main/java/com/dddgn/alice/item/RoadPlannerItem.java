package com.dddgn.alice.item;

import com.dddgn.alice.road.RoadPlan;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.RoadPlanPacket;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** 数学道路蓝图工具：右键两端方块，Shift+右键清空选择。 */
public final class RoadPlannerItem extends Item {
    public RoadPlannerItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            RoadPlan.get().reset();
            AliceNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new RoadPlanPacket(false, java.util.List.of()));
            context.getPlayer().sendSystemMessage(Component.literal("[alice] 道路蓝图已重置"));
            return InteractionResult.SUCCESS;
        }
        BlockPos selected = context.getClickedPos();
        RoadPlan plan = RoadPlan.get();
        boolean hadFirst = plan.first() != null;
        boolean complete = plan.select(serverLevel, selected);
        AliceNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                new RoadPlanPacket(complete, plan.cells()));
        if (context.getPlayer() != null) {
            if (complete) {
                context.getPlayer().sendSystemMessage(Component.literal(
                        "[alice] 道路蓝图已生成: " + plan.cells().size() + " 个体素"));
            } else if (hadFirst) {
                context.getPlayer().sendSystemMessage(Component.literal(
                        "[alice] 道路生成失败: " + plan.lastFailureReason()));
            } else {
                context.getPlayer().sendSystemMessage(Component.literal(
                        "[alice] 道路起点已选择: " + selected.toShortString()));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
