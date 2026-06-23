package com.urunsiyabend.heimcall.common.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;

import java.io.IOException;

/**
 * Attaches a {@code client_credentials} service token (Phase 16 T3) to every outbound inter-service call as
 * {@code Authorization: Bearer <service-token>}. The token is obtained from the {@link
 * OAuth2AuthorizedClientManager} for a fixed {@code registrationId} (one registration per callee, named after
 * the callee so its single-audience token only opens that service). The manager caches the token and
 * re-mints it a clock-skew margin before {@code exp}, so the common path is a cache hit with no extra round
 * trip; only a cold or expired entry calls identity's token endpoint.
 *
 * <p>Bound to one registration so a {@code RestClient} talking to a specific callee always presents the token
 * scoped to exactly that callee+operation — a caller with several callees uses one interceptor per client.
 */
public final class ServiceTokenInterceptor implements ClientHttpRequestInterceptor {

    private final OAuth2AuthorizedClientManager manager;
    private final String registrationId;

    public ServiceTokenInterceptor(OAuth2AuthorizedClientManager manager, String registrationId) {
        this.manager = manager;
        this.registrationId = registrationId;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        OAuth2AuthorizeRequest authorize = OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId)
                // No end-user is involved in a machine call; a stable, non-null principal name is all the
                // AuthorizedClientService needs to key the cached token by (registrationId, principal).
                .principal(registrationId)
                .build();
        OAuth2AuthorizedClient client = manager.authorize(authorize);
        if (client == null) {
            throw new IllegalStateException("could not obtain service token for registration '" + registrationId + "'");
        }
        request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
        return execution.execute(request, body);
    }
}
