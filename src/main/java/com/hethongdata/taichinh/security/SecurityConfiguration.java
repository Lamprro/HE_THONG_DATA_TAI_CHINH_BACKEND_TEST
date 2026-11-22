package com.hethongdata.taichinh.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.jwt.JwtService;
import org.springframework.context.annotation.Import;
import com.hethongdata.taichinh.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Import(JwtService.class)
public class SecurityConfiguration {
    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    @Bean
    PasswordEncoder passwordEncoder(@Value("${financial.security.jwt-secret:}") String authSecret) {
        return new AccountPasswordEncoder(authSecret);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper json, JwtService tokens,
            @Qualifier("apiCors") CorsConfigurationSource cors) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var api = paths.matcher("/api/**");
        var login = paths.matcher(HttpMethod.POST, "/api/auth/login");
        var health = paths.matcher("/api/health/**");
        var user = paths.matcher("/api/v1/**");
        var docs = paths.matcher("/v3/api-docs/**");
        var bearer = new DefaultBearerTokenResolver();
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("");
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(roles);
        AuthenticationEntryPoint denied = (request, response, error) -> securityError(response, json, 401);
        return http.cors(c -> c.configurationSource(cors))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(login, health, user).permitAll()
                        .requestMatchers(api, docs).hasRole("ADMIN")
                        .requestMatchers(paths.matcher("/webjars/swagger-ui/**"), paths.matcher("/admin-api-docs/**")).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(denied)
                        .accessDeniedHandler((request, response, error) -> securityError(response, json, 403)))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(denied)
                        .bearerTokenResolver(request -> (api.matches(request) || docs.matches(request))
                                && !login.matches(request) && !health.matches(request) && !user.matches(request) ? bearer.resolve(request) : null)
                        .jwt(jwt -> jwt.decoder(tokens).jwtAuthenticationConverter(authentication)))
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

    private static void securityError(HttpServletResponse response, ObjectMapper json, int status) throws java.io.IOException {
        response.setStatus(status);
        if (status == 401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType("application/json");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), new ApiErrorResponse(Instant.now(),
                status == 401 ? "UNAUTHENTICATED" : "ADMIN_ACCESS_DENIED", null, null,
                status == 401 ? "A valid access token is required" : "Admin role required"));
    }
}
