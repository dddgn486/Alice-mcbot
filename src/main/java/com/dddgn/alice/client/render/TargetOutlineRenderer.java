package com.dddgn.alice.client.render;

import com.dddgn.alice.client.ClientTargetState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 任务目标透视高亮(客户端测试效果)。
 * <p>在 {@link RenderLevelStageEvent} LAST 阶段画线框盒子:
 * <ul>
 *   <li>方块目标:固定 AABB,亮绿色;</li>
 *   <li>实体/掉落物目标:实时取实体 AABB,亮红色。</li>
 * </ul>
 * 画线框前关闭深度测试 → 透过墙体也能看到(透视),测试期方便跟踪 bot 任务目标。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT)
public final class TargetOutlineRenderer {

    private TargetOutlineRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (!ClientTargetState.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        AABB box;
        float r, g, b;
        if (ClientTargetState.type() == 0) {
            if (ClientTargetState.blockPos() == null) {
                return;
            }
            box = new AABB(ClientTargetState.blockPos());
            r = 0.2F;
            g = 1.0F;
            b = 0.2F;
        } else {
            Entity entity = level.getEntity(ClientTargetState.entityId());
            if (entity == null) {
                return;
            }
            box = entity.getBoundingBox().inflate(0.1D);
            r = 1.0F;
            g = 0.3F;
            b = 0.3F;
        }

        // 呼吸效果:透明度随时间轻微脉动,更醒目
        float pulse = 0.7F + 0.3F * Mth.sin((float) (level.getGameTime() % 40L) / 40.0F * (float) Math.PI * 2.0F);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        Vec3 cam = event.getCamera().getPosition();
        pose.translate(-cam.x, -cam.y, -cam.z);

        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, vc, box, r, g, b, pulse);
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();

        pose.popPose();
    }
}
