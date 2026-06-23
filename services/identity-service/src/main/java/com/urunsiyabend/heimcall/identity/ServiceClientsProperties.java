package com.urunsiyabend.heimcall.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 16 T2 — runtime inputs for the client_credentials token endpoint. <b>Only secrets are config</b>;
 * the caller→scope matrix (which client may request which scopes, hence which callee it can reach) is an
 * authoritative code constant in {@link AuthorizationServerConfig#CLIENT_SCOPES}, not editable config.
 *
 * <p>Secrets are keyed by {@code clientId}. They are required and rotatable per caller; a blank secret fails
 * boot ({@link AuthorizationServerConfig}). Weak, visible dev values live in {@code application-dev.yml} (the
 * default profile for local runs); a non-dev profile must supply each secret via {@code HEIMCALL_CLIENT_SECRET_*}
 * from a Kubernetes Secret or the context fails to start — no silent fall-through to a default in prod.
 */
@ConfigurationProperties("heimcall")
public class ServiceClientsProperties {

    /** Lifetime of a minted service token; short by design (machine callers re-mint cheaply). */
    private Duration serviceTokenTtl = Duration.ofMinutes(5);

    /** {@code clientId → secret}. Keys must match the registered callers; values come from env in non-dev. */
    private Map<String, String> serviceClientSecrets = new LinkedHashMap<>();

    public Duration getServiceTokenTtl() {
        return serviceTokenTtl;
    }

    public void setServiceTokenTtl(Duration serviceTokenTtl) {
        this.serviceTokenTtl = serviceTokenTtl;
    }

    public Map<String, String> getServiceClientSecrets() {
        return serviceClientSecrets;
    }

    public void setServiceClientSecrets(Map<String, String> serviceClientSecrets) {
        this.serviceClientSecrets = serviceClientSecrets;
    }
}
