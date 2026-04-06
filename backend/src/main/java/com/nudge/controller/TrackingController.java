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

/**
 * Public tracking endpoint — no auth required.
 * Called automatically by email clients when the tracking pixel is loaded.
 */
@RestController
@RequestMapping("/track")
public class TrackingController {

    private static final Logger log = LoggerFactory.getLogger(TrackingController.class);

    /**
     * 1x1 transparent GIF pixel.
     * This is the standard minimal GIF89a format (43 bytes).
     */
    private static final byte[] TRACKING_PIXEL = {
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
        0x01, 0x00,                           // Width = 1
        0x01, 0x00,                           // Height = 1
        (byte) 0x80, 0x00, 0x00,             // Global Color Table (2 colors)
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // Color 0: white
        0x00, 0x00, 0x00,                     // Color 1: black
        0x21, (byte) 0xF9, 0x04, 0x01,       // Graphic Control Extension
        0x00, 0x00, 0x00, 0x00,              // Transparent color index 0, no delay
        0x2C,                                 // Image Descriptor
        0x00, 0x00, 0x00, 0x00,             // Left=0, Top=0
        0x01, 0x00, 0x01, 0x00, 0x00,       // Width=1, Height=1, no local table
        0x02, 0x02, 0x44, 0x01, 0x00,       // Image Data (LZW compressed)
        0x3B                                  // GIF Trailer
    };

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    /**
     * GET /track/open/{trackingId}
     *
     * Called when an email client loads the tracking pixel.
     * - Records an OPEN event in the database
     * - Sends a real-time WebSocket notification to the sender
     * - Returns a 1x1 transparent GIF (so it's invisible to the recipient)
     *
     * Cache headers are set to prevent pre-fetching from skewing open counts.
     */
    @GetMapping("/open/{trackingId}")
    public ResponseEntity<byte[]> trackOpen(@PathVariable String trackingId,
                                            HttpServletRequest request) {
        boolean found = trackingService.recordOpen(trackingId, request);
        if (!found) {
            log.warn("Unknown tracking ID: {}", trackingId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/gif"));
        // Prevent email clients and CDNs from caching the pixel
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        headers.setPragma("no-cache");
        headers.setExpires(0);

        return new ResponseEntity<>(TRACKING_PIXEL, headers, HttpStatus.OK);
    }
}
