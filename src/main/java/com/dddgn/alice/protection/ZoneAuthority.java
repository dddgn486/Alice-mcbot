package com.dddgn.alice.protection;

import com.dddgn.alice.action.WritePolicyMatrix;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashSet;
import java.util.Set;
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

    /**
     * ⭐ `D-341`：**这个拒绝码是"永久没有权限"，还是"暂时/搜索性的"？**
     *
     * <p><b>为什么要区分（"无权" ≠ "没有"）</b>：区域作业 `RegionLumberJob` 的候选扫描把**没权限的树
     * 直接丢进 `rejected`**、不进 `viable` ⇒ 作业的世界模型变成"区域里没有树" ⇒ 走**待机巡查等生长**
     * 分支（那是为树苗生长设计的正常机制）。客户端实测（2026-09-19 19:06）：LLM 自起的 `region_lumber`
     * 在保护区内被封顶 `L1`、5 棵树全 `zone_break_not_allowed` ⇒ `viable=0 inRegion=0` +
     * `欠树 deficit=5 但当前没有可补种的位置`，**每 ~2 s 一行、一直转到 `maxTicks=24000`（20 分钟）**，
     * 期间反复唤醒 LLM。用户口径：**"任务要如实失败，不能继续跑"**。
     *
     * <p>⇒ 本函数是**唯一的分类出处**：作业据此把"树全被永久拒绝"判成 `FAILED`，而把
     * `trunk_too_tall` / `not_nearest` / `no_stand` 这类**搜索性或策略性**理由继续当"暂时没有"（照旧等）。
     *
     * <p><b>收录的码</b>：{@code protected_area}（在保护区里且**没有**生效任务区）/
     * {@code protected_safe_zone}（安全区，任务区不可能覆盖）/ {@code zone_read_only}（`L0`）/
     * {@code zone_break_not_allowed}（`L1` 不许破坏）/ {@code zone_place_not_scaffold}（`L1` 只许临时放置）/
     * {@code protected_block} / {@code protected_tag}（玩家设的全世界通用黑名单 —— 也不是"等一下就会变"）。
     *
     * <p>⚠️ **刻意不收录**：`trunk_too_tall`、`not_nearest`、`no_stand`、`unreachable`、`search_limit` 之类
     * —— 那些是"目标此刻做不了"，作业该照旧等/换目标，**不是权限问题**。
     *
     * @param code 候选/动作层给出的拒绝码（`null`/空 ⇒ `false`）
     */
    public static boolean permanentDenial(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        // 码可能带括号后缀（如 `trunk_too_tall(unreachable=…)`）⇒ 按分隔符取主干
        String head = code;
        int cut = head.indexOf('(');
        if (cut > 0) {
            head = head.substring(0, cut);
        }
        return switch (head.trim()) {
            case "protected_area", "protected_safe_zone", "protected_block", "protected_tag",
                 "zone_read_only", "zone_break_not_allowed", "zone_place_not_scaffold" -> true;
            default -> false;
        };
    }

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
        // ⭐ `D-338` 附注十四：**保护区内，非玩家发起（LLM/未归因）的任务区封顶 `L1`** ——
        // 走到这里就已经确定"这一格在**已认领**区块里"（未认领早在上面的 `isClaimed` 就返回 `NOT_GATED`），
        // 所以封顶不需要再判区块：**非玩家发起 ⇒ 只能收紧**（拆不了玩家的方块；临时垫脚仍然允许）。
        WritePolicyMatrix.Level declared = zone.level();
        WritePolicyMatrix.Level level0 = zone.effectiveLevel();
        String capNote = level0 == declared ? ""
                : "【保护区内·非玩家发起（LLM/未归因）⇒ 从 " + declared.label() + " 封顶 " + level0.label() + "】";
        if (act == Act.BREAK) {
            if (!level0.allowsBreak()) {
                return new Decision(Verdict.DENY,
                        level0 == WritePolicyMatrix.Level.L0_READ_ONLY ? "zone_read_only" : "zone_break_not_allowed",
                        "任务区 " + zone.kind() + " 等级 " + level0.label() + capNote
                                + (level0 == WritePolicyMatrix.Level.L0_READ_ONLY ? "（只读）" : "（临时脚手架 ⇒ 不许破坏）"));
            }
            return new Decision(Verdict.ALLOW, null,
                    "任务区 " + zone.kind() + " 等级 " + level0.label() + capNote
                            + " ⇒ 放行破坏（理由 " + reason.name() + "）");
        }
        if (!level0.allowsPlace()) {
            return new Decision(Verdict.DENY, "zone_read_only",
                    "任务区 " + zone.kind() + " 等级 " + level0.label() + capNote + "（只读）⇒ 不许放置");
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
                "任务区 " + zone.kind() + " 等级 " + level0.label() + capNote
                        + " ⇒ 放行放置（理由 " + reason.name() + "）");
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
        Decision decision = authorize(level, owner, pos, reason, act);
        logAllow(pos, reason, decision);
        return decision.refusal();
    }

    /**
     * **候选扫描专用**：**同一判据**，但**不写日志** —— 候选扫描是逐方块热路径
     * （一次巡查可能问几十上百格），逐块留痕会把日志刷成噪声；真正的"写入放行"由动作层
     * （{@link #regionRefusal}）留一行。
     */
    public static String candidateRefusal(ServerLevel level, UUID owner, BlockPos pos, String worldProtection,
                                         WriteReason reason) {
        return silentRefusal(level, owner, pos, worldProtection, reason, Act.BREAK);
    }

    /**
     * ⭐ **规划/执行期专用**（**不留痕**，与 {@link #regionRefusal} 同一判据）：{@code null} = 放行。
     *
     * <p>消费者 = {@code CapabilityGate.Facts}（能力闸门在**执行每一条 Movement 之前**复验：
     * "这条会改世界的移动，落点是不是受保护"）。⚠️ **2026-09-19 客户端实测暴露**：这一处此前走的是
     * **裸 `SafeZoneData.protectionReason`** ⇒ 在保护区里连 `L2` 任务区授权的 `PILLAR`
     * （"垫一格上去"）都被拒（`ZONE_PROTECTED_AREA` ×144，全轮无一次垫脚放置）⇒ **这是第四处消费点，
     * 我上一片漏接了**（当时只接了候选扫描 ×2 / 破坏闸门 / 放置闸门）。
     */
    public static String silentRefusal(ServerLevel level, UUID owner, BlockPos pos, String worldProtection,
                                       WriteReason reason, Act act) {
        if (worldProtection == null) {
            return null;
        }
        if (!"protected_area".equals(worldProtection)) {
            return worldProtection;
        }
        return authorize(level, owner, pos, reason, act).refusal();
    }

    /**
     * **会改世界的移动**（`CapabilityGate`）的落点判定：破坏类按 `PATH_ACCESS`（清障语义）、
     * 放置类按 `STEP_PLACEMENT`（临时脚手架语义）—— 于是 `L1` 允许"垫脚"、`L2` 两者都允许、
     * 没有任务区时**逐字**仍是 `protected_area`（既有码不变）。
     */
    public static String movementRefusal(ServerLevel level, UUID owner, BlockPos pos, String worldProtection,
                                         boolean placing) {
        return silentRefusal(level, owner, pos, worldProtection,
                placing ? WriteReason.STEP_PLACEMENT : WriteReason.PATH_ACCESS,
                placing ? Act.PLACE : Act.BREAK);
    }

    /** 便捷入口（破坏）：{@code null} = 允许。 */
    public static String breakRefusal(ServerLevel level, UUID owner, BlockPos pos, WriteReason reason) {
        return authorize(level, owner, pos, reason, Act.BREAK).refusal();
    }

    /** 便捷入口（放置）：{@code null} = 允许。 */
    public static String placeRefusal(ServerLevel level, UUID owner, BlockPos pos, WriteReason reason) {
        return authorize(level, owner, pos, reason, Act.PLACE).refusal();
    }

    /**
     * **留痕上限**（条）：同一格 + 同一理由**只留一次**，且总数有上限。
     *
     * <p>为什么必须去重（**客户端实测逼出来的**，2026-09-19）：{@code BlockBreakSafety} 不只被**动作层**调用，
     * 还被**规划期**的候选谓词反复调用（同一个 `pos`+`reason` 在 50 ms 内被问 5 次）⇒ 不去重的话
     * 一轮区域伐木会刷出成千上万行 `ALLOW`，把真日志淹掉。行为一条没改，改的只是**打印次数**。
     */
    public static final int AUDIT_CAP = 512;

    private static final Set<String> AUDITED = new LinkedHashSet<>();
    private static boolean auditSaturated;

    /**
     * 放行时留一行（同一格 + 同一理由只留一次；总数到 {@link #AUDIT_CAP} 后不再逐条打，只报一次饱和）。
     * 目的：事后能审计"**谁被授予了区内写入**"，同时不把日志刷成噪声。
     */
    public static void logAllow(BlockPos pos, WriteReason reason, Decision decision) {
        if (!decision.allowed() || pos == null) {
            return;
        }
        String key = pos.asLong() + "|" + (reason == null ? "-" : reason.name());
        if (!AUDITED.add(key)) {
            return;
        }
        if (AUDITED.size() > AUDIT_CAP) {
            if (!auditSaturated) {
                auditSaturated = true;
                BotLog.info("[ZoneAuthority] 审计留痕已达上限 {} 条 ⇒ 后续放行不再逐条打印"
                        + "（**闸门行为不变**，只是不再打印；真要逐次审计看 `[WRITE]` / 账本）", AUDIT_CAP);
            }
            return;
        }
        BotLog.info("[ZoneAuthority] ALLOW pos={} reason={} {}（同一格+同一理由只留痕一次）",
                pos.toShortString(), reason == null ? "-" : reason.name(), decision.detail());
    }

    /** 已留痕的 (格, 理由) 条数（夹具据此断言"重复问同一格不会重复刷日志"）。 */
    public static int auditLoggedCount() {
        return AUDITED.size();
    }

    /** **夹具/收尾专用**：清空留痕去重表（不影响任何授权判定）。 */
    public static void clearAudit() {
        AUDITED.clear();
        auditSaturated = false;
    }

    private static String shortId(UUID owner) {
        return owner == null ? "-" : owner.toString().substring(0, 8);
    }
}
