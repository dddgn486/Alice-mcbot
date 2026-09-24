package com.dddgn.alice.job.fishbone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * **鱼骨模板（`D-386` 切片 1：只有主巷）** —— "参数就是唯一真值，bot 不猜"。
 *
 * <p>定位（`docs/plans/2026-09-21-鱼骨挖矿计划.md` §0/§2）：鱼骨把"算不出怎么去的深处矿"换成
 * "**我脚下/眼前这一格**" ⇒ **深层搜索被取消，而不是被优化**。模板决定一切：bot 不选目标、不猜策略。
 *
 * <h2>⚠️ 切片 1 的范围（本类**故意**只有主巷的四个字段）</h2>
 *
 * <p>计划 §2 的完整模板有 11 个字段（支巷间距/支巷长/侧向/搭路上限/配额/目标族…）。
 * 切片 1 只交付「**主巷 + 返回**」⇒ 这里**只声明真正有消费者的字段**。
 * 这不是设计缩水，是项目纪律：**"声明了没人用"本身就是缺陷**（同 `UNTIL_FULL` 被判死的那条；
 * 门禁 `J5-P1` 会红）。切片 2 加支巷/收集、切片 3 加真机入口时，**各自带着消费者**把字段补上。
 *
 * <h2>纯几何、不读世界（判据 `C1`/`C3` 的基准）</h2>
 *
 * <ul>
 *   <li>一个**单元** = 脚位格 + 其上 `height-1` 格（净高 2 ⇒ 脚位 + 头位两格）；</li>
 *   <li>单元序列按推进方向**严格有序**（`i = 1..mainLength`）；</li>
 *   <li>**脚位格的下方不属于模板**（不挖地板）⇒ bot 不会把自己挖穿（计划 §3 第 4 条）。</li>
 * </ul>
 *
 * @param startFoot  起点**脚位格**（玩家给；也是 `RETURN` 的目标、收集作用域的中心）
 * @param dir        主巷方向（**只许四向水平**；竖直方向没有"主巷"的语义）
 * @param mainLength 主巷长度（格；= 推进单元的个数）
 * @param height     巷道净高（脚位 + 上方可通行格数；**2 或 3**，默认 2 —— 用户 2026-09-21 裁定）
 */
public record FishboneTemplate(BlockPos startFoot, Direction dir, int mainLength, int height) {

    /** 净高默认值（用户 2026-09-21 裁定：净高 2 = 玩家能走的通道）。 */
    public static final int DEFAULT_HEIGHT = 2;

    /** 净高下限（1 格高不是"巷道"）。 */
    public static final int MIN_HEIGHT = 2;

    /** 净高上限（计划 §2 合法域：`3` 只在"通行更舒服"时用，破坏数 +50%）。 */
    public static final int MAX_HEIGHT = 3;

    public FishboneTemplate {
        Objects.requireNonNull(startFoot, "startFoot");
        Objects.requireNonNull(dir, "dir");
        startFoot = startFoot.immutable();
        // 非法即**拒绝**而不是夹取（计划 §2 的口径）：夹取会让"玩家要 20 格"静默变成"挖了 3 格"。
        if (dir.getAxis().isVertical()) {
            throw new IllegalArgumentException("dir 必须是水平四向（N/S/E/W）：" + dir);
        }
        if (mainLength < 1) {
            throw new IllegalArgumentException("mainLength 必须 ≥ 1：" + mainLength);
        }
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("height 必须是 " + MIN_HEIGHT + " 或 " + MAX_HEIGHT + "：" + height);
        }
    }

    /** 便捷构造（净高取默认值）。 */
    public static FishboneTemplate main(BlockPos startFoot, Direction dir, int mainLength) {
        return new FishboneTemplate(startFoot, dir, mainLength, DEFAULT_HEIGHT);
    }

    /**
     * **模板格序列**（纯几何、不读世界、确定性、可复算）：按推进顺序展开，
     * 每个单元先脚位格、再其上 `height-1` 格。
     *
     * <p>判据 `C1`（模板 = 事实）与 `C5`（白名单）都以**本方法的返回**为基准 ——
     * 因此它必须是纯函数（同参数同结果），否则判据会跟着实现漂。
     */
    public List<BlockPos> cells() {
        List<BlockPos> out = new ArrayList<>(mainLength * height);
        for (int i = 1; i <= mainLength; i++) {
            BlockPos foot = startFoot.relative(dir, i);
            for (int dy = 0; dy < height; dy++) {
                out.add(foot.above(dy));
            }
        }
        return List.copyOf(out);
    }

    /** 模板格集合（`C1`/`C5` 的白名单；顺序无关，去重）。 */
    public Set<BlockPos> cellSet() {
        return Set.copyOf(new LinkedHashSet<>(cells()));
    }

    /** 第 `unit`（1-based）个单元的脚位格；越界即抛（调用方自己保证范围）。 */
    public BlockPos footOf(int unit) {
        if (unit < 1 || unit > mainLength) {
            throw new IllegalArgumentException("unit 越界：" + unit + " ∉ [1, " + mainLength + "]");
        }
        return startFoot.relative(dir, unit);
    }

    /** 模板末端脚位格（主巷最深处的脚位）。 */
    public BlockPos lastFoot() {
        return footOf(mainLength);
    }

    /**
     * **推进格数**（判据 `C3` 的分母 = "搜索规模 ≤ 常数 × 推进格数"里的那个数）。
     *
     * <p>切片 1 = 主巷单元数；切片 2 起还要加上支巷与追簇的游走格（那时它会变，判据跟着变）。
     */
    public int advanceCells() {
        return mainLength;
    }

    /**
     * **作业作用域半径**（计划 §3 `COLLECT` 的纪律：半径必须**从模板推导**，不许沿用默认 32 —— `D-346`
     * 的教训是"追取上限 < 作业直径 ⇒ 判据永不成立"）。
     *
     * <p>切片 1 没有收集段，但 `MineTask` 需要作用域 ⇒ 现在就用**同一个**推导式，免得切片 2 再改一次。
     * `+ 2` = 包围盒对角余量（起点那一格 + 一格缓冲）。
     */
    public int scopeRadius() {
        return mainLength + 2;
    }

    public String describe() {
        return "dir=" + dir.getName() + " main=" + mainLength + " height=" + height
                + " start=" + startFoot.toShortString() + " cells=" + cells().size();
    }
}
