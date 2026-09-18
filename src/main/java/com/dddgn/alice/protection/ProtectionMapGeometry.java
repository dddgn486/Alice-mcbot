package com.dddgn.alice.protection;

import net.minecraft.world.level.ChunkPos;

/**
 * 保护区**勾选界面**的几何：鼠标像素 ⇄ 区块的换算（D-314，保护区 2/2）。
 *
 * <p><b>为什么单独一个类</b>：它**没有任何客户端依赖**（只碰 {@link ChunkPos}）⇒ 可以放在 common 里，
 * 于是"点到的格子对应哪个区块""中心格是不是玩家所在区块"这类最容易出错、又最贵的判据
 * **可以在无头夹具里逐条断言**，不必花一个客户端轮次去发现"点左边认领了右边"。
 *
 * <p><b>两条不变量</b>（构造时强制）：
 * <ol>
 *   <li>{@code grid} 必须是**正奇数** —— 正中间那一格 = 玩家所在区块（界面以玩家为中心）；</li>
 *   <li>{@code cell} 必须为正 —— 每格边长（GUI 缩放后的像素）。</li>
 * </ol>
 *
 * <p>坐标系：列 0 是最左列、行 0 是最上一行；列/行 → 区块 = {@code center + index - half}（north-west 方向为负）。
 *
 * @param centerChunkX 视图中心格对应的区块 X（= 玩家所在区块）
 * @param centerChunkZ 视图中心格对应的区块 Z
 * @param grid         每边格数（正奇数）
 * @param cell         每格边长（像素）
 * @param left         网格左上角屏幕 X
 * @param top          网格左上角屏幕 Y
 */
public record ProtectionMapGeometry(int centerChunkX, int centerChunkZ,
                                    int grid, int cell, int left, int top) {

    /** 网格边长下限（太小就点不准）。 */
    public static final int MIN_GRID = 9;
    /** 网格边长上限（太大就画不下）。 */
    public static final int MAX_GRID = 25;
    /** 单格边长下限/上限（像素）。 */
    public static final int MIN_CELL = 6;
    public static final int MAX_CELL = 16;

    public ProtectionMapGeometry {
        if (grid < 1 || grid % 2 == 0) {
            throw new IllegalArgumentException("grid 必须是正奇数（正中间那格 = 玩家所在区块）: " + grid);
        }
        if (cell < 1) {
            throw new IllegalArgumentException("cell 必须为正: " + cell);
        }
    }

    /**
     * 按可用像素选一个**正奇数**网格边长（下限 {@value #MIN_GRID}、上限 {@value #MAX_GRID}）。
     *
     * <p>偶数会被减 1 ⇒ 保证"正中间有一格"这个前提不会被窗口尺寸悄悄破坏
     * （这正是"点到的区块不是玩家站的那块"这类 bug 的入口）。
     */
    public static int fitGrid(int availablePixels, int minCell) {
        int raw = availablePixels / Math.max(1, minCell);
        if (raw % 2 == 0) {
            raw--;
        }
        return Math.max(MIN_GRID, Math.min(MAX_GRID, raw));
    }

    /** 按可用像素与网格边长选单格边长（夹在 {@value #MIN_CELL}..{@value #MAX_CELL}）。 */
    public static int fitCell(int availablePixels, int grid) {
        return Math.max(MIN_CELL, Math.min(MAX_CELL, availablePixels / Math.max(1, grid)));
    }

    /** 中心格的索引（= 玩家所在区块那一格）。 */
    public int half() {
        return grid / 2;
    }

    /** 网格边长（像素）。 */
    public int size() {
        return grid * cell;
    }

    public int right() {
        return left + size();
    }

    public int bottom() {
        return top + size();
    }

    public int chunkXAt(int column) {
        return centerChunkX + column - half();
    }

    public int chunkZAt(int row) {
        return centerChunkZ + row - half();
    }

    public int cellLeft(int column) {
        return left + column * cell;
    }

    public int cellTop(int row) {
        return top + row * cell;
    }

    /** 列/行 → 区块键（{@link ChunkPos#asLong(int, int)}）。 */
    public long keyAt(int column, int row) {
        return ChunkPos.asLong(chunkXAt(column), chunkZAt(row));
    }

    /** 区块 X → 列索引（可能落在网格外，用 {@link #containsCell} 判）。 */
    public int columnOf(int chunkX) {
        return chunkX - centerChunkX + half();
    }

    /** 区块 Z → 行索引。 */
    public int rowOf(int chunkZ) {
        return chunkZ - centerChunkZ + half();
    }

    public boolean containsCell(int column, int row) {
        return column >= 0 && row >= 0 && column < grid && row < grid;
    }

    /** 网格是否包含该区块。 */
    public boolean containsChunk(int chunkX, int chunkZ) {
        return containsCell(columnOf(chunkX), rowOf(chunkZ));
    }

    /**
     * 鼠标坐标 → 区块键；落在网格外返回 {@code null}（调用方据此把事件交还给其它控件）。
     *
     * <p>注意用 {@code floor} 而不是整数除法：鼠标在网格左侧/上侧时差值可能为负，
     * 整数除法会把它算成第 0 格（"点到外面却改了第一格"）。
     */
    public Long keyAt(double mouseX, double mouseY) {
        int column = (int) Math.floor((mouseX - left) / (double) cell);
        int row = (int) Math.floor((mouseY - top) / (double) cell);
        return containsCell(column, row) ? keyAt(column, row) : null;
    }

    public String describe() {
        return "grid=" + grid + " cell=" + cell + " center=" + centerChunkX + "," + centerChunkZ
                + " rect=[" + left + "," + top + " " + right() + "x" + bottom() + "]";
    }
}
