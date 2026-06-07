package com.nudge.controller;

import com.nudge.service.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Locale;

/**
 * Public tracking endpoints — no auth required.
 * Called automatically by email clients or recipient browsers.
 */
@RestController
@RequestMapping("/track")
public class TrackingController {

    private static final Logger log = LoggerFactory.getLogger(TrackingController.class);

    /** 1x1 transparent GIF89a (43 bytes). */
    private static final byte[] TRACKING_PIXEL = {
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
        0x01, 0x00, 0x01, 0x00,
        (byte) 0x80, 0x00, 0x00,
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
        0x00, 0x00, 0x00,
        0x21, (byte) 0xF9, 0x04, 0x01,
        0x00, 0x00, 0x00, 0x00,
        0x2C,
        0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00,
        0x02, 0x02, 0x44, 0x01, 0x00,
        0x3B
    };

    private static final int MAX_REDIRECT_URL_LENGTH = 2048;

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    /**
     * GET /track/open/{trackingId}
     *
     * Records an OPEN event and returns a 1x1 transparent GIF.
     * Cache headers prevent email clients from pre-fetching and skewing counts.
     */
    @GetMapping("/open/{trackingId}")
    public ResponseEntity<byte[]> trackOpen(@PathVariable String trackingId,
                                            HttpServletRequest request) {
        boolean found = trackingService.recordOpen(trackingId, request);
        if (!found) {
            log.warn("Unknown tracking ID (open): {}", trackingId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/gif"));
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        headers.setPragma("no-cache");
        headers.setExpires(0);

        return new ResponseEntity<>(TRACKING_PIXEL, headers, HttpStatus.OK);
    }

    /**
     * GET /track/click/{trackingId}?url=https://original.link
     *
     * Records a CLICK event and 302-redirects to the original URL.
     * Embed links in emails as: /track/click/{trackingId}?url=<encoded original URL>
     *
     * Only http:// and https:// schemes are allowed to prevent open redirect abuse
     * (javascript:, data:, file:, etc. are rejected with 400).
     */
    @GetMapping("/click/{trackingId}")
    public ResponseEntity<Void> trackClick(@PathVariable String trackingId,
                                           @RequestParam(required = false) String url,
                                           HttpServletRequest request) {
        boolean found = trackingService.recordClick(trackingId, request);
        if (!found) {
            log.warn("Unknown tracking ID (click): {}", trackingId);
        }

        String destination = resolveDestination(url);
        if (destination == null) {
            log.warn("Rejected redirect for tracking ID {}: missing or unsafe URL", trackingId);
            return ResponseEntity.badRequest().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(destination));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    /**
     * Validates and returns the redirect destination, or null if rejected.
     * Only http and https schemes are permitted; the URL must also be syntactically valid.
     */
    private static String resolveDestination(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.length() > MAX_REDIRECT_URL_LENGTH) return null;

        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;

        try {
            URI.create(url);
            return url;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
