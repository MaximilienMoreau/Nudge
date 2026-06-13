package com.nudge.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * S8 + KR: AES-256-GCM encryption with key versioning (envelope encryption pattern).
 *
 * ── Wire format ──────────────────────────────────────────────────────────────
 *   New values:    "{version}:{Base64(IV[12] ‖ ciphertext+authTag)}"
 *                  e.g. "v1:AAEC..."
 *   Legacy values: "{Base64(IV[12] ‖ ciphertext+authTag)}"   (no version prefix)
 *                  Decrypted with the active key for backward compatibility.
 *
 * ── Key rotation workflow ────────────────────────────────────────────────────
 *   1. Generate a new key and export it as a Base64-encoded 32-byte value.
 *   2. Set ENCRYPTION_KEY_V2=<new-key> and ENCRYPTION_ACTIVE_KEY_VERSION=v2.
 *   3. Restart. New writes use v2; old v1 (and legacy) values still decrypt.
 *   4. Background-migrate old rows at your own pace: re-encrypt with
 *      encrypt(decrypt(value)) — the result will carry the new version prefix.
 *
 * ── Configuration ────────────────────────────────────────────────────────────
 *   app.encryption.key            Base64 AES-256 key for version "v1"
 *   app.encryption.key.v2         Optional Base64 key for version "v2" (rotation)
 *   app.encryption.active-key-version   Which version to use for new encryptions
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    private static final int    IV_BYTES        = 12;
    private static final int    TAG_BITS        = 128;

    @Value("${app.encryption.key}")
    private String keyV1Base64;

    @Value("${app.encryption.key.v2:}")
    private String keyV2Base64;

    @Value("${app.encryption.active-key-version:v1}")
    private String activeVersion;

    private final SecureRandom random = new SecureRandom();

    /** version string → SecretKey, built at startup */
    private Map<String, SecretKey> keychain;
    private SecretKey activeKey;

    @PostConstruct
    void init() {
        keychain = new HashMap<>();

        SecretKey v1 = buildKey(keyV1Base64, "v1");
        keychain.put("v1", v1);

        if (keyV2Base64 != null && !keyV2Base64.isBlank()) {
            keychain.put("v2", buildKey(keyV2Base64, "v2"));
            log.info("EncryptionService: v2 key loaded (rotation available)");
        }

        activeKey = keychain.get(activeVersion);
        if (activeKey == null) {
            throw new IllegalStateException(
                    "Active encryption key version '" + activeVersion + "' is not configured");
        }

        log.debug("EncryptionService ready — active version: {}, keychain: {}",
                activeVersion, keychain.keySet());
    }

    /**
     * Encrypt plaintext. Returns null if input is null.
     * Output format: "{activeVersion}:{Base64(IV ‖ ciphertext)}"
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv,         0, combined, 0,       IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.length);

            return activeVersion + ":" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a value produced by encrypt() or by the previous single-key
     * implementation (no version prefix — treated as legacy v1 data).
     * Returns null if input is null or decryption fails.
     */
    public String decrypt(String encrypted) {
        if (encrypted == null) return null;
        try {
            SecretKey key;
            String payload;

            int colonIdx = encrypted.indexOf(':');
            if (colonIdx > 0 && colonIdx <= 3 && encrypted.charAt(0) == 'v') {
                // Versioned format: "v1:..." or "v2:..."
                String version = encrypted.substring(0, colonIdx);
                key     = keychain.get(version);
                payload = encrypted.substring(colonIdx + 1);
                if (key == null) {
                    log.error("Unknown encryption key version '{}' — cannot decrypt", version);
                    return null;
                }
            } else {
                // Legacy format: no version prefix, decrypt with active key (v1)
                key     = keychain.get("v1");
                payload = encrypted;
            }

            byte[] combined = Base64.getDecoder().decode(payload);

            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);

            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("Decryption failed (data corruption or key mismatch): {}", e.getMessage());
            throw new IllegalStateException("Content could not be decrypted", e);
        }
    }

    private SecretKey buildKey(String base64, String version) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length != 32) {
            throw new IllegalArgumentException(
                    "Encryption key '" + version + "' must decode to 32 bytes, got " + bytes.length);
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
