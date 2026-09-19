package com.dddgn.alice.protection;

import com.dddgn.alice.action.WritePolicyMatrix;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * ⭐ **区域级授权面**（`D-338` ① 的落地；用户 2026-09-19 拍板权限阶梯，`D-338` 附注七②③）
 * —— **一个判据，三处消费**：候选扫描 / 破坏闸门 / 放置闸门都问这一个函数。
 *
 * <p><b>它回答什么</b>：在**保护区**（= 玩家认领的区块）里，**这一次写入**（`pos` + `reason` + 动作）
 * 有没有被授予权限。答案三态：
 * <ul>
 *   <li>{@link Verdict#NOT_GATED}：**该区块未认领** ⇒ 这里**不管**（野外由成本模型 + 维生 + 只读审计治理，
 *       `D-327` 场所化）；</li>
 *   <li>{@link Verdict#ALLOW}：**任务区**覆盖了这一格，且等级够 ⇒ **放行**；
 *       ⚠️ 放行**不等于**免检：调用方仍要走既有的 `WriteGrant`（谁/为什么）+ `WriteBudget`（几次）+ 账本（`TEMP`/`KEEP`）；</li>
 *   <li>{@link Verdict#DENY}：拒绝 + **可归因的码**。</li>
 * </ul>
 *
 * <p><b>为什么只做一处</b>：三处各写一套判据 = 重演"六份重复"（`BlockBreakSafety` 的教训）。
 * 这里连"保护区黑名单规则（`protected_block`/`protected_tag`）**不参与**区域授权"这条口径
 * 也收在 {@link #regionRefusal} 里 —— 那两条是**全世界通用**的玩家规则，任务区不许把它们顶掉。
 *
 * <p><b>拒绝码（稳定词表；调用方/日志/夹具都按它归因）</b>：
 * <table border="1">
 *   <tr><th>码</th><th>含义</th></tr>
 *   <tr><td>{@code protected_area}</td><td>在保护区里、**没有**生效的任务区（或任务区不覆盖这一格）
 *       —— ⚠️ **这是今天的行为，逐字保留**（既有失败码/夹具/文档都按它写）</td></tr>
 *   <tr><td>{@code protected_safe_zone}</td><td>在**安全区**（子类声明）里 —— 任务区**不可能**覆盖它
 *       （`declare` 直接拒绝）⇒ 这里是纵深防御，不该出现</td></tr>
 *   <tr><td>{@code zone_read_only}</td><td>任务区等级 = `L0`（只读）</td></tr>
 *   <tr><td>{@code zone_break_not_allowed}</td><td>`L1`（临时脚手架）**不许破坏**</td></tr>
 *   <tr><td>{@code zone_place_not_scaffold}</td><td>`L1` 只许**临时**放置（`WriteReason#temporary()`），
 *       这一笔不是</td></tr>
 *   <tr><td>{@code zone_place_quota}</td><td>`L1` 的**区内 8 次**放置配额用尽</td></tr>
 *   <tr><td>{@code zone_reason_required}</td><td>调用方**没给理由** ⇒ 不给区域级放行（保守：没有声明就没有授权）</td></tr>
 * </table>
 */
public final class ZoneAuthority {

    /** `L1`（临时脚手架）在**区内**的放置配额（用户口径：**≤8 次**）。 */
    public static final int L1_MAX_PLACES = 8;

    /** 判定三态。 */
    public enum Verdict {
        /** 该区块**未认领** ⇒ 本判据不适用（野外）。 */
        NOT_GATED,
        /** 有任务区覆盖 + 等级够 ⇒ 放行（仍受授权/预算/账本约束）。 */
        ALLOW,
        /** 拒绝（带码）。 */
        DENY
    }

    /** 写入动作（同一个位置，破坏与放置的等级要求不同）。 */
    public enum Act {
        BREAK, PLACE
    }

    /** 判定结果（`code` 为 `null` 表示放行/不适用）。 */
    public record Decision(Verdict verdict, String code, String detail) {

        public boolean allowed() {
            return verdict == Verdict.ALLOW;
        }

        /** 拒绝原因码（放行/不适用 ⇒ `null`）。 */
        public String refusal() {
            return verdict == Verdict.DENY ? code : null;
        }

        public String describe() {
            return verdict + (code == null ? "" : "[" + code + "]") + " " + detail;
        }
    }

    private ZoneAuthority() {
    }

    /**
     * **唯一判据**：这一格、这个理由、这个动作，在（可能的）保护区里被授权了吗。
     *
     * @param reason 声明式写入理由；{@code null} = 调用方没声明 ⇒ **不给区域级放行**
     *               （保守方向：没有理由就没有授权，且逐字保持旧行为 `protected_area`）
     */
    public static Decision authorize(ServerLevel level, UUID owner, BlockPos pos, WriteReason reason, Act act) {
        if (level == null || pos == null) {
            return new Decision(Verdict.NOT_GATED, null, "no_level_or_pos");
        }
        SafeZoneData zones = SafeZoneData.get(level.getServer());
        if (!zones.isClaimed(level, pos)) {
            return new Decision(Verdict.NOT_GATED, null,
                    "unclaimed chunk " + (pos.getX() >> 4) + "," + (pos.getZ() >> 4));
        }
        if (zones.isSafe(level, pos)) {
            // 安全区 ⊆ 保护区，且任务区**不得**覆盖子类声明（declare 已拒）⇒ 走到这里说明有东西坏了
            return new Decision(Verdict.DENY, "protected_safe_zone",
                    "安全区（子类声明）内的写入：任务区不许覆盖它（D-338 附注二第 1 条）");
        }
        TaskZoneRegistry.Zone zone = TaskZoneRegistry.zoneOf(level.getServer(), owner);
        if (zone == null || !zone.covers(pos)) {
            return new Decision(Verdict.DENY, "protected_area",
                    "保护区内的写入需要**生效的任务区**覆盖这一格（owner=" + shortId(owner)
                            + " zone=" + (zone == null ? "无" : zone.kind() + "/不覆盖此格") + "）");
        }
        if (reason == null) {
            return new Decision(Verdict.DENY, "zone_reason_required",
                    "任务区 " + zone.kind() + " 存在，但这次写入**没有声明理由** ⇒ 不给放行");
        }
        WritePolicyMatrix.Level level0 = zone.level();
        if (act == Act.BREAK) {
            if (!level0.allowsBreak()) {
                return new Decision(Verdict.DENY,
                        level0 == WritePolicyMatrix.Level.L0_READ_ONLY ? "zone_read_only" : "zone_break_not_allowed",
                        "任务区 " + zone.kind() + " 等级 " + level0.label()
                                + (level0 == WritePolicyMatrix.Level.L0_READ_ONLY ? "（只读）" : "（临时脚手架 ⇒ 不许破坏）"));
            }
            return new Decision(Verdict.ALLOW, null,
                    "任务区 " + zone.kind() + " 等级 " + level0.label() + " ⇒ 放行破坏（理由 " + reason.name() + "）");
        }
        if (!level0.allowsPlace()) {
            return new Decision(Verdict.DENY, "zone_read_only",
                    "任务区 " + zone.kind() + " 等级 " + level0.label() + "（只读）⇒ 不许放置");
        }
        if (level0 == WritePolicyMatrix.Level.L1_SCAFFOLD) {
            if (!reason.temporary()) {
                return new Decision(Verdict.DENY, "zone_place_not_scaffold",
                        "任务区 " + zone.kind() + " 等级 L1 只许**临时**放置，而 " + reason.name() + " 不是");
            }
            int used = TaskZoneRegistry.zonePlaceCount(zone.scopeId());
            if (used >= L1_MAX_PLACES) {
                return new Decision(Verdict.DENY, "zone_place_quota",
                        "任务区 " + zone.kind() + " 等级 L1 的**区内放置配额**已用尽（" + used + "/"
                                + L1_MAX_PLACES + "）");
            }
        }
        return new Decision(Verdict.ALLOW, null,
                "任务区 " + zone.kind() + " 等级 " + level0.label() + " ⇒ 放行放置（理由 " + reason.name() + "）");
    }

    /**
     * **把"保护区判定的结果"过一遍区域授权面**（三个消费点共用的那一行）：
     * 返回 `null` = 放行，非 null = 拒绝码。
     *
     * <p>分工写死在这里，避免三处各写一遍而漂移：
     * ① `null`（没有保护区规则命中）⇒ 放行；
     * ② `protected_block` / `protected_tag` ⇒ **原样拒**（玩家黑名单/标签规则**全世界通用**，
     *    与"这块地认没认领"正交，任务区不许顶掉它）；
     * ③ `protected_area`（区块认领）⇒ **问区域授权面**（可能因任务区 + 等级而放行）。
     */
    public static String regionRefusal(ServerLevel level, UUID owner, BlockPos pos, String worldProtection,
                                       WriteReason reason, Act act) {
        if (worldProtection == null) {
            return null;
        }
        if (!"protected_area".equals(worldProtection)) {
            return worldProtection;
        }
        return authorize(level, owner, pos, reason, act).refusal();
    }

    /** 便捷入口（破坏）：{@code null} = 允许。 */
    public static String breakRefusal(ServerLevel level, UUID owner, BlockPos pos, WriteReason reason) {
        return authorize(level, owner, pos, reason, Act.BREAK).refusal();
    }

    /** 便捷入口（放置）：{@code null} = 允许。 */
    public static String placeRefusal(ServerLevel level, UUID owner, BlockPos pos, WriteReason reason) {
        return authorize(level, owner, pos, reason, Act.PLACE).refusal();
    }

    /** 放行时留一行（**低频**：只在"保护区里真的被放行"时打，便于事后审计"谁被授予了区内写入"）。 */
    public static void logAllow(BlockPos pos, WriteReason reason, Decision decision) {
        if (decision.allowed()) {
            BotLog.info("[ZoneAuthority] ALLOW pos={} reason={} {}", pos.toShortString(),
                    reason == null ? "-" : reason.name(), decision.detail());
        }
    }

    private static String shortId(UUID owner) {
        return owner == null ? "-" : owner.toString().substring(0, 8);
    }
}
