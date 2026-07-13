package com.nudge.config;

import com.nudge.security.JwtAuthFilter;
import com.nudge.security.RateLimitFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration.
 * - Stateless (JWT, no sessions)
 * - Public routes: /api/auth/**, /track/**
 * - Everything else requires a valid JWT
 *
 * CORS locked to origins listed in app.cors.allowed-origins.
 *     allowCredentials IS set to true — the web frontend authenticates via
 *     an httpOnly JWT cookie, so wildcard origins are rejected (see
 *     corsConfigurationSource(), which uses allowed origin *patterns* so
 *     credentialed requests still work per-origin).
 * RateLimitFilter runs before the JWT filter.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final RateLimitFilter rateLimitFilter;
    private final Environment environment;

    /** Injected from application.properties / env var. No default — app fails fast if not set. */
    @Value("${app.cors.allowed-origins}")
    private String corsAllowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          UserDetailsService userDetailsService,
                          RateLimitFilter rateLimitFilter,
                          Environment environment) {
        this.jwtAuthFilter    = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
        this.rateLimitFilter  = rateLimitFilter;
        this.environment      = environment;
    }

    /**
     * Fail fast on startup if the prod profile is active with a wildcard CORS
     * origin — allowCredentials(true) + "*" would let any origin make
     * credentialed (cookie-bearing) requests against a production deployment.
     */
    @PostConstruct
    void validateCorsConfig() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        boolean hasWildcard = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .anyMatch("*"::equals);
        if (isProd && hasWildcard) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins is '*' while the 'prod' profile is active. " +
                    "Combined with allowCredentials(true), this would allow any origin to make " +
                    "credentialed requests. Set CORS_ALLOWED_ORIGINS to an explicit origin list.");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/track/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/", "/*.html", "/js/**", "/css/**", "/images/**", "/favicon.ico").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
            )
            .authenticationProvider(authenticationProvider())
            // Rate limiter before JWT filter
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Credentials (httpOnly cookie) require explicit origins — wildcards are forbidden by spec.
        // setAllowedOriginPatterns supports wildcards AND works with allowCredentials(true).
        // allowCredentials IS set to true because the web frontend sends the JWT via an httpOnly cookie.
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOriginPatterns(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Set-Cookie"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
