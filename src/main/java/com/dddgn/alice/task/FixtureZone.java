package com.dddgn.alice.task;

import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.protection.SafeZoneData;
import com.dddgn.alice.protection.TaskZoneRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * **夹具前提：把一块矩形范围临时变成"保护区 + 任务区封套"，用完还原**。
 *
 * <h2>为什么 `D-398` 之后夹具需要它</h2>
 * `D-398`（用户 2026-09-22 决定性断言）把世界修改的责任收窄到**保护区及其子区域**：
 * ① 区外**不记账** ⇒ 垫脚石/工作站**不会**被收尾拆回（`Z1`）；② 保护区内**一定记账**，
 * 而且工作面的写入要过 `ZoneAuthority`（**必须有生效的任务区封套覆盖那一格**，否则逐字仍是
 * `protected_area`）。
 * ⇒ 于是"建拆同权"这类判据**只在保护区内还有意义** ⇒ 凡是要验它的夹具，都**必须自己摆出前提**：
 * 认领区块（= 这片地是我的）+ 声明任务区（= 我这个任务在这里干活）。
 *
 * <h2>为什么不给夹具开特权、也不改检测代码</h2>
 * 让 `recordPlacement` 对"自检任务"网开一面，等于**在生产代码里为测试开洞**，而且会让
 * `ledger_zone_scope` 那类判据失去意义（它验的正是"区外一律不记"）。所以走**夹具摆前提**这条正路：
 * 和生产里 `RegionLumberJob` 声明任务区是同一个入口、同一套判据（`TaskZoneRegistry.declare`）。
 *
 * <h2>诚实标注：这是"借用"一个 L2 工作面封套</h2>
 * 夹具自己的定位是 `DIAGNOSTIC` ⇒ 按阶梯是 `L0`（只读）。所以这里显式传
 * {@code kind = "region_lumber"} + {@code playerDriven = true}（`LUMBER ⇒ L2 工作面`），
 * 语义是"**这块地是玩家的，且有个玩家发起的区域任务正在这里干活**" —— 这正是生产中该场景的由来。
 * 它**不是**在验权限阶梯（那是 `task_zone` 的活），而是让闸门放行，好让夹具测到闸门**后面**的事。
 *
 * <h2>用法（夹具纪律：结束复位）</h2>
 * <pre>{@code
 * zone = FixtureZone.protect(level, bot.getUUID(), min, max, "region_lumber");
 * check("前提", zone.ok() && zone.describe());
 * ...
 * zone.release();   // 终态路径（含失败路径）必须调用；release 幂等
 * }</pre>
 */
public final class FixtureZone {

    private FixtureZone() {
    }

    /**
     * 认领 {@code cornerA..cornerB} 覆盖到的**全部区块**，并在同一范围声明一个任务区封套。
     *
     * @param kind 任务类别名（决定等级；夹具用 {@code "region_lumber"} = `L2 工作面`）
     * @return 句柄；{@link Handle#ok()} 为假时说明前提没摆成（**夹具应当据此判红**，别默默继续）
     */
    public static Handle protect(ServerLevel level, UUID owner, BlockPos cornerA, BlockPos cornerB,
                                 String kind) {
        if (level == null || level.getServer() == null || owner == null) {
            return new Handle(null, owner, List.of(), null, false, null, null, "no_level_or_owner");
        }
        var server = level.getServer();
        ResourceLocation dimension = level.dimension().location();
        SafeZoneData data = SafeZoneData.get(server);
        int minCx = Math.min(cornerA.getX(), cornerB.getX()) >> 4;
        int maxCx = Math.max(cornerA.getX(), cornerB.getX()) >> 4;
        int minCz = Math.min(cornerA.getZ(), cornerB.getZ()) >> 4;
        int maxCz = Math.max(cornerA.getZ(), cornerB.getZ()) >> 4;
        List<Long> added = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (data.claims(dimension).contains(key)) {
                    continue;   // 本来就是我们的地：不动它（release 也不还它）
                }
                if (data.claim(level, cx, cz)) {
                    added.add(key);
                }
            }
        }
        // 任务区必须挂在**打开的作用域**上（`TaskZoneRegistry.declare` 的硬约束：不允许任务之外造授权封套）
        String scopeId = WorldModLedger.currentScope(server, owner);
        boolean ownsScope = false;
        if (scopeId == null) {
            scopeId = WorldModLedger.openScope(server, owner, "fixture_zone");
            ownsScope = true;
        }
        int minX = Math.min(cornerA.getX(), cornerB.getX());
        int maxX = Math.max(cornerA.getX(), cornerB.getX());
        int minZ = Math.min(cornerA.getZ(), cornerB.getZ());
        int maxZ = Math.max(cornerA.getZ(), cornerB.getZ());
        TaskZoneRegistry.WorkArea area = new TaskZoneRegistry.WorkArea(dimension, minX, minZ, maxX, maxZ);
        TaskZoneRegistry.Result declared = TaskZoneRegistry.declare(server, owner, kind, area, true);
        Handle handle = new Handle(level, owner, added, scopeId, ownsScope, declared, area,
                null);
        BotLog.info("[FixtureZone] 夹具前提 = 保护区 + {} 任务区 ｜ {} ｜ {}", kind, area.describe(),
                handle.describe());
        return handle;
    }

    /** 前提句柄（**幂等** `release`）。 */
    public static final class Handle {

        private final ServerLevel level;
        private final UUID owner;
        private final List<Long> added;
        private final String scopeId;
        private final boolean ownsScope;
        private final TaskZoneRegistry.Result declared;
        private final TaskZoneRegistry.WorkArea area;
        private final String problem;
        private boolean released;

        Handle(ServerLevel level, UUID owner, List<Long> added, String scopeId, boolean ownsScope,
               TaskZoneRegistry.Result declared, TaskZoneRegistry.WorkArea area, String problem) {
            this.level = level;
            this.owner = owner;
            this.added = List.copyOf(added);
            this.scopeId = scopeId;
            this.ownsScope = ownsScope;
            this.declared = declared;
            this.area = area;
            this.problem = problem;
        }

        /** 前提是否真的成立（认领到了区块 **且** 任务区封套生效）。 */
        public boolean ok() {
            return problem == null && declared != null && declared.active() && !added.isEmpty();
        }

        /** 生效的任务区（`ok()` 为假时可能为 null）——夹具据此断言等级。 */
        public TaskZoneRegistry.Zone declaredZone() {
            return declared == null ? null : declared.zone();
        }

        /** 新认领的区块数（**本夹具自己加的那些**；本来就是我方的地不算）。 */
        public int claimedChunks() {
            return added.size();
        }

        public String describe() {
            if (problem != null) {
                return "前提未成立：" + problem;
            }
            return "新认领区块=" + added.size()
                    + (area == null ? "" : " 范围=" + area.describe())
                    + " 任务区=" + (declared == null ? "-"
                            : declared.status() + "/" + (declared.zone() == null ? "-"
                                    : declared.zone().level().label() + " chunks="
                                            + declared.zone().chunks().size()))
                    + " scope=" + scopeId;
        }

        /** 还原前提（**幂等**；夹具的每条终态路径都要调）。 */
        public void release() {
            if (released || level == null || level.getServer() == null) {
                return;
            }
            released = true;
            SafeZoneData data = SafeZoneData.get(level.getServer());
            for (long key : added) {
                data.unclaim(level, ChunkPos.getX(key), ChunkPos.getZ(key));
            }
            if (scopeId != null) {
                TaskZoneRegistry.release(scopeId);
            }
            if (ownsScope) {
                WorldModLedger.closeScope(level.getServer(), owner);
            }
            BotLog.info("[FixtureZone] 前提已还原：取消认领 {} 个区块 + 解任务区（scope={}）",
                    added.size(), scopeId);
        }
    }
}
