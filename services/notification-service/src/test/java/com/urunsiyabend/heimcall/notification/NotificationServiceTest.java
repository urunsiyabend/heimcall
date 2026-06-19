package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.NotificationRequestedEvent;
import com.urunsiyabend.heimcall.notification.domain.ContactMethod;
import com.urunsiyabend.heimcall.notification.domain.ContactMethodRepository;
import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationDeliveryRepository;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13 T4: the request fan-out ({@link NotificationService}) — one PENDING delivery per <em>enabled</em>
 * contact method, idempotent on the request event id. Pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock NotificationRequestRepository requests;
    @Mock NotificationDeliveryRepository deliveries;
    @Mock ContactMethodRepository contactMethods;
    @InjectMocks NotificationService service;

    private NotificationRequestedEvent request(UUID eventId) {
        return new NotificationRequestedEvent(eventId, Instant.now(), ORG, UUID.randomUUID(), UUID.randomUUID(),
                1, USER, "USER", "boom", Severity.CRITICAL);
    }

    private ContactMethod contact(NotificationChannel channel, String destination) {
        ContactMethod cm = mock(ContactMethod.class);
        when(cm.getId()).thenReturn(UUID.randomUUID());
        when(cm.getChannel()).thenReturn(channel);
        when(cm.getDestination()).thenReturn(destination);
        return cm;
    }

    @Test
    void fansOutOneDeliveryPerEnabledContactMethod() {
        NotificationRequestedEvent event = request(UUID.randomUUID());
        ContactMethod email = contact(NotificationChannel.EMAIL, "a@acme.io");
        ContactMethod webhook = contact(NotificationChannel.WEBHOOK, "https://hooks.acme.io/x");
        when(contactMethods.findByOrganizationIdAndUserIdAndEnabledTrue(ORG, USER))
                .thenReturn(List.of(email, webhook));

        service.onNotificationRequested(event);

        verify(requests).save(any());
        ArgumentCaptor<NotificationDelivery> saved = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveries, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(NotificationDelivery::getChannel)
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.WEBHOOK);
        assertThat(saved.getAllValues())
                .extracting(NotificationDelivery::getDestination)
                .containsExactly("a@acme.io", "https://hooks.acme.io/x");
    }

    @Test
    void noEnabledContactMethods_recordsRequestButNoDeliveries() {
        NotificationRequestedEvent event = request(UUID.randomUUID());
        when(contactMethods.findByOrganizationIdAndUserIdAndEnabledTrue(ORG, USER)).thenReturn(List.of());

        service.onNotificationRequested(event);

        verify(requests).save(any());
        verify(deliveries, never()).save(any());
    }

    @Test
    void idempotentOnRequestEventId() {
        UUID eventId = UUID.randomUUID();
        when(requests.existsById(eventId)).thenReturn(true);

        service.onNotificationRequested(request(eventId));

        verify(requests, never()).save(any());
        verify(deliveries, never()).save(any());
    }
}
