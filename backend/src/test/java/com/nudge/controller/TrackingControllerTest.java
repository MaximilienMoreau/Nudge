package com.nudge.controller;

import com.nudge.service.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TrackingController, focused on the open-redirect guard in
 * /track/click: only http/https destinations may be redirected to.
 */
@ExtendWith(MockitoExtension.class)
class TrackingControllerTest {

    @Mock TrackingService trackingService;
    @Mock HttpServletRequest request;

    TrackingController controller;

    @BeforeEach
    void setUp() {
        controller = new TrackingController(trackingService);
    }

    @Test
    void trackOpen_returnsGifPixel_withNoCacheHeaders() {
        when(trackingService.recordOpen(anyString(), any())).thenReturn(true);

        ResponseEntity<byte[]> response = controller.trackOpen("some-tracking-id", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void trackClick_redirects_forValidHttpsUrl() {
        when(trackingService.recordClick(anyString(), any())).thenReturn(true);

        ResponseEntity<Void> response =
                controller.trackClick("tracking-id", "https://example.com/landing", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/landing");
    }

    @Test
    void trackClick_redirects_forValidHttpUrl() {
        when(trackingService.recordClick(anyString(), any())).thenReturn(true);

        ResponseEntity<Void> response =
                controller.trackClick("tracking-id", "http://example.com", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "ftp://example.com/file"
    })
    void trackClick_rejects_unsafeSchemes(String unsafeUrl) {
        when(trackingService.recordClick(anyString(), any())).thenReturn(true);

        ResponseEntity<Void> response = controller.trackClick("tracking-id", unsafeUrl, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void trackClick_rejects_missingUrl() {
        when(trackingService.recordClick(anyString(), any())).thenReturn(true);

        ResponseEntity<Void> response = controller.trackClick("tracking-id", null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void trackClick_rejects_blankUrl() {
        when(trackingService.recordClick(anyString(), any())).thenReturn(true);

        ResponseEntity<Void> response = controller.trackClick("tracking-id", "   ", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void trackClick_rejects_urlLongerThanMaxLength() {
        when(trackingService.recordClick(anyString(), any())).thenReturn(true);
        String tooLong = "https://example.com/" + "a".repeat(2048);

        ResponseEntity<Void> response = controller.trackClick("tracking-id", tooLong, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void trackClick_stillRecordsEvent_evenWhenTrackingIdUnknown() {
        when(trackingService.recordClick(anyString(), any())).thenReturn(false);

        ResponseEntity<Void> response =
                controller.trackClick("unknown-id", "https://example.com", request);

        // The redirect still happens so the recipient's browsing experience is unaffected;
        // only the (missing) event recording differs.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
}
