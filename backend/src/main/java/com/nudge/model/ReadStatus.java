package com.nudge.model;

/** Single definition of the read status shown for an email. */
public final class ReadStatus {

    private ReadStatus() {}

    public static String of(long opens, long clicks, boolean loadedAutomatically) {
        if (opens > 1) return "Opened Multiple Times";
        // A click proves the email was read even when images are blocked.
        if (opens == 1 || clicks > 0) return "Opened";
        return loadedAutomatically ? "Unconfirmed" : "Not Opened";
    }
}
