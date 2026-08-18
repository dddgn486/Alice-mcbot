package com.dddgn.alice.client.render;

import com.dddgn.alice.client.ClientTargetState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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
 * <p>在 {@link RenderLevelStageEvent} AFTER_PARTICLES 阶段画线框盒子
 * (注:1.20.1 Forge patch 中 Stage.AFTER_LEVEL 永不触发,最晚可用阶段是
 * AFTER_PARTICLES——粒子之后、天气之前):
 * <ul>
 *   <li>方块目标:固定 AABB,亮绿色;</li>
 *   <li>实体/掉落物目标:实时取实体 AABB,亮红色。</li>
 * </ul>
 * <b>透视关键</b>:不能直接用 {@code RenderType.lines()}——它的渲染状态自带
 * 深度测试(LEQUAL),会在 endBatch 绘制时覆盖全局 disableDepthTest,
 * 导致线框被方块遮挡(不透视)。这里自定义 RenderType 关闭深度测试。
 * poseStack 在该阶段为「相机旋转矩阵(无平移)」,故手动 translate(-camera)。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT)
public final class TargetOutlineRenderer {

    /** 无深度测试的线框 RenderType(透视高亮核心)。
     * 状态常量在 RenderStateShard 中是 protected,需子类访问。 */
    private static final RenderType OUTLINE_LINES = OutlineRenderType.create();

    /** 继承 RenderType 以访问 protected 状态常量。 */
    @OnlyIn(Dist.CLIENT)
    private static final class OutlineRenderType extends RenderType {
        private OutlineRenderType(String name, VertexFormat format, VertexFormat.Mode mode,
                                  int bufferSize, boolean hasCrumbling, boolean needsSorting,
                                  Runnable setupTask, Runnable clearTask) {
            super(name, format, mode, bufferSize, hasCrumbling, needsSorting, setupTask, clearTask);
        }

        static RenderType create() {
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    // 默认 1px 线条隔着多层方块几乎不可见；固定 3px 提高透视可读性。
                    .setLineState(new LineStateShard(java.util.OptionalDouble.of(3.0D)))
                    .setLayeringState(NO_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    // 透视必须同时禁止深度测试与深度写入；COLOR_WRITE 保证不会污染后续世界渲染。
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setLightmapState(NO_LIGHTMAP)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false);
            return RenderType.create("alice_outline", DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.LINES, 256, false, false, state);
        }
    }

    private TargetOutlineRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
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
            // 稍微外扩，避免线框与方块表面共面导致远距离闪烁/被吞掉。
            box = new AABB(ClientTargetState.blockPos()).inflate(0.003D);
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

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(OUTLINE_LINES);
        LevelRenderer.renderLineBox(pose, vc, box, r, g, b, pulse);
        // RenderType 会在 endBatch 内设置状态；显式关闭深度测试作为 Forge 后处理状态的兜底，
        // 并在结束后恢复，避免影响后续的云层/天气渲染。
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        buffers.endBatch(OUTLINE_LINES);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();

        pose.popPose();
    }
}
