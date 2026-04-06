package com.nudge.model;

/** Type of tracking event recorded for an email. */
public enum EventType {
    OPEN,   // Recipient opened the email (tracking pixel loaded)
    CLICK   // Reserved for future link-click tracking
}
