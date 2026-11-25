package com.nudge.model;

/** Why a tracking event is stored but not counted as a read. */
public enum FilterReason {
    /** Mail scanner or security gateway. */
    SCANNER,
    /** Same pixel fetched again within a few seconds. */
    RAPID_REFETCH,
    /** Apple Mail Privacy Protection: fetched whether or not the recipient reads. */
    APPLE_PRIVACY,
    /** Proton Mail loads images on reception, not when the recipient reads. */
    PROTON_PRELOAD,
    /** The sender's own browser, while composing. */
    SENDER;

    /** Loaded automatically by the recipient's mailbox: says nothing about reading. */
    public boolean isAutomaticLoad() {
        return this == APPLE_PRIVACY || this == PROTON_PRELOAD;
    }
}
