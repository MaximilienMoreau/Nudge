package com.nudge.dto;

import com.nudge.model.TrackingEvent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

/** One line of an email's history. The IP address is deliberately left out. */
public record EmailEventDTO(String type, LocalDateTime at, boolean counted, String reason, String source) {

    public static EmailEventDTO of(TrackingEvent event) {
        // Hibernate returns server-local times; the API speaks UTC.
        LocalDateTime utc = event.getTimestamp().atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        String reason = event.getFilterReason() != null ? event.getFilterReason().name()
                : event.isSuspectedBot() ? "OTHER" : null;
        return new EmailEventDTO(event.getType().name(), utc, !event.isSuspectedBot(), reason, source(event.getUserAgent()));
    }

    static String source(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("googleimageproxy")) return "Gmail";
        if (ua.contains("yahoomailproxy"))   return "Yahoo Mail";
        if (ua.startsWith("hey.com/"))       return "HEY";
        if (ua.contains("outlook"))          return "Outlook";
        if (ua.equals("mozilla/5.0"))        return "Apple Mail";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("android")) return "Mobile";
        if (ua.contains("windows") || ua.contains("macintosh") || ua.contains("linux")) return "Computer";
        return "Other";
    }
}
