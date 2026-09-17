package com.dddgn.alice.pathing.risk;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **S-6（2026-09-17 用户裁定：先做「按 bot 冻结」的容器）**：把风险开关从**全局静态**变成
 * **按 bot 冻结的快照**。
 *
 * <p>为什么：`RiskSwitches` 是"进程一份"的全局静态（D-046/D-059 的痕迹），于是
 * **一个 bot 的任务跑到一半、别人改了开关**，同一个任务内的风险口径就会**变**（同一份计划的两段
 * 用两套口径评估）。冻结点的语义是：**一个任务从指派到结束，用同一份画像**；
 * `/alice risk` 改开关后**重新冻结所有在跑的 bot**（A/B 对比仍然立刻生效，不牺牲可用性）。
 *
 * <p>与本类的关系：`RiskSwitches` 仍是**配置源/命令入口**（全局默认值）；本类是**消费者读的那一份**。
 * 消费者（搜索侧 + 执行侧）一律读本类，不再直接读 `RiskSwitches`。
 *
 * <p>⚠️ **字段口径（本步只搬现有 1 个开关）**：将来 D-046 的评估器要"哪些字段进画像"定了之后，
 * 再往这个 record 里加分量 —— 这一步**不做** Job 候选筛选，也不新增任何开关。
 */
public record RiskProfile(boolean descendOvershootGuard, boolean containerAccess, boolean hazardAversion) {

    private static final Map<UUID, RiskProfile> FROZEN = new ConcurrentHashMap<>();

    /** 按当前全局开关取一份**新**画像（不冻结）。 */
    public static RiskProfile fromSwitches() {
        return new RiskProfile(RiskSwitches.descendOvershootGuard(), RiskSwitches.containerAccess(),
                RiskSwitches.hazardAversion());
    }

    /** 该 bot 的**冻结**画像（第一次读取时冻结）。 */
    public static RiskProfile of(ServerPlayer player) {
        return FROZEN.computeIfAbsent(player.getUUID(), ignored -> fromSwitches());
    }

    /** 重新冻结该 bot 的画像（任务指派时调用 = 冻结点）。 */
    public static void freeze(ServerPlayer player) {
        FROZEN.put(player.getUUID(), fromSwitches());
    }

    /** 重新冻结一批 bot（命令改开关后用，保持 A/B 立刻生效）。 */
    public static void freezeAll(Collection<? extends ServerPlayer> players) {
        for (ServerPlayer player : players) {
            freeze(player);
        }
    }

    /** **夹具/复位用**：丢掉该 bot 的冻结画像。 */
    public static void unfreeze(ServerPlayer player) {
        FROZEN.remove(player.getUUID());
    }

    /** **夹具/复位用**：清空全部冻结画像。 */
    public static void reset() {
        FROZEN.clear();
    }

    /** **夹具/汇报用**：当前有多少 bot 有冻结画像。 */
    public static int frozenCount() {
        return FROZEN.size();
    }

    /** 日志/命令用的一行描述。 */
    public String describe() {
        return RiskSwitches.DESCEND_OVERSHOOT_GUARD + "=" + descendOvershootGuard;
    }
}
