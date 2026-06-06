package com.nudge.util;

/**
 * Utility methods for IP address / CIDR range matching.
 *
 * Used by both TrackingService and RateLimitFilter to decide whether to
 *     trust X-Forwarded-For. Centralised here so both use identical logic and
 *     neither can be trivially bypassed by IP spoofing.
 *
 * Supported formats:
 *   - Exact IPv4 match          "192.168.1.5"
 *   - CIDR IPv4 notation        "10.0.0.0/8"  (proper bitmask, not prefix string)
 *   - Exact IPv6 match          "::1"
 *   (IPv6 CIDR is not supported — treated as exact match.)
 *
 * The previous implementation used String.startsWith() for CIDR ranges, which
 * gives wrong results for e.g. "10.0.0.0/8" matching "100.1.2.3".
 */
public final class IpUtils {

    private IpUtils() {}

    /**
     * Returns {@code true} when {@code ip} falls within {@code cidrOrExact}.
     *
     * @param ip          the client IP address to test (IPv4 or IPv6)
     * @param cidrOrExact an exact IP or an IPv4 CIDR range ("a.b.c.d/prefix")
     */
    public static boolean matches(String ip, String cidrOrExact) {
        if (ip == null || cidrOrExact == null || cidrOrExact.isBlank()) return false;

        // No slash → exact match only
        if (!cidrOrExact.contains("/")) {
            return ip.equals(cidrOrExact);
        }

        // IPv6 with a slash — not supported, treat as no match
        if (ip.contains(":")) return false;

        String[] parts = cidrOrExact.split("/", 2);
        int prefixLen;
        try {
            prefixLen = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (prefixLen < 0 || prefixLen > 32) return false;

        int networkAddr = ipv4ToInt(parts[0].trim());
        int clientAddr  = ipv4ToInt(ip);
        if (networkAddr == Integer.MIN_VALUE || clientAddr == Integer.MIN_VALUE) return false;

        // Build the bitmask: prefixLen=8 → 0xFF000000
        // Using arithmetic shift on unsigned value via long
        int mask = prefixLen == 0 ? 0 : (int)(0xFFFFFFFFL << (32 - prefixLen));

        return (networkAddr & mask) == (clientAddr & mask);
    }

    /**
     * Converts a dotted-decimal IPv4 string to a 32-bit signed int.
     * Returns {@link Integer#MIN_VALUE} on parse error (sentinel, never a valid IP int).
     */
    static int ipv4ToInt(String ipv4) {
        String[] octets = ipv4.split("\\.", -1);
        if (octets.length != 4) return Integer.MIN_VALUE;
        int result = 0;
        for (String octet : octets) {
            try {
                int val = Integer.parseInt(octet.trim());
                if (val < 0 || val > 255) return Integer.MIN_VALUE;
                result = (result << 8) | val;
            } catch (NumberFormatException e) {
                return Integer.MIN_VALUE;
            }
        }
        return result;
    }
}
