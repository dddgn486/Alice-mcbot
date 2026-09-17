package com.dddgn.alice.action;

/**
 * **菜单交互的稳定失败码**（L2，2026-09-13）。与 `TransferCodes` 同风格：机器可读、可归因、可断言。
 *
 * <p>为什么单列一套：方块写入的失败码（`WriteBudget`/`TransferCodes`）描述的是"权限/预算/路径"，
 * 而菜单路线的失败发生在**另一个维度**——打开、槽位、中途失效。混在一起会让日志里
 * "到底是没权限还是菜单没开"分不清。
 */
public final class MenuCodes {

    /** `useItemOn` 没能开出菜单（方块没有 `MenuProvider`／被模组拒绝／朝向不对）。 */
    public static final String MENU_OPEN_FAILED = "menu_open_failed";
    /** 开了但迟迟没变成"真菜单"（超时）—— 与服务端同步/模组延迟有关。 */
    public static final String MENU_OPEN_TIMEOUT = "menu_open_timeout";
    /** 打开的不是我们预期的那个容器（例如点到了别的方块）。 */
    public static final String MENU_TARGET_MISMATCH = "menu_target_mismatch";
    /** 操作中途菜单失效（玩家走远／容器被拆／被服务器关闭）。 */
    public static final String MENU_CLOSED_EARLY = "menu_closed_early";
    /** 槽位语义不匹配（找不到目标槽／槽位类型不对）。 */
    public static final String MENU_SLOT_MISMATCH = "menu_slot_mismatch";
    /** K-3 门：此刻（空中/未站定）**不允许**打开菜单。 */
    public static final String MENU_NOT_SETTLED = "menu_not_settled";
    /** **画像拒绝开箱**（D-291）：`RiskProfile.of(bot).containerAccess()==false` ⇒ 策略性拒绝（不是失败）✓。 */
    public static final String PROFILE_DENIES_CONTAINER = "profile_denies_container";
    /** 容器已满，放不下。 */
    public static final String MENU_CONTAINER_FULL = "menu_container_full";

    private MenuCodes() {
    }
}
