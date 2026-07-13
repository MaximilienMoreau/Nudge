package com.nudge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for EncryptionService (AES-256-GCM, key versioning, legacy fallback).
 * No Spring context needed — fields are set directly and init() is invoked manually.
 */
class EncryptionServiceTest {

    private static final String KEY_V1 = "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=";
    private static final String KEY_V2 = "MTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTE=";

    private EncryptionService service;

    @BeforeEach
    void setUp() {
        service = new EncryptionService();
        ReflectionTestUtils.setField(service, "keyV1Base64", KEY_V1);
        ReflectionTestUtils.setField(service, "keyV2Base64", "");
        ReflectionTestUtils.setField(service, "activeVersion", "v1");
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    @Test
    void encrypt_thenDecrypt_roundTrips() {
        String plaintext = "Hey, following up on our call yesterday.";
        String encrypted = service.encrypt(plaintext);

        assertThat(encrypted).startsWith("v1:").isNotEqualTo(plaintext);
        assertThat(service.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_returnsNull_forNullInput() {
        assertThat(service.encrypt(null)).isNull();
    }

    @Test
    void decrypt_returnsNull_forNullInput() {
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void encrypt_isNonDeterministic_dueToRandomIv() {
        String plaintext = "same content";
        String first  = service.encrypt(plaintext);
        String second = service.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo(plaintext);
        assertThat(service.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_supportsLegacyFormat_withNoVersionPrefix() {
        // Legacy rows were written before version prefixes existed: just Base64(IV ‖ ciphertext).
        String plaintext = "legacy content";
        String versioned = service.encrypt(plaintext);
        String legacy = versioned.substring(versioned.indexOf(':') + 1);

        assertThat(service.decrypt(legacy)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_throws_onCorruptedCiphertext() {
        String encrypted = service.encrypt("some content");
        String corrupted = encrypted.substring(0, encrypted.length() - 4) + "abcd";

        assertThatThrownBy(() -> service.decrypt(corrupted))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_returnsNull_forUnknownKeyVersion() {
        String encrypted = service.encrypt("some content");
        String unknownVersion = "v9:" + encrypted.substring(encrypted.indexOf(':') + 1);

        assertThat(service.decrypt(unknownVersion)).isNull();
    }

    @Test
    void keyRotation_newWritesUseV2_oldReadsStillDecryptWithV1() {
        ReflectionTestUtils.setField(service, "keyV2Base64", KEY_V2);
        ReflectionTestUtils.setField(service, "activeVersion", "v2");
        ReflectionTestUtils.invokeMethod(service, "init");

        String legacyV1Encrypted = encryptWithV1Only("still readable after rotation");
        String newV2Encrypted    = service.encrypt("written after rotation");

        assertThat(newV2Encrypted).startsWith("v2:");
        assertThat(service.decrypt(newV2Encrypted)).isEqualTo("written after rotation");
        assertThat(service.decrypt(legacyV1Encrypted)).isEqualTo("still readable after rotation");
    }

    @Test
    void init_throws_whenActiveKeyVersionIsNotConfigured() {
        EncryptionService misconfigured = new EncryptionService();
        ReflectionTestUtils.setField(misconfigured, "keyV1Base64", KEY_V1);
        ReflectionTestUtils.setField(misconfigured, "keyV2Base64", "");
        ReflectionTestUtils.setField(misconfigured, "activeVersion", "v2");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(misconfigured, "init"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void init_throws_whenKeyIsNotThirtyTwoBytes() {
        EncryptionService misconfigured = new EncryptionService();
        ReflectionTestUtils.setField(misconfigured, "keyV1Base64", "dG9vc2hvcnQ=");
        ReflectionTestUtils.setField(misconfigured, "keyV2Base64", "");
        ReflectionTestUtils.setField(misconfigured, "activeVersion", "v1");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(misconfigured, "init"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private String encryptWithV1Only(String plaintext) {
        EncryptionService v1Only = new EncryptionService();
        ReflectionTestUtils.setField(v1Only, "keyV1Base64", KEY_V1);
        ReflectionTestUtils.setField(v1Only, "keyV2Base64", "");
        ReflectionTestUtils.setField(v1Only, "activeVersion", "v1");
        ReflectionTestUtils.invokeMethod(v1Only, "init");
        return v1Only.encrypt(plaintext);
    }
}
