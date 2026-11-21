package com.hethongdata.taichinh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(SecurityConfiguration.AdminCredentials.class)
public class SecurityConfiguration {
    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    public static final String SHARED_PRINCIPAL = "DEDICATED_ADMIN_TOKEN";

    @ConfigurationProperties("financial.admin")
    public record AdminCredentials(String apiToken, Map<String, Credential> credentials) {
        public record Credential(String token, String email, Set<String> roles) {
            @Override public String toString() { return "Credential[redacted]"; }
        }

        public AdminCredentials {
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
            Set<String> tokens = new HashSet<>();
            if (usable(apiToken)) tokens.add(apiToken);
            credentials.forEach((name, credential) -> {
                if (!name.matches("[A-Za-z0-9._@-]{1,100}") || SHARED_PRINCIPAL.equals(name)
                        || !usable(credential.token()) || credential.roles() == null || credential.roles().isEmpty()
                        || credential.roles().stream().anyMatch(role -> !Set.of("ADMIN", "AUDITOR").contains(role))
                        || !tokens.add(credential.token())) {
                    throw new IllegalArgumentException("Invalid or duplicate named admin credential");
                }
            });
        }

        @Override public String toString() { return "AdminCredentials[redacted]"; }
        boolean configured() { return usable(apiToken) || !credentials.isEmpty(); }
        public boolean adminConfigured() {
            return usable(apiToken) || credentials.values().stream().anyMatch(credential -> credential.roles().contains("ADMIN"));
        }
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper json, AdminCredentials credentials,
            @Qualifier("apiCors") CorsConfigurationSource cors) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var api = paths.matcher("/api/**");
        var health = paths.matcher("/api/health/**");
        var user = paths.matcher("/api/v1/**");
        var docs = paths.matcher("/v3/api-docs/**");
        var bearer = new DefaultBearerTokenResolver();
        AuthenticationEntryPoint denied = (request, response, error) -> securityError(response, json,
                credentials.configured() ? 403 : 503);
        return http.cors(c -> c.configurationSource(cors))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(health, user).permitAll()
                        .requestMatchers(api, docs).hasRole("ADMIN")
                        .requestMatchers(paths.matcher("/webjars/swagger-ui/**"), paths.matcher("/admin-api-docs/**")).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(denied)
                        .accessDeniedHandler((request, response, error) -> securityError(response, json, 403)))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(denied)
                        .bearerTokenResolver(request -> (api.matches(request) || docs.matches(request))
                                && !health.matches(request) && !user.matches(request) ? bearer.resolve(request) : null)
                        .opaqueToken(opaque -> opaque.introspector(introspector(credentials))))
                .build();
    }

    @Bean
    CorsConfigurationSource apiCors(@Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String csv) {
        var cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(Arrays.stream(csv.split(",")).map(String::trim)
                .filter(origin -> !origin.isBlank()).toList());
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, "ngrok-skip-browser-warning"));
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.CONTENT_TYPE));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    public static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()))
                ? authentication.getName() : "SYSTEM_WORKER";
    }

    private static OpaqueTokenIntrospector introspector(AdminCredentials config) {
        return token -> {
            if (usable(config.apiToken()) && matches(config.apiToken(), token)) {
                return principal(SHARED_PRINCIPAL, null, Set.of("ADMIN"));
            }
            for (var entry : config.credentials().entrySet()) {
                var credential = entry.getValue();
                if (matches(credential.token(), token)) {
                    return principal(entry.getKey(), credential.email(), credential.roles());
                }
            }
            throw new BadOpaqueTokenException("Invalid admin credential");
        };
    }

    private static DefaultOAuth2AuthenticatedPrincipal principal(String name, String email, Set<String> roles) {
        var attributes = new HashMap<String, Object>();
        attributes.put("sub", name);
        if (email != null && !email.isBlank()) attributes.put("email", email);
        return new DefaultOAuth2AuthenticatedPrincipal(name, attributes,
                roles.stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
    }

    private static boolean usable(String token) { return token != null && token.length() >= 32; }

    private static boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void securityError(HttpServletResponse response, ObjectMapper json, int status) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), new ApiErrorResponse(Instant.now(),
                status == 503 ? "ADMIN_ACCESS_NOT_CONFIGURED" : "ADMIN_ACCESS_DENIED", null, null,
                status == 503 ? "Configure an admin bearer credential" : "Admin credential required"));
    }
}
