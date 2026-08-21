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
    public static final String SURVIVAL_SUFFOCATING = "survival_suffocating";
    public static final String TIMEOUT = "timeout";
    public static final String ACTOR_DISCONNECT = "actor_disconnect";
    public static final String SERVER_RESTART = "server_restart";
    public static final String BOT_MISSING = "bot_missing";
    public static final String MANUAL_TAKEOVER_REQUIRED = "manual_takeover_required";
    public static final String UNKNOWN_DISCREPANCY = "unknown_discrepancy";
    public static final String TRANSFER_VERIFIED = "transfer_verified";
    public static final String DEFAULT_ITEM_UNAVAILABLE = "default_item_unavailable";

    private TransferCodes() {
    }
}
