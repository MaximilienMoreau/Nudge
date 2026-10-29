package com.nudge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * General application bean definitions.
 *
 * RestClient (Spring 7) exposed as a Spring bean with configured timeouts.
 *     Replaces the deprecated RestTemplate (in maintenance mode since Spring 5.0).
 * Reuse Spring's managed ObjectMapper (Jackson auto-configuration).
 * Trusted proxy ranges parsed from configuration property.
 */
@Configuration
public class AppConfig {

    @Value("${app.proxy.trusted-ranges:127.0.0.1,::1}")
    private String trustedProxyRanges;

    /**
     * Shared RestClient with explicit connection and read timeouts.
     * Injected into AIService for OpenAI HTTP calls.
     * Swap the requestFactory to OkHttp / Apache HttpClient if needed.
     */
    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Set of trusted proxy IP addresses / CIDR prefixes.
     * X-Forwarded-For is only trusted when the request arrives from these ranges.
     */
    @Bean("trustedProxySet")
    public Set<String> trustedProxySet() {
        return Arrays.stream(trustedProxyRanges.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

}
