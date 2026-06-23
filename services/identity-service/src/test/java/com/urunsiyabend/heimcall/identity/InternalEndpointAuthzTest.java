package com.urunsiyabend.heimcall.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.identity.domain.MembershipRepository;
import com.urunsiyabend.heimcall.identity.domain.TeamMemberRepository;
import com.urunsiyabend.heimcall.identity.domain.TeamRepository;
import com.urunsiyabend.heimcall.identity.web.InternalController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 16 T3 acceptance — enforcement on a real internal endpoint, end to end over the actual security
 * wiring: a caller mints a service token at {@code /oauth2/token}, then calls {@code GET
 * /v1/internal/organizations/{org}/members/{user}}. The endpoint is now {@code @PreAuthorize}-gated on
 * {@code SCOPE_identity.membership.read} (not {@code permitAll}). Repositories are mocked so no database is
 * needed; the focus is the authz outcome, not the lookup.
 *
 * <p>Proves: no token → 401; a token for the wrong audience (catalog) → 401 (never authenticated here); a
 * valid-but-wrong-scope token (identity.team.read) → 403; the correctly scoped token → 204 (reaches the
 * handler). A forged {@code X-User-Id} is irrelevant — the endpoint takes the user as an explicit path
 * variable and the filter strips the header, so there is no header-derived user to spoof.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = InternalEndpointAuthzTest.Boot.class)
@ActiveProfiles("dev")
class InternalEndpointAuthzTest {

    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @Import({AuthorizationServerConfig.class, SecurityBeans.class, InternalController.class})
    static class Boot {
    }

    @Autowired
    TestRestTemplate rest;

    @MockBean
    MembershipRepository memberships;
    @MockBean
    TeamRepository teams;
    @MockBean
    TeamMemberRepository teamMembers;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private String mint(String clientId, String secret, String scope) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", scope);
        ResponseEntity<String> resp = rest.withBasicAuth(clientId, secret)
                .postForEntity("/oauth2/token", new HttpEntity<>(form, headers), String.class);
        JsonNode node = JSON.readTree(resp.getBody());
        return node.get("access_token").asText();
    }

    private ResponseEntity<Void> callMembers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
            headers.add("X-User-Id", UUID.randomUUID().toString()); // forged; must be ignored
        }
        return rest.exchange("/v1/internal/organizations/{org}/members/{user}",
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), Void.class, ORG, USER);
    }

    @Test
    void noTokenIsUnauthorized() {
        assertThat(callMembers(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenForAnotherAudienceIsUnauthorized() throws Exception {
        // incident may mint a catalog-scoped token (aud=catalog); it must not open identity's endpoint.
        String catalogToken = mint("incident", "dev-incident-secret", "catalog.routing.resolve");
        assertThat(callMembers(catalogToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validAudienceButWrongScopeIsForbidden() throws Exception {
        // catalog is registered for identity.team.read (aud=identity) but the members endpoint needs
        // identity.membership.read — authenticated, but method security denies -> 403.
        String wrongScope = mint("catalog", "dev-catalog-secret", "identity.team.read");
        assertThat(callMembers(wrongScope).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void correctlyScopedTokenReachesTheHandler() throws Exception {
        when(memberships.existsByOrganizationIdAndUserId(any(), any())).thenReturn(true);

        String token = mint("incident", "dev-incident-secret", "identity.membership.read");
        assertThat(callMembers(token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
