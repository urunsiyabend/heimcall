package com.urunsiyabend.heimcall.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.urunsiyabend.heimcall.common.security.HeimcallSecurityAutoConfiguration;
import com.urunsiyabend.heimcall.common.security.JwtAuthenticationFilter;
import com.urunsiyabend.heimcall.common.security.JwtClaims;
import com.urunsiyabend.heimcall.common.security.JwtKeys;
import com.urunsiyabend.heimcall.common.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 16 T2 — service-token issuance via Spring Authorization Server. identity-service hosts the standard
 * OAuth2 {@code client_credentials} token endpoint ({@code POST /oauth2/token}, {@code client_secret_basic}).
 * Each caller service is a {@link RegisteredClient} whose allowed scopes come from the authoritative
 * code-level matrix {@link #CLIENT_SCOPES} (not editable config); only the per-caller secret is config.
 *
 * <p><b>Single signing key.</b> The server signs with the very same RSA key + {@code kid} that mints user
 * tokens (the {@link JwtKeys} from T1), exposed here as a Nimbus {@link JWKSource}. So a service token
 * verifies against the existing {@code /v1/.well-known/jwks.json} and every {@code JwtVerifier} with no
 * verifier-side change — there is still exactly one issuer and one trust anchor.
 *
 * <p><b>Claim shaping.</b> A {@link OAuth2TokenCustomizer} stamps {@code token_use=service} and derives the
 * {@code aud} from the granted scopes' first segment ({@code identity.membership.read} → {@code identity}).
 * A <i>single-audience invariant</i> is enforced: every scope in one token must target the same callee, so a
 * token is never simultaneously valid at two services (and a no-scope request, which would grant all of a
 * multi-target client's scopes, is rejected). {@code iss}/{@code sub}/{@code exp}/{@code jti} come from SAS.
 */
@Configuration
@EnableConfigurationProperties(ServiceClientsProperties.class)
public class AuthorizationServerConfig {

    /**
     * The authoritative caller→scope matrix: which service may request which dotted scopes. Each scope's
     * first segment is its callee (the {@code aud} the token is minted for, see {@link #audienceOf}), so this
     * map alone decides which callee+operation a caller can ever reach — a caller cannot request a scope it is
     * not listed for. Kept in code, not config, so the trust matrix is reviewed like code and never edited by
     * a deployment value. Mirrors the Phase 16 plan matrix.
     */
    static final Map<String, List<String>> CLIENT_SCOPES = Map.of(
            "incident", List.of("identity.membership.read", "catalog.routing.resolve"),
            "escalation", List.of("identity.membership.read", "identity.team-members.read", "schedule.on-call.read"),
            "integration", List.of("identity.integration-key.resolve"),
            "catalog", List.of("escalation.policy.read"));

    /**
     * Authorization-server endpoints ({@code /oauth2/token}, {@code /oauth2/jwks}, metadata). Highest
     * priority so {@code /oauth2/**} is handled here (client auth via Basic), ahead of the resource chain.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        return http.build();
    }

    /**
     * Catch-all resource chain (user JWTs + open routes). identity must declare this explicitly: once it
     * defines any {@link SecurityFilterChain} bean (the one above), common-security's auto-configured chain
     * is suppressed by {@code @ConditionalOnMissingBean}. Reuses the shared builder so there is no drift.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public SecurityFilterChain resourceSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
            throws Exception {
        return HeimcallSecurityAutoConfiguration.heimcallDefaultChain(http, jwtFilter);
    }

    /**
     * One {@link RegisteredClient} per caller in {@link #CLIENT_SCOPES}, with scopes from that matrix and the
     * secret from config. <b>Fail-closed:</b> a missing/blank secret throws at boot — in a non-dev profile,
     * where no weak default exists, forgetting to wire {@code HEIMCALL_CLIENT_SECRET_*} stops the context
     * rather than silently registering a credential-less client. Secrets are BCrypt-hashed here (identity
     * stores only the hash; constant-time match), never kept in plaintext.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(ServiceClientsProperties props,
                                                                 PasswordEncoder passwordEncoder) {
        Map<String, String> secrets = props.getServiceClientSecrets();
        List<RegisteredClient> clients = new ArrayList<>();
        CLIENT_SCOPES.forEach((clientId, scopes) -> {
            String secret = secrets.get(clientId);
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "missing service-client secret for '" + clientId + "': set heimcall.service-client-secrets."
                                + clientId + " (HEIMCALL_CLIENT_SECRET_" + clientId.toUpperCase() + ")");
            }
            clients.add(RegisteredClient.withId(clientId)
                    .clientId(clientId)
                    .clientSecret(passwordEncoder.encode(secret))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .scopes(s -> s.addAll(scopes))
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                            .accessTokenTimeToLive(props.getServiceTokenTtl())
                            .build())
                    .build());
        });
        return new InMemoryRegisteredClientRepository(clients);
    }

    /**
     * Sign service tokens with T1's RSA key material — one issuer, one trust anchor, never a second key
     * source. The JWKSet mirrors {@code /v1/.well-known/jwks.json} exactly (active + any retired kid), built
     * from the same {@link JwtKeys}; only the active kid carries private material, so SAS selects it to sign
     * while {@code /oauth2/jwks} publishes the identical set. Rotating the key in {@code JwtKeys} rotates both
     * the user-token signer (jjwt) and this service-token signer (Nimbus) together.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(JwtKeys keys) {
        List<JWK> jwks = keys.publicKeysByKid().entrySet().stream()
                .map(e -> {
                    RSAKey.Builder b = new RSAKey.Builder((RSAPublicKey) e.getValue())
                            .keyID(e.getKey())
                            .keyUse(KeyUse.SIGNATURE)
                            .algorithm(JWSAlgorithm.RS256);
                    if (e.getKey().equals(keys.activeKid())) {
                        b.privateKey(keys.privateKey());
                    }
                    return (JWK) b.build();
                })
                .toList();
        return new ImmutableJWKSet<>(new JWKSet(jwks));
    }

    /**
     * Issuer set <b>explicitly</b> to Heimcall's single universal issuer ({@link JwtProperties#getIssuer()},
     * an absolute URL shared with user tokens) — never derived from the inbound request, so a host/scheme
     * difference behind the gateway/ingress can never shift the {@code iss} claim. The JWK Set endpoint is
     * pointed at the canonical {@code /v1/.well-known/jwks.json} so there is one JWKS URL for the whole
     * platform (the standalone T1 controller is removed); verifiers' {@code jwks-uri} is unchanged.
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(JwtProperties jwtProperties) {
        return AuthorizationServerSettings.builder()
                .issuer(jwtProperties.getIssuer())
                .jwkSetEndpoint("/v1/.well-known/jwks.json")
                .build();
    }

    /**
     * Stamp {@code token_use=service}, the space-delimited {@code scope}, and the single derived {@code aud}.
     * {@code iss}/{@code sub}/{@code exp}/{@code iat}/{@code nbf}/{@code jti} come from SAS ({@code iss} from
     * the explicit issuer setting). Enforces the single-audience invariant: every scope in one token must
     * target the same callee, so a token is never simultaneously valid at two services (and a no-scope
     * request, which would grant all of a multi-target client's scopes, is rejected).
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> serviceTokenCustomizer() {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                    || !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                return;
            }
            Set<String> scopes = context.getAuthorizedScopes();
            if (scopes.isEmpty()) {
                throw badScope("a service token request must name the scope(s) it needs");
            }
            Set<String> audiences = scopes.stream()
                    .map(AuthorizationServerConfig::audienceOf)
                    .collect(Collectors.toSet());
            if (audiences.size() != 1) {
                throw badScope("all scopes in one service token must target the same audience, got " + audiences);
            }
            context.getClaims()
                    .claim(JwtClaims.TOKEN_USE, JwtClaims.SERVICE)
                    .claim(JwtClaims.SCOPE, String.join(" ", scopes))
                    .audience(List.copyOf(audiences))
                    .id(UUID.randomUUID().toString());
        };
    }

    /** Callee = the scope's first dotted segment, e.g. {@code catalog.routing.resolve} → {@code catalog}. */
    private static String audienceOf(String scope) {
        int dot = scope.indexOf('.');
        return dot < 0 ? scope : scope.substring(0, dot);
    }

    private static org.springframework.security.oauth2.core.OAuth2AuthenticationException badScope(String msg) {
        return new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                new OAuth2Error("invalid_scope", msg, null));
    }
}
