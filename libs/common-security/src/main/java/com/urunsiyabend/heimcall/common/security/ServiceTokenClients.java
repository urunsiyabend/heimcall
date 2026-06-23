package com.urunsiyabend.heimcall.common.security;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

/**
 * Factory a caller service uses to attach service tokens to its inter-service {@link RestClient}s (Phase 16
 * T3). Inject this and call {@link #authorize(RestClient.Builder, String)} when building each client, passing
 * the {@code registrationId} of the callee it talks to (the OAuth2 client registration whose scopes target
 * that callee). Keeps the wiring identical across every {@code *Client} and hides the {@link
 * OAuth2AuthorizedClientManager} plumbing.
 */
public class ServiceTokenClients {

    private final OAuth2AuthorizedClientManager manager;

    public ServiceTokenClients(OAuth2AuthorizedClientManager manager) {
        this.manager = manager;
    }

    /** Adds the bearer-token interceptor for {@code registrationId} to the given builder and returns it. */
    public RestClient.Builder authorize(RestClient.Builder builder, String registrationId) {
        return builder.requestInterceptor(new ServiceTokenInterceptor(manager, registrationId));
    }
}
