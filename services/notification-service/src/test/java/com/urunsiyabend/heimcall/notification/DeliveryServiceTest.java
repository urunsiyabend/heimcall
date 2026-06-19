package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.events.NotificationDeliveredEvent;
import com.urunsiyabend.heimcall.common.events.NotificationFailedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import com.urunsiyabend.heimcall.notification.domain.DeliveryStatus;
import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationDeliveryRepository;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequestRepository;
import com.urunsiyabend.heimcall.notification.sender.NotificationSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 13 T4: notification delivery firing ({@link DeliveryService}) — the retry/backoff decision
 * (linear `attempts × backoff`, terminal FAILED at `max-attempts`), success/failure event + counter
 * side-effects, and the claim-empty / no-sender / missing-request guards. Pure Mockito (a real
 * {@link SimpleMeterRegistry}); the lock-safe claim is proven against real PG in
 * {@code NotificationDeliveryClaimTest}.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    private static final long BACKOFF = 10_000L;
    private static final int MAX_ATTEMPTS = 3;

    @Mock NotificationDeliveryRepository deliveries;
    @Mock NotificationRequestRepository requests;
    @Mock OutboxAppender outbox;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private DeliveryService service(List<NotificationSender> senders) {
        return new DeliveryService(deliveries, requests, outbox, senders, registry, MAX_ATTEMPTS, BACKOFF);
    }

    private NotificationDelivery emailDelivery() {
        return NotificationDelivery.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), NotificationChannel.EMAIL, "a@acme.io", Instant.now());
    }

    private NotificationSender okSender() throws Exception {
        NotificationSender s = org.mockito.Mockito.mock(NotificationSender.class);
        when(s.channel()).thenReturn(NotificationChannel.EMAIL);
        return s;
    }

    private NotificationSender throwingSender() throws Exception {
        NotificationSender s = org.mockito.Mockito.mock(NotificationSender.class);
        when(s.channel()).thenReturn(NotificationChannel.EMAIL);
        doThrow(new RuntimeException("smtp down")).when(s).send(any(), any());
        return s;
    }

    private void claim(NotificationDelivery d) {
        when(deliveries.findPendingForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(requests.findById(d.getRequestEventId())).thenReturn(Optional.of(org.mockito.Mockito.mock(NotificationRequest.class)));
    }

    @Test
    void success_marksDeliveredAndPublishesDelivered() throws Exception {
        NotificationDelivery d = emailDelivery();
        claim(d);

        service(List.of(okSender())).fireDelivery(d.getId());

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        verify(outbox).append(eq("notification"), any(), eq(Topics.NOTIFICATION_DELIVERED), any(),
                any(NotificationDeliveredEvent.class));
        assertThat(registry.counter("notification.delivery.success").count()).isEqualTo(1.0);
    }

    @Test
    void firstFailure_retriesWithOneBackoffStep_noTerminalEvent() throws Exception {
        NotificationDelivery d = emailDelivery(); // attempts = 0
        claim(d);

        service(List.of(throwingSender())).fireDelivery(d.getId());

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PENDING); // not terminal
        assertThat(d.getAttempts()).isEqualTo(1);
        // attemptJustMade=1 -> next = now + 1*backoff.
        assertThat(Duration.between(d.getLastAttemptAt(), d.getNextAttemptAt()).toMillis()).isEqualTo(BACKOFF);
        verifyNoInteractions(outbox);
    }

    @Test
    void secondFailure_backoffScalesLinearly() throws Exception {
        NotificationDelivery d = emailDelivery();
        d.markRetry("x", Instant.now(), Instant.now()); // attempts = 1
        claim(d);

        service(List.of(throwingSender())).fireDelivery(d.getId());

        assertThat(d.getAttempts()).isEqualTo(2);
        // attemptJustMade=2 -> next = now + 2*backoff.
        assertThat(Duration.between(d.getLastAttemptAt(), d.getNextAttemptAt()).toMillis()).isEqualTo(2 * BACKOFF);
        verifyNoInteractions(outbox);
    }

    @Test
    void exhaustedAttempts_marksFailedAndPublishesFailed() throws Exception {
        NotificationDelivery d = emailDelivery();
        d.markRetry("x", Instant.now(), Instant.now()); // attempts = 1
        d.markRetry("x", Instant.now(), Instant.now()); // attempts = 2
        claim(d);

        service(List.of(throwingSender())).fireDelivery(d.getId());

        // attemptJustMade = 2+1 = 3 >= max -> terminal.
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(outbox).append(eq("notification"), any(), eq(Topics.NOTIFICATION_FAILED), any(),
                any(NotificationFailedEvent.class));
        assertThat(registry.counter("notification.delivery.failure").count()).isEqualTo(1.0);
    }

    @Test
    void claimEmpty_doesNothing() {
        UUID id = UUID.randomUUID();
        when(deliveries.findPendingForUpdate(id)).thenReturn(Optional.empty());

        service(List.of()).fireDelivery(id);

        verifyNoInteractions(outbox);
        verify(deliveries, never()).save(any());
    }

    @Test
    void noSenderForChannel_failsWithoutWastingRetries() {
        NotificationDelivery d = emailDelivery();
        claim(d);

        service(List.of()).fireDelivery(d.getId()); // no EMAIL sender registered

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(outbox).append(any(), any(), eq(Topics.NOTIFICATION_FAILED), any(), any());
    }

    @Test
    void missingRequest_failsDelivery() {
        NotificationDelivery d = emailDelivery();
        when(deliveries.findPendingForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(requests.findById(d.getRequestEventId())).thenReturn(Optional.empty());

        service(List.of()).fireDelivery(d.getId());

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(outbox).append(any(), any(), eq(Topics.NOTIFICATION_FAILED), any(), any());
    }
}
