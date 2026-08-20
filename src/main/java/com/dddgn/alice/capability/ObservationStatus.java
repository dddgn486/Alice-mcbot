package com.dddgn.alice.capability;

/** Result of one synchronous server-side capability observation. */
public enum ObservationStatus {
    OK,
    NO_BLOCK_ENTITY,
    CHUNK_NOT_LOADED,
    CAPTURE_ERROR
}
