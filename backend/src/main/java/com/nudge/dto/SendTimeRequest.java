package com.nudge.dto;

/** Optional body of the send-time endpoint: the caller's IANA time zone. */
public record SendTimeRequest(String timeZone) {}
