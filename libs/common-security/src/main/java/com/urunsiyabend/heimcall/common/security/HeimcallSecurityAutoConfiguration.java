package com.urunsiyabend.heimcall.common.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security, auto-applied to every service that depends on common-security. Validates
 * {@code Authorization: Bearer} via {@link JwtAuthenticationFilter}; unauthenticated calls to
 * protected routes get a plain 401 (no redirect, no session).
 *
 * <p>Open routes: actuator, the auth endpoints (login/register/refresh), service-to-service internal
 * APIs, the integration-key resolve call, and the webhook ingest endpoint (key-authenticated).
 * A service can override the whole chain by declaring its own {@link SecurityFilterChain} bean.
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class HeimcallSecurityAutoConfiguration {

    @Bean
    public JwtSupport jwtSupport(JwtProperties props) {
        return new JwtSupport(props);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtSupport jwtSupport) {
        return new JwtAuthenticationFilter(jwtSupport);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain heimcallSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Servlet ERROR/FORWARD dispatches (e.g. a 400 routed to /error) must not be
                        // re-authenticated, else the real error status is masked as 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/register", "/v1/auth/refresh").permitAll()
                        .requestMatchers("/v1/internal/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/integration-keys/resolve").permitAll()
                        .requestMatchers("/v1/integrations/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (request, response, ex) -> response.sendError(401)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
