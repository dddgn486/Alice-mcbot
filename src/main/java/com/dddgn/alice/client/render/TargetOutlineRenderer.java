package com.dddgn.alice.client.render;

import com.dddgn.alice.client.ClientTargetState;
import com.dddgn.alice.client.ClientRoadState;
import com.dddgn.alice.road.RoadPlan;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
        if (!ClientTargetState.isActive() && !ClientRoadState.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        if (ClientRoadState.isActive()) {
            renderRoad(mc, event);
        }
        if (!ClientTargetState.isActive()) {
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

    private static void renderRoad(Minecraft mc, RenderLevelStageEvent event) {
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(OUTLINE_LINES);
        for (RoadPlan.Cell cell : ClientRoadState.cells()) {
            BlockPos pos = cell.pos();
            // 只绘制没有相邻体素的外露面；相邻体素的公共面完全不入缓冲区。
            drawExposedFaces(pose, vc, pos, 0.15F, 0.65F, 1.0F, 0.9F);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        buffers.endBatch(OUTLINE_LINES);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        pose.popPose();
    }

    private static void drawExposedFaces(PoseStack pose, VertexConsumer vc, BlockPos pos,
                                         float r, float g, float b, float a) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        double e = 0.01D;
        int[][] faces = {
                {-1, 0, 0, 0}, {1, 0, 0, 1}, {0, -1, 0, 2},
                {0, 1, 0, 3}, {0, 0, -1, 4}, {0, 0, 1, 5}
        };
        for (int[] face : faces) {
            if (ClientRoadState.contains(pos.offset(face[0], face[1], face[2]))) continue;
            float[][] corners = faceCorners(x, y, z, face[3], e);
            int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}};
            for (int[] edge : edges) {
                float[] p = corners[edge[0]], q = corners[edge[1]];
                vc.vertex(pose.last().pose(), p[0], p[1], p[2]).color(r, g, b, a).endVertex();
                vc.vertex(pose.last().pose(), q[0], q[1], q[2]).color(r, g, b, a).endVertex();
            }
        }
    }

    private static float[][] faceCorners(double x, double y, double z, int face, double e) {
        float x0 = (float) (x - e), x1 = (float) (x + 1 + e);
        float y0 = (float) (y - e), y1 = (float) (y + 1 + e);
        float z0 = (float) (z - e), z1 = (float) (z + 1 + e);
        return switch (face) {
            case 0 -> new float[][] {{x0,y0,z0},{x0,y1,z0},{x0,y1,z1},{x0,y0,z1}};
            case 1 -> new float[][] {{x1,y0,z1},{x1,y1,z1},{x1,y1,z0},{x1,y0,z0}};
            case 2 -> new float[][] {{x0,y0,z1},{x1,y0,z1},{x1,y0,z0},{x0,y0,z0}};
            case 3 -> new float[][] {{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}};
            case 4 -> new float[][] {{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},{x0,y0,z0}};
            default -> new float[][] {{x0,y0,z1},{x0,y1,z1},{x1,y1,z1},{x1,y0,z1}};
        };
    }
}
