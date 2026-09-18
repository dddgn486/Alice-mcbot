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
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

/**
 * **保护区勾选界面**（`D-314` 建、`D-315` 细化地形）：以玩家为中心的 N×N **区块**网格，
 * 左键认领 / 右键取消 / 拖动连选 / 关闭即批量提交。
 *
 * <p>分工（`D-307`）：
 * <ul>
 *   <li><b>认领态只来自服务端</b>：{@link ClientProtectionState}。没有本维度快照时界面**拒绝编辑**
 *       （否则用户会在空网格上盲点右键 = **盲取消认领**，破坏性），并每 20 tick 自愈请求一次快照；</li>
 *   <li><b>地形 100% 客户端自采，且不碰原版地图渲染器</b>：每格切成
 *       {@link ProtectionMapGeometry#sub()}×{@link ProtectionMapGeometry#sub()} 个子格，各自采一列
 *       （从玩家高度 ±40 的窗口里自上而下找第一个非空气方块，取 {@code MapColor#col}）；
 *       同色再按**相对高度**加明暗 ⇒ 「一格一色块」变成能认出**地形起伏与建筑轮廓**的低分辨率地图。
 *       未加载的子格 = 浅灰，窗口里没有地表的子格 = 深灰（**"不知道"必须看得出来**）；</li>
 *   <li><b>渐进采样</b>：按 {@link ProtectionMapGeometry#centreOutOrder(int)} 的**中心向外环序**，
 *       每帧只算固定格数 ⇒ 打开界面先有玩家周围、远处几百毫秒内补齐，**不占满一帧**（大窗口尤其明显）；</li>
 *   <li><b>提交是一次性批包</b>：关闭（{@code removed()}，含被聊天界面顶掉）时按动作发 1~2 包；
 *       发不出去（断线）就**留着**，下次打开还能提交。</li>
 * </ul>
 *
 * <p>键位：左键 = 认领 · 右键 = 取消 · 拖动 = 连选 · {@code R} = 回到玩家所在区块 ·
 * {@code G} = 高度明暗开关 · 关闭 = 提交。
 *
 * <p>几何换算在 {@link ProtectionMapGeometry}（无客户端依赖 ⇒ 无头夹具能逐条断言"点到的格子对应哪个区块"、
 * 子格是否精确平铺、采样序是否真的从中心向外）。
 */
@OnlyIn(Dist.CLIENT)
public class ProtectionMapScreen extends Screen {

    private static final int MARGIN = 10;
    private static final int HEADER_H = 28;
    private static final int FOOTER_H = 56;

    /** 每隔几格标一个区块坐标（边缘刻度）。 */
    private static final int EDGE_LABEL_EVERY = 4;
    /** 每帧最多采样几格（一格 = sub² 列）⇒ 大窗口也不会卡帧。 */
    private static final int SAMPLED_CELLS_PER_FRAME = 6;
    /** 采样窗口：玩家脚位上下各多少格。 */
    private static final int SAMPLE_WINDOW_Y = 40;

    /** 未加载的子格（"不知道"要看得见，不能假装是地形）。 */
    private static final int UNLOADED_COLOR = 0xFF454545;
    /** 采过但窗口里没有地表的子格。 */
    private static final int UNSAMPLED_COLOR = 0xFF1E1E1E;
    private static final int CLAIMED_TINT = 0x33FF4040;
    private static final int PENDING_CLAIM_TINT = 0x60FFC000;
    private static final int PENDING_UNCLAIM_TINT = 0x6080C0FF;
    private static final int GRID_LINE = 0x28FFFFFF;
    private static final int REGION_LINE = 0x60FFFFFF;
    private static final int OUTER_BORDER = 0xFF909090;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0B0B0;
    private static final int TEXT_LABEL = 0xFF9AA0A6;
    private static final int TEXT_WARN = 0xFFFFD060;

    /** 「本子格没有地表」（窗口里没扫到非空气方块）。 */
    private static final int NO_TOP = Integer.MIN_VALUE;

    private ProtectionMapGeometry geometry;
    private int sub;

    // 采样缓冲：索引 = ((row*grid + column) * sub + sx) * sub + sz
    private int[] subRgb;
    private int[] subTopY;
    private boolean[] subDone;
    private String[] cellSurface;
    private int[] sampleOrder;
    private int sampleCursor;
    private long topYSum;
    private int topYCount;
    private boolean shade = true;

    /** 扫描结果的复用槽位（[0] = y；方块名走 {@link #lastBlockName}）⇒ 每列零分配。 */
    private final int[] sampleOut = new int[2];
    private String lastBlockName;

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
        sub = geometry.sub();

        int subCount = grid * grid * sub * sub;
        subRgb = new int[subCount];
        subTopY = new int[subCount];
        subDone = new boolean[subCount];
        cellSurface = new String[grid * grid];
        Arrays.fill(subRgb, UNSAMPLED_COLOR);
        Arrays.fill(subTopY, NO_TOP);
        sampleOrder = ProtectionMapGeometry.centreOutOrder(grid);
        sampleCursor = 0;
        topYSum = 0;
        topYCount = 0;
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
        if (keyCode == GLFW.GLFW_KEY_G) {
            shade = !shade;                           // 明暗只是一层观感 ⇒ 给个不用重进游戏的开关
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
        graphics.drawString(font, "维度 " + dimension + " · " + geometry.describe() + sampleProgress(),
                MARGIN, 18, TEXT_DIM);

        if (!ClientProtectionState.hasSnapshotFor(dimension)) {
            graphics.drawCenteredString(font, "等待服务端认领数据…", width / 2, height / 2 - 10, TEXT_WARN);
            graphics.drawCenteredString(font, "（已自动请求；若一直不来，请关掉再开一次界面）",
                    width / 2, height / 2 + 6, TEXT_DIM);
            return;
        }

        sampleStep();
        renderGrid(graphics, mouseX, mouseY);
        renderFooter(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String sampleProgress() {
        if (sampleOrder == null || sampleCursor >= sampleOrder.length) {
            return "";
        }
        return " · 采样中 " + (sampleCursor * 100 / sampleOrder.length) + "%";
    }

    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        Long hovered = geometry.keyAt(mouseX, mouseY);
        int grid = geometry.grid();
        int reference = referenceY();
        for (int row = 0; row < grid; row++) {
            for (int column = 0; column < grid; column++) {
                long key = geometry.keyAt(column, row);
                Boolean pending = ClientProtectionState.pending(key);
                boolean claimed = ClientProtectionState.isClaimed(key);

                for (int sx = 0; sx < sub; sx++) {
                    for (int sz = 0; sz < sub; sz++) {
                        int index = subIndex(column, row, sx, sz);
                        int color = subRgb[index];
                        if (shade && subDone[index] && subTopY[index] != NO_TOP
                                && color != UNLOADED_COLOR) {
                            color = shade(color, subTopY[index] - reference);
                        }
                        graphics.fill(geometry.subLeft(column, sx), geometry.subTop(row, sz),
                                geometry.subRight(column, sx), geometry.subBottom(row, sz), color);
                    }
                }

                int x1 = geometry.cellLeft(column);
                int y1 = geometry.cellTop(row);
                int x2 = x1 + geometry.cell();
                int y2 = y1 + geometry.cell();
                if (pending != null) {
                    graphics.fill(x1, y1, x2, y2, pending ? PENDING_CLAIM_TINT : PENDING_UNCLAIM_TINT);
                } else if (claimed) {
                    graphics.fill(x1, y1, x2, y2, CLAIMED_TINT);
                }
                if (claimed || (pending != null && pending)) {
                    graphics.renderOutline(x1, y1, geometry.cell(), geometry.cell(),
                            pending != null ? TEXT_WARN : 0xFFFF6060);
                }
                if (hovered != null && hovered.longValue() == key) {
                    graphics.renderOutline(x1, y1, geometry.cell(), geometry.cell(), TEXT);
                }
            }
        }

        // 网格线（每 4 格一根亮的，便于数格子）＋ 外框
        for (int i = 0; i <= grid; i++) {
            int color = i % 4 == 0 ? REGION_LINE : GRID_LINE;
            int x = geometry.left() + i * geometry.cell();
            int y = geometry.top() + i * geometry.cell();
            graphics.fill(x, geometry.top(), x + 1, geometry.bottom(), color);
            graphics.fill(geometry.left(), y, geometry.right(), y + 1, color);
        }
        graphics.renderOutline(geometry.left() - 1, geometry.top() - 1, geometry.size() + 2, geometry.size() + 2,
                OUTER_BORDER);

        // 玩家所在区块（正中心那一格）
        int half = geometry.half();
        graphics.renderOutline(geometry.cellLeft(half) + 1, geometry.cellTop(half) + 1,
                Math.max(1, geometry.cell() - 2), Math.max(1, geometry.cell() - 2), TEXT);

        renderEdgeLabels(graphics);
    }

    /** 边缘区块坐标刻度（每 4 格一个）—— "能分辨"的一半其实是"能定位"。 */
    private void renderEdgeLabels(GuiGraphics graphics) {
        int labelTop = Math.max(20, geometry.top() - 9);
        for (int column = 0; column < geometry.grid(); column += EDGE_LABEL_EVERY) {
            graphics.drawString(font, String.valueOf(geometry.chunkXAt(column)),
                    geometry.cellLeft(column), labelTop, TEXT_LABEL);
        }
        for (int row = 0; row < geometry.grid(); row += EDGE_LABEL_EVERY) {
            String text = String.valueOf(geometry.chunkZAt(row));
            graphics.drawString(font, text, Math.max(1, geometry.left() - 4 - font.width(text)),
                    geometry.cellTop(row) + 2, TEXT_LABEL);
        }
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        String cursor = "光标：网格外";
        Long hovered = geometry.keyAt(mouseX, mouseY);
        if (hovered != null) {
            int column = geometry.columnOf(ChunkPos.getX(hovered));
            int row = geometry.rowOf(ChunkPos.getZ(hovered));
            boolean claimed = ClientProtectionState.isClaimed(hovered);
            Boolean pending = ClientProtectionState.pending(hovered);
            String state = pending != null ? (pending ? "待认领" : "待取消") : (claimed ? "已认领" : "未认领");
            String surface = cellSurface[row * geometry.grid() + column];
            int topY = subTopY[subIndex(column, row, sub / 2, sub / 2)];
            cursor = "光标：区块 " + ChunkPos.getX(hovered) + ", " + ChunkPos.getZ(hovered) + " · " + state
                    + (surface == null || topY == NO_TOP ? "" : " · 地表 " + surface + " y=" + topY);
        }
        int y = geometry.bottom() + 4;
        graphics.drawString(font, cursor, MARGIN, y, TEXT);
        graphics.drawString(font, "本视图已认领 " + ClientProtectionState.claimed().size() + " 格"
                        + (ClientProtectionState.truncated() ? " · ⚠ 快照被截断（远端未显示）" : ""),
                MARGIN, y + 11, ClientProtectionState.truncated() ? TEXT_WARN : TEXT_DIM);
        graphics.drawString(font, "图例：红框=已认领 · 琥珀=待认领 · 蓝=待取消 · 浅灰=未加载 · 深灰=无地表 · 亮暗=高低",
                MARGIN, y + 22, TEXT_DIM);
        graphics.drawString(font, "左键=认领 · 右键=取消 · 拖动=连选 · R=回到玩家 · G=明暗(" + (shade ? "开" : "关")
                        + ") · 关闭即提交（待提交 " + ClientProtectionState.pendingCount() + "）",
                MARGIN, y + 33, TEXT_DIM);
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

    // ==================== 地形采样（客户端自采，渐进） ====================

    private int subIndex(int column, int row, int subColumn, int subRow) {
        return ((row * geometry.grid() + column) * sub + subColumn) * sub + subRow;
    }

    /** 明暗基准 = 已采样子格的**平均地表高度**（边走边更新；还没采到任何东西时退回玩家脚位）。 */
    private int referenceY() {
        if (topYCount > 0) {
            return (int) (topYSum / topYCount);
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? 64 : Mth.floor(minecraft.player.getY());
    }

    /** 每帧只算固定格数（按中心向外环序）⇒ 打开界面立刻有中心区，且**不占满一帧**。 */
    private void sampleStep() {
        if (sampleOrder == null || sampleCursor >= sampleOrder.length) {
            return;
        }
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        int footY = Mth.floor(player.getY());
        int yTop = Math.min(level.getMaxBuildHeight() - 2, footY + SAMPLE_WINDOW_Y);
        int yBottom = Math.max(level.getMinBuildHeight(), footY - SAMPLE_WINDOW_Y);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int i = 0; i < SAMPLED_CELLS_PER_FRAME && sampleCursor < sampleOrder.length; i++) {
            sampleCell(sampleOrder[sampleCursor++], level, yTop, yBottom, probe);
        }
    }

    private void sampleCell(int cellIndex, ClientLevel level, int yTop, int yBottom,
                            BlockPos.MutableBlockPos probe) {
        int grid = geometry.grid();
        int column = cellIndex % grid;
        int row = cellIndex / grid;
        int chunkX = geometry.chunkXAt(column);
        int chunkZ = geometry.chunkZAt(row);
        boolean loaded = level.hasChunk(chunkX, chunkZ);
        for (int sx = 0; sx < sub; sx++) {
            for (int sz = 0; sz < sub; sz++) {
                int index = subIndex(column, row, sx, sz);
                subDone[index] = true;
                if (!loaded) {
                    subRgb[index] = UNLOADED_COLOR;      // 未加载 ⇒ 浅灰（不假装知道）
                    subTopY[index] = NO_TOP;
                    continue;
                }
                int worldX = (chunkX << 4) + ProtectionMapGeometry.sampleLocal(sub, sx);
                int worldZ = (chunkZ << 4) + ProtectionMapGeometry.sampleLocal(sub, sz);
                int color = scanTopColumn(level, worldX, worldZ, yTop, yBottom, probe);
                if (color < 0) {
                    subRgb[index] = UNSAMPLED_COLOR;     // 窗口里没有地表 ⇒ 深灰
                    subTopY[index] = NO_TOP;
                } else {
                    subRgb[index] = 0xFF000000 | color;
                    subTopY[index] = sampleOut[0];
                    topYSum += sampleOut[0];
                    topYCount++;
                }
                if (sx == sub / 2 && sz == sub / 2) {    // 中心子格 ⇒ 悬停文字用
                    cellSurface[cellIndex] = color < 0 ? null : lastBlockName;
                }
            }
        }
    }

    /**
     * 自上而下找该列第一个非空气方块，返回它的 {@code MapColor#col}（{@code -1} = 窗口里没有地表）。
     *
     * <p>复用以避免每列分配：结果 y 写进 {@link #sampleOut}[0]，方块名写进 {@link #lastBlockName}。
     */
    private int scanTopColumn(ClientLevel level, int worldX, int worldZ, int yTop, int yBottom,
                              BlockPos.MutableBlockPos probe) {
        for (int y = yTop; y >= yBottom; y--) {
            probe.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(probe);
            if (state.isAir()) {
                continue;
            }
            sampleOut[0] = y;
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            lastBlockName = id == null ? state.getBlock().getName().getString() : id.getPath();
            MapColor mapColor = state.getMapColor(level, probe);
            if (mapColor == null || mapColor == MapColor.NONE || mapColor.col == 0) {
                return -1;
            }
            return mapColor.col;
        }
        return -1;
    }

    /** 同色按相对高度加明暗：墙体 / 山脊亮、坑洼暗 ⇒ 一眼能看出起伏（{@code G} 键可关）。 */
    private static int shade(int color, int dy) {
        double factor = 1.0 + dy * 0.045;
        if (factor < 0.62) {
            factor = 0.62;
        } else if (factor > 1.32) {
            factor = 1.32;
        }
        return 0xFF000000
                | (clampColor((int) (((color >> 16) & 0xFF) * factor)) << 16)
                | (clampColor((int) (((color >> 8) & 0xFF) * factor)) << 8)
                | clampColor((int) ((color & 0xFF) * factor));
    }

    private static int clampColor(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
