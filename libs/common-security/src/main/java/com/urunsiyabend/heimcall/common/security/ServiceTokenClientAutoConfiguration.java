package com.urunsiyabend.heimcall.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Phase 16 T3 client side. Only active on a caller service — one that ships {@code
 * spring-boot-starter-oauth2-client} and declares {@code spring.security.oauth2.client.registration.*} (so a
 * {@link ClientRegistrationRepository} exists). It builds a service-to-service {@link
 * OAuth2AuthorizedClientManager} (no servlet request / user context, unlike the default web manager) that
 * mints {@code client_credentials} tokens at identity's token endpoint, caches them, and re-mints before
 * expiry. {@link ServiceTokenClients} wraps it so each {@code *Client} attaches the right callee-scoped token.
 *
 * <p>Lives in a separate auto-configuration from {@link HeimcallSecurityAutoConfiguration} so the verifier
 * side stays independent of the OAuth2 client classes (callee-only services never load these beans).
 */
@AutoConfiguration
// Run after Boot has bound spring.security.oauth2.client.* into a ClientRegistrationRepository, so the
// @ConditionalOnBean below sees it (the condition is evaluated in autoconfig order). Referenced by name so a
// callee-only service without the oauth2-client starter doesn't fail to load the class.
@AutoConfigureAfter(name =
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration")
@ConditionalOnClass(OAuth2AuthorizedClientManager.class)
@ConditionalOnBean(ClientRegistrationRepository.class)
public class ServiceTokenClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OAuth2AuthorizedClientService oAuth2AuthorizedClientService(ClientRegistrationRepository clients) {
        return new InMemoryOAuth2AuthorizedClientService(clients);
    }

    @Bean
    @ConditionalOnMissingBean
    public OAuth2AuthorizedClientManager serviceTokenAuthorizedClientManager(
            ClientRegistrationRepository clients, OAuth2AuthorizedClientService authorizedClients) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clients, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public ServiceTokenClients serviceTokenClients(OAuth2AuthorizedClientManager manager) {
        return new ServiceTokenClients(manager);
    }
}
