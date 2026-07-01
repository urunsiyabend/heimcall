package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequestRepository;
import com.urunsiyabend.heimcall.notification.sender.NotificationSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.when;

/**
 * Phase 20 T1: notification delivery sending ({@link DeliveryService#sendClaimed}) — the retry/backoff
 * decision (linear {@code attempts × backoff}, terminal FAILED at {@code max-attempts}), the
 * success/failure counters, the missing-request / no-sender guards, and the fencing guard (when
 * {@link DeliveryTx} reports the lease was lost, no metric is recorded). Pure Mockito; {@code DeliveryTx}
 * is mocked so the un-transactional send path is tested in isolation. The two-phase claim's
 * exactly-one-claimer / lease semantics are proven against real PG in {@code NotificationDeliveryClaimTest}.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    private static final long BACKOFF = 10_000L;
    private static final int MAX_ATTEMPTS = 3;
    private static final long LEASE_MS = 60_000L;

    @Mock DeliveryTx tx;
    @Mock NotificationRequestRepository requests;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private DeliveryService service(List<NotificationSender> senders) {
        return new DeliveryService(tx, requests, senders, registry, MAX_ATTEMPTS, BACKOFF, LEASE_MS);
    }

    /** A delivery already claimed for sending (status SENDING, lease stamped), with {@code priorAttempts} prior tries. */
    private NotificationDelivery claimed(int priorAttempts) {
        NotificationDelivery d = NotificationDelivery.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), NotificationChannel.EMAIL, "a@acme.io", Instant.now());
        for (int i = 0; i < priorAttempts; i++) {
            d.markRetry("x", Instant.now(), Instant.now());
        }
        d.claim(UUID.randomUUID(), Instant.now().plusMillis(LEASE_MS), Instant.now());
        return d;
    }

    private NotificationSender okSender() {
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

    private void haveRequest(NotificationDelivery d) {
        when(requests.findById(d.getRequestEventId()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(NotificationRequest.class)));
    }

    @Test
    void success_marksDeliveredAndCountsIt() {
        NotificationDelivery d = claimed(0);
        haveRequest(d);
        when(tx.finalizeDelivered(eq(d.getId()), eq(d.getLeaseToken()), any())).thenReturn(true);

        service(List.of(okSender())).sendClaimed(d);

        verify(tx).finalizeDelivered(eq(d.getId()), eq(d.getLeaseToken()), any());
        assertThat(registry.counter("notification.delivery.success").count()).isEqualTo(1.0);
    }

    @Test
    void deliveredButLeaseLost_doesNotCount() {
        NotificationDelivery d = claimed(0);
        haveRequest(d);
        // Fencing: the row was reclaimed by another worker (lease expired) -> finalize reports not-owned.
        when(tx.finalizeDelivered(eq(d.getId()), eq(d.getLeaseToken()), any())).thenReturn(false);

        service(List.of(okSender())).sendClaimed(d);

        assertThat(registry.counter("notification.delivery.success").count()).isEqualTo(0.0);
    }

    @Test
    void firstFailure_retriesWithOneBackoffStep_noTerminal() throws Exception {
        NotificationDelivery d = claimed(0); // attempts = 0
        haveRequest(d);

        service(List.of(throwingSender())).sendClaimed(d);

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        // attemptJustMade = 1 -> next = now + 1*backoff.
        verify(tx).finalizeRetry(eq(d.getId()), eq(d.getLeaseToken()), any(), next.capture(), now.capture());
        assertThat(Duration.between(now.getValue(), next.getValue()).toMillis()).isEqualTo(BACKOFF);
        verify(tx, never()).finalizeFailure(any(), any(), any(), any());
    }

    @Test
    void secondFailure_backoffScalesLinearly() throws Exception {
        NotificationDelivery d = claimed(1); // attempts = 1
        haveRequest(d);

        service(List.of(throwingSender())).sendClaimed(d);

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        // attemptJustMade = 2 -> next = now + 2*backoff.
        verify(tx).finalizeRetry(eq(d.getId()), eq(d.getLeaseToken()), any(), next.capture(), now.capture());
        assertThat(Duration.between(now.getValue(), next.getValue()).toMillis()).isEqualTo(2 * BACKOFF);
    }

    @Test
    void exhaustedAttempts_marksFailedAndCountsIt() throws Exception {
        NotificationDelivery d = claimed(2); // attempts = 2 -> attemptJustMade = 3 >= max
        haveRequest(d);
        when(tx.finalizeFailure(eq(d.getId()), eq(d.getLeaseToken()), any(), any())).thenReturn(true);

        service(List.of(throwingSender())).sendClaimed(d);

        verify(tx).finalizeFailure(eq(d.getId()), eq(d.getLeaseToken()), any(), any());
        verify(tx, never()).finalizeRetry(any(), any(), any(), any(), any());
        assertThat(registry.counter("notification.delivery.failure").count()).isEqualTo(1.0);
    }

    @Test
    void noSenderForChannel_failsWithoutWastingRetries() {
        NotificationDelivery d = claimed(0);
        haveRequest(d);
        when(tx.finalizeFailure(eq(d.getId()), eq(d.getLeaseToken()), any(), any())).thenReturn(true);

        service(List.of()).sendClaimed(d); // no EMAIL sender registered

        verify(tx).finalizeFailure(eq(d.getId()), eq(d.getLeaseToken()), any(), any());
        verify(tx, never()).finalizeRetry(any(), any(), any(), any(), any());
        assertThat(registry.counter("notification.delivery.failure").count()).isEqualTo(1.0);
    }

    @Test
    void missingRequest_failsDelivery() {
        NotificationDelivery d = claimed(0);
        when(requests.findById(d.getRequestEventId())).thenReturn(Optional.empty());
        when(tx.finalizeFailure(eq(d.getId()), eq(d.getLeaseToken()), any(), any())).thenReturn(true);

        service(List.of()).sendClaimed(d);

        verify(tx).finalizeFailure(eq(d.getId()), eq(d.getLeaseToken()), any(), any());
    }
}
