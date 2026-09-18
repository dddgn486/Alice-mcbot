package com.dddgn.alice.client.gui;

import com.dddgn.alice.client.ClientProtectionState;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.ProtectionActionPacket;
import com.dddgn.alice.protection.ProtectionMapGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

/**
 * **保护区勾选界面**（D-314，保护区 2/2）：以玩家为中心的 N×N **区块**网格，左键认领 / 右键取消。
 *
 * <p>分工（`D-307`）：
 * <ul>
 *   <li><b>地形色 100% 客户端自采</b>：每格从自己的 {@link ClientLevel} 取 2×2 个采样点，
 *       从玩家高度上下 40 格窗口里找**第一个非空气方块**，取其 {@link MapColor} 求平均；
 *       区块没加载 ⇒ 灰色（**不假装知道**）。**不复用原版地图渲染器**（它要"谁是持有者"，
 *       且 scale=4 时 1 像素 = 1 区块 ⇒ 看不清建筑，`D-307`）。</li>
 *   <li><b>认领态只来自服务端</b>：{@link ClientProtectionState}。没有本维度快照时界面**拒绝编辑**
 *       并显示"等待服务端数据" —— 否则用户会在空网格上盲点右键 = **盲取消认领**（破坏性）。</li>
 *   <li><b>提交是一次性批包</b>：关闭界面（{@code removed()}，含被聊天界面顶掉）时把待提交
 *       按动作发 1~2 包（认领批 / 取消批）；发不出去（断线）就**留着**，下次打开还能提交。</li>
 * </ul>
 *
 * <p>键位：左键 = 认领 · 右键 = 取消 · 按住拖动 = 连选 · {@code R} = 回到玩家所在区块 · 关闭 = 提交。
 * <p>几何换算在 {@link ProtectionMapGeometry}（无客户端依赖 ⇒ 无头夹具能逐条断言"点到的格子对应哪个区块"）。
 */
@OnlyIn(Dist.CLIENT)
public class ProtectionMapScreen extends Screen {

    private static final int MARGIN = 10;
    private static final int HEADER_H = 28;
    private static final int FOOTER_H = 44;

    /** 未加载/采不到颜色时的底色（"不知道"要看得见，不能假装是地形）。 */
    private static final int UNKNOWN_COLOR = 0xFF2A2A2A;
    private static final int CLAIMED_TINT = 0x55FF4040;
    private static final int PENDING_CLAIM_TINT = 0x70FFC000;
    private static final int PENDING_UNCLAIM_TINT = 0x7080C0FF;
    private static final int GRID_LINE = 0x30FFFFFF;
    private static final int REGION_LINE = 0x66FFFFFF;
    private static final int OUTER_BORDER = 0xFF909090;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0B0B0;
    private static final int TEXT_WARN = 0xFFFFD060;

    /** 每格的采样点（区块内局部坐标 0..15）。 */
    private static final int[] SAMPLE_OFFSETS = {4, 12};

    private ProtectionMapGeometry geometry;
    private int[] terrain;
    private ResourceLocation terrainDimension;
    private int terrainCenterX = Integer.MIN_VALUE;
    private int terrainCenterZ = Integer.MIN_VALUE;
    private int terrainGrid = -1;

    private boolean dragging;
    private int draggingButton = -1;
    private boolean flushed;
    private int syncTicks;

    public ProtectionMapScreen() {
        super(Component.literal("Alice 保护区地图"));
    }

    // ==================== 布局 ====================

    @Override
    protected void init() {
        int availableWidth = Math.max(120, width - 2 * MARGIN);
        int availableHeight = Math.max(80, height - HEADER_H - FOOTER_H - 2 * MARGIN);
        int available = Math.min(availableWidth, availableHeight);
        int grid = ProtectionMapGeometry.fitGrid(available, 9);
        int cell = ProtectionMapGeometry.fitCell(available, grid);
        ChunkPos center = playerChunk();
        int size = grid * cell;
        geometry = new ProtectionMapGeometry(center.x, center.z, grid, cell,
                (width - size) / 2, HEADER_H + MARGIN + Math.max(0, (availableHeight - size) / 2));
        terrain = null;
        flushed = false;
        if (!ClientProtectionState.hasSnapshotFor(ClientProtectionState.currentDimension())) {
            ClientProtectionState.requestSync();
        }
    }

    private ChunkPos playerChunk() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? ChunkPos.ZERO : minecraft.player.chunkPosition();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 没有本维度快照时每 20 tick 自愈请求一次（服务端有 10 tick 限流）。 */
    @Override
    public void tick() {
        if (ClientProtectionState.hasSnapshotFor(ClientProtectionState.currentDimension())) {
            syncTicks = 0;
            return;
        }
        syncTicks++;
        if (syncTicks % 20 == 1) {
            ClientProtectionState.requestSync();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            this.init(minecraft, width, height);      // 回到玩家所在区块（网格可能已经跑偏）
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        ResourceLocation dimension = ClientProtectionState.currentDimension();
        if (geometry == null || dimension == null || minecraft == null || minecraft.level == null) {
            return;
        }

        graphics.drawString(font, title, MARGIN, 6, TEXT);
        graphics.drawString(font, "维度 " + dimension + " · " + geometry.describe(), MARGIN, 18, TEXT_DIM);

        if (!ClientProtectionState.hasSnapshotFor(dimension)) {
            graphics.drawCenteredString(font, "等待服务端认领数据…", width / 2, height / 2 - 10, TEXT_WARN);
            graphics.drawCenteredString(font, "（已自动请求；若一直不来，请关掉再开一次界面）",
                    width / 2, height / 2 + 6, TEXT_DIM);
            return;
        }

        ensureTerrain(dimension);
        renderGrid(graphics, mouseX, mouseY);
        renderFooter(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        Long hovered = geometry.keyAt(mouseX, mouseY);
        int cell = geometry.cell();
        int grid = geometry.grid();
        for (int row = 0; row < grid; row++) {
            for (int column = 0; column < grid; column++) {
                int x = geometry.cellLeft(column);
                int y = geometry.cellTop(row);
                long key = geometry.keyAt(column, row);
                graphics.fill(x, y, x + cell, y + cell, 0xFF000000 | terrain[row * grid + column]);

                Boolean pending = ClientProtectionState.pending(key);
                boolean claimed = ClientProtectionState.isClaimed(key);
                if (pending != null) {
                    graphics.fill(x, y, x + cell, y + cell, pending ? PENDING_CLAIM_TINT : PENDING_UNCLAIM_TINT);
                } else if (claimed) {
                    graphics.fill(x, y, x + cell, y + cell, CLAIMED_TINT);
                }
                if (claimed || (pending != null && pending)) {
                    graphics.renderOutline(x, y, cell, cell, pending != null ? TEXT_WARN : 0xFFFF6060);
                }
                if (hovered != null && hovered.longValue() == key) {
                    graphics.renderOutline(x, y, cell, cell, TEXT);
                }
            }
        }

        // 网格线（每 4 格一根亮的，便于数格子）＋ 外框
        for (int i = 0; i <= grid; i++) {
            int color = i % 4 == 0 ? REGION_LINE : GRID_LINE;
            int x = geometry.left() + i * cell;
            int y = geometry.top() + i * cell;
            graphics.fill(x, geometry.top(), x + 1, geometry.bottom(), color);
            graphics.fill(geometry.left(), y, geometry.right(), y + 1, color);
        }
        graphics.renderOutline(geometry.left() - 1, geometry.top() - 1, geometry.size() + 2, geometry.size() + 2,
                OUTER_BORDER);

        // 玩家所在区块（正中心那一格）
        int half = geometry.half();
        graphics.renderOutline(geometry.cellLeft(half) + 1, geometry.cellTop(half) + 1,
                Math.max(1, cell - 2), Math.max(1, cell - 2), TEXT);
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        Long hovered = geometry.keyAt(mouseX, mouseY);
        int y = geometry.bottom() + 4;
        String cursor = "光标：网格外";
        if (hovered != null) {
            boolean claimed = ClientProtectionState.isClaimed(hovered);
            Boolean pending = ClientProtectionState.pending(hovered);
            String state = pending != null ? (pending ? "待认领" : "待取消") : (claimed ? "已认领" : "未认领");
            cursor = "光标：区块 " + ChunkPos.getX(hovered) + ", " + ChunkPos.getZ(hovered) + " · " + state;
        }
        graphics.drawString(font, cursor, MARGIN, y, TEXT);
        graphics.drawString(font, "本视图已认领 " + ClientProtectionState.claimed().size() + " 格"
                + (ClientProtectionState.truncated() ? " · ⚠ 快照被截断（远端未显示）" : ""),
                MARGIN, y + 11, ClientProtectionState.truncated() ? TEXT_WARN : TEXT_DIM);
        graphics.drawString(font, "左键=认领 · 右键=取消 · 拖动=连选 · R=回到玩家 · 关闭即提交（待提交 "
                + ClientProtectionState.pendingCount() + "）", MARGIN, y + 22, TEXT_DIM);
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (!editable()) {
            return true;        // 没有服务端数据时明确吞掉点击，避免误落到别处
        }
        Long key = geometry.keyAt(mouseX, mouseY);
        if (key == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        dragging = true;
        draggingButton = button;
        applyEdit(key, button == 0);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging || button != draggingButton || geometry == null) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        Long key = geometry.keyAt(mouseX, mouseY);
        if (key != null) {
            applyEdit(key, draggingButton == 0);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == draggingButton) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean editable() {
        return geometry != null
                && ClientProtectionState.hasSnapshotFor(ClientProtectionState.currentDimension());
    }

    /**
     * 一次点击（或拖动经过）的语义：**目标是"与服务端相反"**；如果点回了服务端状态，
     * 就把这条待提交撤掉（不必白提交一次）。
     */
    private void applyEdit(long chunkKey, boolean claim) {
        ResourceLocation dimension = ClientProtectionState.currentDimension();
        if (dimension == null) {
            return;
        }
        if (ClientProtectionState.isClaimed(chunkKey) == claim) {
            ClientProtectionState.clearPending(chunkKey);
        } else {
            ClientProtectionState.setPending(chunkKey, claim, dimension);
        }
    }

    // ==================== 提交 ====================

    /**
     * 界面被移除（ESC / 被聊天界面顶掉 / 关游戏）时整批提交。
     *
     * <p>⚠️ 用 {@code removed()} 而不是 {@code onClose()}：按 T 开聊天会让 Screen **被替换**，
     * 那条路径只调 {@code removed()} —— 用 {@code onClose()} 写就会把用户的选择静默丢掉。
     */
    @Override
    public void removed() {
        super.removed();
        flush();
    }

    private void flush() {
        if (flushed || !ClientProtectionState.hasPending()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation dimension = ClientProtectionState.currentDimension();
        ResourceLocation pendingDimension = ClientProtectionState.pendingDimension();
        if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
            return;     // 断线：待提交**保留**，下次打开界面还能提交（不静默丢）
        }
        if (pendingDimension != null && dimension != null && !pendingDimension.equals(dimension)) {
            ClientProtectionState.clearPendingAll();
            minecraft.player.displayClientMessage(Component.literal("[alice] 保护区：维度已变化，未提交的 "
                    + "选择已丢弃（请在新维度重新打开界面）"), false);
            return;
        }
        long[] claims = ClientProtectionState.pendingKeys(true);
        long[] unclaims = ClientProtectionState.pendingKeys(false);
        if (claims.length > 0) {
            AliceNetwork.CHANNEL.sendToServer(new ProtectionActionPacket(true, claims));
        }
        if (unclaims.length > 0) {
            AliceNetwork.CHANNEL.sendToServer(new ProtectionActionPacket(false, unclaims));
        }
        flushed = true;
        ClientProtectionState.clearPendingAll();
    }

    // ==================== 地形色（客户端自采） ====================

    private void ensureTerrain(ResourceLocation dimension) {
        if (terrain != null && dimension.equals(terrainDimension)
                && geometry.centerChunkX() == terrainCenterX && geometry.centerChunkZ() == terrainCenterZ
                && geometry.grid() == terrainGrid) {
            return;
        }
        terrain = sampleTerrain();
        terrainDimension = dimension;
        terrainCenterX = geometry.centerChunkX();
        terrainCenterZ = geometry.centerChunkZ();
        terrainGrid = geometry.grid();
    }

    /**
     * 逐格采样：每格 2×2 个点，从 {@code 玩家Y ± 40} 的窗口里自上而下找第一个非空气方块，
     * 取其 {@link MapColor} 求平均。
     *
     * <p>为什么用"上下 40 格的窗口"而不是客户端高度图：高度图在客户端是否已 prime 取决于
     * 收包路径，读错了会**静默**给出一整屏看似正常的错误颜色（最难查的一类）。窗口扫描是
     * 确定性的，代价是每格最多 81 次 palette 查询（一屏 15×15 ≈ 1.8 万次，打开时算一次）。
     */
    private int[] sampleTerrain() {
        int grid = geometry.grid();
        int[] colors = new int[grid * grid];
        Arrays.fill(colors, UNKNOWN_COLOR);
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (level == null || player == null) {
            return colors;
        }
        int yTop = Math.min(level.getMaxBuildHeight() - 2, Mth.floor(player.getY()) + 40);
        int yBottom = Math.max(level.getMinBuildHeight(), Mth.floor(player.getY()) - 40);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int row = 0; row < grid; row++) {
            for (int column = 0; column < grid; column++) {
                int chunkX = geometry.chunkXAt(column);
                int chunkZ = geometry.chunkZAt(row);
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;       // 未加载 ⇒ 灰色（"不知道"要看得出来）
                }
                int red = 0;
                int green = 0;
                int blue = 0;
                int samples = 0;
                for (int offsetX : SAMPLE_OFFSETS) {
                    for (int offsetZ : SAMPLE_OFFSETS) {
                        int worldX = (chunkX << 4) + offsetX;
                        int worldZ = (chunkZ << 4) + offsetZ;
                        for (int y = yTop; y >= yBottom; y--) {
                            probe.set(worldX, y, worldZ);
                            BlockState state = level.getBlockState(probe);
                            if (state.isAir()) {
                                continue;
                            }
                            MapColor mapColor = state.getMapColor(level, probe);
                            if (mapColor != null && mapColor != MapColor.NONE && mapColor.col != 0) {
                                red += (mapColor.col >> 16) & 0xFF;
                                green += (mapColor.col >> 8) & 0xFF;
                                blue += mapColor.col & 0xFF;
                                samples++;
                            }
                            break;
                        }
                    }
                }
                if (samples > 0) {
                    colors[row * grid + column] = 0xFF000000
                            | ((red / samples) << 16) | ((green / samples) << 8) | (blue / samples);
                }
            }
        }
        return colors;
    }
}
