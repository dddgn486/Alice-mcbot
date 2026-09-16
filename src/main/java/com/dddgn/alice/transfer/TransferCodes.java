package com.dddgn.alice.transfer;

/** Stable machine-readable transfer result codes. */
public final class TransferCodes {
    public static final String INVALID_ITEM_ID = "invalid_item_id";
    public static final String INVALID_COUNT = "invalid_count";
    public static final String UNAUTHORIZED_ACTOR = "unauthorized_actor";
    public static final String BOT_UNAVAILABLE = "bot_unavailable";
    public static final String DUPLICATE_REQUEST = "duplicate_request";
    public static final String ENDPOINT_NOT_LOADED = "endpoint_not_loaded";
    public static final String ENDPOINT_NOT_SINGLE_CHEST = "endpoint_not_single_chest";
    public static final String ENDPOINT_HANDLER_UNAVAILABLE = "endpoint_handler_unavailable";
    public static final String SAME_ENDPOINT_REJECTED = "same_endpoint_rejected";
    public static final String CROSS_DIMENSION_REJECTED = "cross_dimension_rejected";
    public static final String UNSUPPORTED_ITEM_COMPONENTS = "unsupported_item_components";
    public static final String CAPACITY_REJECTED = "capacity_rejected";
    public static final String SOURCE_INSUFFICIENT = "source_insufficient";
    public static final String SIMULATION_CONFLICT = "simulation_conflict";
    public static final String SOURCE_DELTA_MISMATCH = "source_delta_mismatch";
    public static final String DESTINATION_DELTA_MISMATCH = "destination_delta_mismatch";
    public static final String EXTERNAL_INTERFERENCE = "external_interference";
    public static final String HARD_PATH_UNREACHABLE = "hard_path_unreachable";
    public static final String HARD_PATH_SEARCH_LIMIT = "hard_path_search_limit";
    public static final String HARD_PATH_FAILED = "hard_path_failed";
    public static final String SURVIVAL_LAVA_CONTACT = "survival_lava_contact";
    public static final String TIMEOUT = "timeout";
    // ---- 2026-09-13（R3 + L1）：容器写入纳入授权/预算 + 触及校验 ----
    /** 容器写入预算用尽（与方块写入同一套语义：超限即拒绝，如实失败）。 */
    public static final String CONTAINER_BUDGET_EXHAUSTED = "container_budget_exhausted";
    /** 端点周围找不到可站立点（L1：不再要求"站到方块顶上"）。 */
    public static final String ENDPOINT_NO_STANDING_POINT = "endpoint_no_standing_point";
    /** 站好了但**够不到**端点（L1：按原版触及语义校验）。 */
    public static final String ENDPOINT_OUT_OF_REACH = "endpoint_out_of_reach";
    // ---- 2026-09-13（L2 生产化）：菜单路线 ----
    /** 该容器**不在语义表**里 ⇒ 菜单路线不支持（不猜槽位语义，如实失败）。 */
    public static final String UNSUPPORTED_CONTAINER = "unsupported_container";
    /** 菜单路线失败（打开超时/目标不符/中途失效…）——具体原因在账本证据里。 */
    public static final String CONTAINER_MENU_FAILED = "container_menu_failed";
    /** bot 背包没有空位放取出的物品。 */
    public static final String BOT_INVENTORY_FULL = "bot_inventory_full";
    /** 目标容器已满，放不下。 */
    public static final String DESTINATION_FULL = "destination_full";
    public static final String SERVER_RESTART = "server_restart";
    public static final String MANUAL_TAKEOVER_REQUIRED = "manual_takeover_required";
    /**
     * **挂起结清：物品从未进过 bot 背包**（§5.9 / 2026-09-15）。
     *
     * <p>重启时把"没动过物品"的未完成请求落成这个终态，而不是 `SUSPENDED` —— 挂起会让该 bot 的
     * `assign*` 通路（walk/follow/place/transfer/维生自检）**永久**被 `blocksBot` 挡住，
     * 而这类条目**没有任何"人工接管"的语义**（物品还在源容器，或压根没动）。
     */
    public static final String ABORTED_NO_BOT_INVENTORY = "aborted_no_bot_inventory";
    public static final String UNKNOWN_DISCREPANCY = "unknown_discrepancy";
    public static final String TRANSFER_VERIFIED = "transfer_verified";
    public static final String DEFAULT_ITEM_UNAVAILABLE = "default_item_unavailable";
    /** §5.9 C1（2026-09-16 用户裁定「甲」）：操作者**显式确认**后解除阻塞（放弃追踪，**不动物品**）。 */
    public static final String RESOLVED_BY_OPERATOR = "resolved_by_operator";

    private TransferCodes() {
    }
}
