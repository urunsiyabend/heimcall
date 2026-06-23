package com.urunsiyabend.heimcall.common.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security, auto-applied to every service that depends on common-security. Tokens are RS256,
 * signed by the single issuer (identity-service, the only holder of the private key) and verified everywhere
 * via JWKS — there is no shared symmetric secret. The signer additionally gets {@link JwtKeys} +
 * {@link JwtIssuer}; verifiers get a {@link JwksKeyResolver}. Both verify through {@link JwtVerifier}.
 *
 * <p>Unauthenticated calls to protected routes get a plain 401 (no redirect, no session). Open routes:
 * actuator, the JWKS/discovery endpoints, the auth endpoints (login/register/refresh), service-to-service
 * internal APIs, the integration-key resolve call, and the webhook ingest endpoint (key-authenticated).
 * A service can override the whole chain by declaring its own {@link SecurityFilterChain} bean.
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class HeimcallSecurityAutoConfiguration {

    /** Signer keys — only on the service that holds a private key (identity-service). */
    @Bean
    @ConditionalOnExpression(
            "'${heimcall.jwt.private-key:}' != '' or '${heimcall.jwt.private-key-location:}' != ''")
    public JwtKeys jwtKeys(JwtProperties props, ResourceLoader resourceLoader) {
        return new JwtKeys(props, resourceLoader);
    }

    @Bean
    @ConditionalOnBean(JwtKeys.class)
    public JwtIssuer jwtIssuer(JwtProperties props, JwtKeys jwtKeys) {
        return new JwtIssuer(props, jwtKeys);
    }

    /** On the signer, verify with the in-process keys (no self-HTTP to its own JWKS). */
    @Bean
    @ConditionalOnBean(JwtKeys.class)
    public PublicKeyResolver localKeyResolver(JwtKeys jwtKeys) {
        return new LocalKeyResolver(jwtKeys);
    }

    /** On every other service, fetch the issuer's public keys from its JWKS endpoint. */
    @Bean
    @ConditionalOnMissingBean(JwtKeys.class)
    public PublicKeyResolver jwksKeyResolver(JwtProperties props) {
        return new JwksKeyResolver(props.getJwksUri());
    }

    @Bean
    public JwtVerifier jwtVerifier(JwtProperties props, PublicKeyResolver resolver) {
        return new JwtVerifier(props, resolver);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtVerifier jwtVerifier) {
        return new JwtAuthenticationFilter(jwtVerifier);
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
                        .requestMatchers(HttpMethod.GET, "/v1/.well-known/**").permitAll()
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
