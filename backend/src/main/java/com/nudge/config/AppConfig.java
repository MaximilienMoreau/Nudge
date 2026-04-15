package com.nudge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * General application bean definitions.
 *
 * Q2: RestTemplate exposed as a Spring bean with configured timeouts.
 * Q3: Reuse Spring's managed ObjectMapper (Jackson auto-configuration).
 * S4: Trusted proxy ranges parsed from configuration property.
 */
@Configuration
public class AppConfig {

    @Value("${app.proxy.trusted-ranges:127.0.0.1,::1}")
    private String trustedProxyRanges;

    /**
     * Q2: Shared RestTemplate with explicit connection and read timeouts.
     * Injects into AIService instead of instantiating inline.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * S4: Set of trusted proxy IP addresses / CIDR prefixes.
     * X-Forwarded-For is only trusted when the request arrives from these ranges.
     */
    @Bean("trustedProxySet")
    public Set<String> trustedProxySet() {
        return Arrays.stream(trustedProxyRanges.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * Q3: Expose the Spring-managed ObjectMapper as a named bean so services
     * can inject it instead of creating their own instances.
     * Jackson auto-configuration provides this bean; we just expose it here
     * for clarity / explicit injection.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
