package com.nudge.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for IpUtils — verifies exact-match and real CIDR bitmask logic.
 *
 * The previous startsWith() implementation would incorrectly match:
 *   "100.1.2.3" against "10.0.0.0/8"  (startsWith("10") → true, wrong)
 * These tests guard against that regression.
 */
class IpUtilsTest {

    // ── Exact IPv4 ────────────────────────────────────────────────────────────

    @Test
    void exactIpv4_matches() {
        assertThat(IpUtils.matches("192.168.1.5", "192.168.1.5")).isTrue();
    }

    @Test
    void exactIpv4_doesNotMatch_differentAddress() {
        assertThat(IpUtils.matches("192.168.1.6", "192.168.1.5")).isFalse();
    }

    // ── CIDR IPv4 ─────────────────────────────────────────────────────────────

    @Test
    void cidr_slash8_matchesAddressInRange() {
        assertThat(IpUtils.matches("10.20.30.40", "10.0.0.0/8")).isTrue();
    }

    @Test
    void cidr_slash8_doesNotMatch_addressOutsideRange() {
        // "100.x.x.x" starts with "10" as a string — old bug, should be false
        assertThat(IpUtils.matches("100.1.2.3", "10.0.0.0/8")).isFalse();
    }

    @Test
    void cidr_slash16_matchesAddressInRange() {
        assertThat(IpUtils.matches("172.16.5.10", "172.16.0.0/16")).isTrue();
    }

    @Test
    void cidr_slash16_doesNotMatch_addressOutsideRange() {
        assertThat(IpUtils.matches("172.17.0.1", "172.16.0.0/16")).isFalse();
    }

    @Test
    void cidr_slash24_matchesAddressInRange() {
        assertThat(IpUtils.matches("192.168.1.200", "192.168.1.0/24")).isTrue();
    }

    @Test
    void cidr_slash24_doesNotMatch_addressOutsideRange() {
        assertThat(IpUtils.matches("192.168.2.1", "192.168.1.0/24")).isFalse();
    }

    @Test
    void cidr_slash32_matchesOnlyExactHost() {
        assertThat(IpUtils.matches("1.2.3.4", "1.2.3.4/32")).isTrue();
        assertThat(IpUtils.matches("1.2.3.5", "1.2.3.4/32")).isFalse();
    }

    @Test
    void cidr_slash0_matchesEverything() {
        assertThat(IpUtils.matches("1.2.3.4",   "0.0.0.0/0")).isTrue();
        assertThat(IpUtils.matches("255.0.0.1", "0.0.0.0/0")).isTrue();
    }

    // ── Exact IPv6 ────────────────────────────────────────────────────────────

    @Test
    void ipv6_exactMatch() {
        assertThat(IpUtils.matches("::1", "::1")).isTrue();
    }

    @Test
    void ipv6_doesNotMatch_differentAddress() {
        assertThat(IpUtils.matches("::2", "::1")).isFalse();
    }

    @Test
    void ipv6_withSlash_treatedAsNoMatch() {
        // IPv6 CIDR not supported — should not blow up, just return false
        assertThat(IpUtils.matches("::1", "::1/128")).isFalse();
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void nullInputs_returnFalse() {
        assertThat(IpUtils.matches(null, "10.0.0.0/8")).isFalse();
        assertThat(IpUtils.matches("10.0.0.1", null)).isFalse();
    }

    @Test
    void invalidCidr_returnsFalse() {
        assertThat(IpUtils.matches("10.0.0.1", "10.0.0.0/bad")).isFalse();
        assertThat(IpUtils.matches("10.0.0.1", "10.0.0.0/33")).isFalse();
    }
}
