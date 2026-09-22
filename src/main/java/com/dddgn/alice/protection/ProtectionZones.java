package com.dddgn.alice.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * ⭐ `D-398`（用户 2026-09-22 的**决定性断言**）的**唯一判据入口**：这一格是不是
 * "**保护区及其子区域**"。
 *
 * <h2>为什么要有这个类（而不是各处直接问 `SafeZoneData`）</h2>
 * `D-398` 把世界修改的责任划成两半，此后**每一个**"要不要记账 / 要不要恢复 / 要不要设额度"的判定
 * 都从同一个问题出发：**这格在不在保护区里**。判据散在四处各写一遍就必然漂移（本项目已吃过
 * 四次"同一判据多处消费漏接一处"的教训：`D-338` 附注十/十一、`D-330`），所以这里只留一个名字、
 * 一处口径。
 *
 * <h2>口径（照 `D-398` R1–R4，别在这里加戏）</h2>
 * <ul>
 *   <li><b>保护区</b> = 玩家认领的区块（`SafeZoneData` 的 claim 集，**区块级、全高度、忽略 Y**，
 *       `D-313`） ⇒ 命中 = {@link SafeZoneData#isClaimed}；</li>
 *   <li><b>子区域</b> = 安全区（`safeChunks`）。它按不变量**必须**落在保护区里
 *       （拒绝声明 / `unclaim` 连带清 / `load` 丢孤儿，`D-338` 附注一）⇒ 已经被 claim 集覆盖，
 *       所以这里**不加第三项**；真出现"安全区不在保护区内"那是上面那条链坏了，该由它自己的门禁抓；</li>
 *   <li>⚠️ <b>任务区（`TaskZoneRegistry`）不是保护区</b>。它是"**任务在保护区里干活时的授权封套**"
 *       （可覆盖保护区父类、不得覆盖安全区），**它的存在不把一片野外变成保护区** —— 真机事故里
 *       我正是把 `作用域开启: center=-84,89,148 radius=8`（任务 scope）误当成保护区，
 *       才没看出"这次回收根本不该发生"（`D-406` §二）。</li>
 *   <li>⚠️ <b>挖掘黑名单</b>（原话"除了本身的挖掘黑名单以外"）是**全球通用**的"不许挖"规则，
 *       与"这块地归谁"正交 ⇒ 不进本判据（它在 `BlockBreakSafety` 那一侧）。</li>
 * </ul>
 *
 * <h2>消费者（加一个之前先想清楚它是不是同一判据）</h2>
 * <ol>
 *   <li>`ledger/WorldModLedger.recordPlacement` —— 区外**不记账**（`D-398` R1/R2）；</li>
 *   <li>`ledger/WorldModLedger` 的查询/销账 —— `RestoreScopeTask` **只认区外条目的反面**：
 *       保护区内的条目；</li>
 *   <li>（`Z3`）格数额度：区外不设上限、区内不许静默降级。</li>
 * </ol>
 *
 * <p><b>零副作用</b>：`isClaimed` 是纯认领集查询（不读方块、不加载区块 —— 见 `SafeZoneData.isClaimed`
 * 的 javadoc）；本方法可每 tick 反复问。
 */
public final class ProtectionZones {

    private ProtectionZones() {
    }

    /**
     * 这一格是否落在**保护区及其子区域**里（= 我方对世界修改负有记账/恢复责任的范围）。
     *
     * <p>维度敏感：认领是**按维度**存的 ⇒ 必须传这一格所在的 `level`。
     */
    public static boolean isProtected(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || level.getServer() == null) {
            return false;
        }
        return SafeZoneData.get(level.getServer()).isClaimed(level, pos);
    }

    /**
     * 无主区域（"不是我们的地"）—— `D-398` R1/R2/R4 的适用范围。
     *
     * <p>刻意提供**反面名字**：调用点读起来是"**这是无主区域 ⇒ 不记账**"，
     * 而不是"`!isProtected` ⇒ 不记账"（反向布尔在别处最容易读反）。
     */
    public static boolean isWild(ServerLevel level, BlockPos pos) {
        return !isProtected(level, pos);
    }
}
