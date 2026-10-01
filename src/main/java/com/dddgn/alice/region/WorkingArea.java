package com.dddgn.alice.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ⭐ **实际作业区（`workingArea`）** —— **对内**对"一片水平作业范围"的称呼；**玩家实际划分的就是它**
 * （{@code /alice region set} 划的那块地＝一个 `WorkingArea`）。
 *
 * <p>⭐ **三层口径**（2026-10-01 用户定案，`D-567` 补充）：
 * <table border="1">
 *   <tr><th>词</th><th>是什么</th><th>代码载体</th></tr>
 *   <tr><td><b>{@code region_job}</b></td><td>**对外**的 job **种类**（口径词）</td>
 *       <td>⚠️ 今天无载体（只作口径；`JobRequest.Kind` 是平表）</td></tr>
 *   <tr><td><b>{@code region_lumber}</b></td><td>**对外**的具体 kind（保留）</td>
 *       <td>{@code JobRequest.Kind.REGION_LUMBER} · `RegionLumberJob`</td></tr>
 *   <tr><td><b>{@code WorkingArea}</b>（本类）</td><td>⭐ **对内**的实际作业区（玩家划的那块）</td>
 *       <td>本类 · `LumberAreaState.Area` 的水平部分</td></tr>
 *   <tr><td><b>{@code JobRegion}</b></td><td>⭐ **由 `WorkingArea` 创建出来的区域**（区块级封套、随 `scopeId` 生灭）</td>
 *       <td>{@link JobRegionRegistry.JobRegion}（⇒ 待改名为 `JobRegion`）</td></tr>
 * </table>
 *
 * <p>⚠️ **本类只描述水平范围**（⛔ 不含维度、⛔ 不含 Y）——三条理由：
 * <ol>
 *   <li>**维度是「区域」的属性，不是 footprint 的属性** ⇒ 它在 {@code JobRegion.dimension()} 上
 *       （唯一的读者是"这一格被哪个 job 区覆盖"的查询）；</li>
 *   <li>**Y 不参与区块派生**（与保护区同一口径：忽略 Y、覆盖全高度）—— 否则"同一条隧道里上下两格
 *       拿到不同授权"这种怪事就会出现；</li>
 *   <li>**竖直策略是作业侧的事**（伐木的 `baseY` / 自适应上限）⇒ 在 `LumberAreaState.Area` 上，
 *       ⛔ 不塞进"这块地是什么"。</li>
 * </ol>
 *
 * <p>⭐ **2026-10-01：本类由两份重复定义合并而来**（用户逐字「**这两个同名类不能合并吗？本来就是重复的**」）——
 * 旧的 {@code region/WorkingArea}（`dimension ＋ 水平矩形`）与旧
 * {@code job/lumber/LumberAreaState.Area} 的水平部分（`minX/minZ/maxX/maxZ`）。
 * ⇒ 从此**只有一个 `WorkingArea`**（⛔ 同名两类）。{@code center()}/{@code contains()} 这类**要用到竖直信息**
 * 的方法**不在本类**（它们留在 `LumberAreaState.Area` 上，那里有 `baseY`）。
 */
public record WorkingArea(int minX, int minZ, int maxX, int maxZ) {

    /** 规范化：两个角反过来写也得到同一个区域（`equals` 因此对"重划同一个区"稳定 ⇒ 幂等可判）。 */
    public WorkingArea {
        if (minX > maxX) {
            int swap = minX;
            minX = maxX;
            maxX = swap;
        }
        if (minZ > maxZ) {
            int swap = minZ;
            minZ = maxZ;
            maxZ = swap;
        }
    }

    /** 水平（x/z）是否落在作业区内。 */
    public boolean containsHorizontal(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /** 水平格数（**方块级**的规模；与区块数不是一回事，日志里两个都打）。 */
    public int areaXZ() {
        return (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    /** 覆盖该水平范围所需的外接半径（`TreeScanner` 只吃"中心 + 半径"）。 */
    public int coverRadius() {
        int dx = maxX - minX;
        int dz = maxZ - minZ;
        return (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz) / 2.0D) + 2;
    }

    /**
     * 派生：本作业区**所占区块的最小覆盖**（单向：工作区域 ⇒ job 区）。
     *
     * <p>为什么这就是最小覆盖：矩形是**连续**的 ⇒ 这个区块矩形里每一个区块都至少含矩形的一格
     * （不会出现"扫进来却空着"的区块）⇒ 既覆盖全部方块，又没有一个多余区块。夹具用**逐方块枚举**那条
     * 独立路径对这一点做等式断言（⛔ 不是同一公式抄两遍）。
     */
    public Set<Long> chunkCover() {
        Set<Long> result = new LinkedHashSet<>();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                result.add(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
        return result;
    }

    /** 水平描述（⛔ 不含 Y、不含维度 —— 那两样不属本类）。 */
    public String describe() {
        return "x" + minX + ".." + maxX + " z" + minZ + ".." + maxZ;
    }
}
