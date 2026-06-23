package com.urunsiyabend.heimcall.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.common.security.JwtClaims;
import com.urunsiyabend.heimcall.common.security.JwtVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 16 T2 acceptance — the client_credentials token endpoint over the real Spring Authorization Server
 * wiring (config, registered clients, JWKSource, claim customizer) and identity's actual {@code
 * application.yml} clients. Sliced to the auth-server + security beans (JPA/Flyway/Kafka auto-config
 * excluded) so it needs no database and no Docker.
 *
 * <p>Proves: a registered caller gets a service token scoped to exactly the callee+operation it needs; the
 * token is signed by the same RS256 key as user tokens, so identity's own {@link JwtVerifier} accepts it; a
 * token minted for one audience is rejected at another; a bad secret is refused; an out-of-scope request and
 * a cross-audience request are both refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ServiceTokenEndpointTest.Boot.class)
@ActiveProfiles("dev") // weak service-client secrets live in the dev profile (fail-closed elsewhere)
class ServiceTokenEndpointTest {

    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @Import({AuthorizationServerConfig.class, SecurityBeans.class})
    static class Boot {
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JwtVerifier verifier;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponseEntity<String> token(String clientId, String secret, String scope) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", scope);
        return rest.withBasicAuth(clientId, secret)
                .postForEntity("/oauth2/token", new HttpEntity<>(form, headers), String.class);
    }

    private static String accessToken(ResponseEntity<String> resp) throws Exception {
        JsonNode node = JSON.readTree(resp.getBody());
        return node.get("access_token").asText();
    }

    @Test
    void mintsServiceTokenScopedToTheCallee() throws Exception {
        ResponseEntity<String> resp = token("incident", "dev-incident-secret", "catalog.routing.resolve");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = accessToken(resp);

        Claims claims = verifier.verifyService(accessToken, "catalog");
        assertThat(claims.getSubject()).isEqualTo("incident");
        assertThat(claims.getIssuer()).isEqualTo("https://identity.heimcall.internal"); // same iss as user tokens
        assertThat(claims.get(JwtClaims.TOKEN_USE, String.class)).isEqualTo(JwtClaims.SERVICE);
        assertThat(claims.get(JwtClaims.SCOPE, String.class)).isEqualTo("catalog.routing.resolve");
        assertThat(claims.getId()).isNotBlank(); // jti

        // The very same token must NOT authenticate a call addressed to another service.
        assertThatThrownBy(() -> verifier.verifyService(accessToken, "identity")).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongClientSecret() {
        ResponseEntity<String> resp = token("incident", "wrong-secret", "catalog.routing.resolve");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsScopeTheClientIsNotRegisteredFor() {
        // incident may reach identity + catalog, never schedule. SAS refuses the unregistered scope.
        ResponseEntity<String> resp = token("incident", "dev-incident-secret", "schedule.on-call.read");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("invalid_scope");
    }

    @Test
    void rejectsCrossAudienceTokenRequest() {
        // Both scopes are registered for incident, but they target different callees: the single-audience
        // invariant in the token customizer forbids one token valid at two services.
        ResponseEntity<String> resp =
                token("incident", "dev-incident-secret", "identity.membership.read catalog.routing.resolve");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
