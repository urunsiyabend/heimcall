package com.urunsiyabend.heimcall.notification.sender;

import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers a notification as an HTTP POST of a JSON body to the contact method's destination URL.
 * Connect + read timeouts are bounded so a slow endpoint fails fast and the delivery is retried.
 * A non-2xx response throws (RestClient default), which the caller treats as a failed attempt.
 */
@Component
public class WebhookSender implements NotificationSender {

    private final RestClient restClient;

    public WebhookSender(RestClient.Builder builder,
                         @Value("${notification.webhook.timeout-ms:5000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        // Boot's auto-configured builder carries the observation customizer, so outbound webhook
        // deliveries emit a client span and stay on the distributed trace (Phase 8 T4b).
        this.restClient = builder.requestFactory(factory).build();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WEBHOOK;
    }

    @Override
    public void send(NotificationDelivery delivery, NotificationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", request.getIncidentId());
        payload.put("recipientUserId", request.getRecipientUserId());
        payload.put("level", request.getLevel());
        payload.put("title", request.getTitle());
        payload.put("severity", request.getSeverity() != null ? request.getSeverity().name() : null);
        payload.put("organizationId", request.getOrganizationId());

        restClient.post()
                .uri(delivery.getDestination())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
