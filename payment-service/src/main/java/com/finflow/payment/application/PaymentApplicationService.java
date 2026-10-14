
package com.finflow.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.events.fraud.FraudAnalysisCompletedEvent;
import com.finflow.events.fraud.FraudAnalysisRequestedEvent;
import com.finflow.events.payment.PaymentApprovedEvent;
import com.finflow.events.payment.PaymentRejectedEvent;
import com.finflow.payment.application.command.CreatePaymentCommand;
import com.finflow.payment.domain.exception.IdempotencyKeyConflictException;
import com.finflow.payment.domain.exception.InsufficientFundsException;
import com.finflow.payment.domain.exception.PaymentNotFoundException;
import com.finflow.payment.domain.outbox.OutboxEvent;
import com.finflow.payment.domain.outbox.OutboxEventRepository;
import com.finflow.payment.domain.payment.Payment;
import com.finflow.payment.domain.payment.PaymentRepository;
import com.finflow.payment.domain.payment.PaymentStatus;
import com.finflow.payment.infrastructure.client.WalletServiceClient;
import com.finflow.payment.infrastructure.metrics.PaymentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PaymentApplicationService {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentApplicationService.class);

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxRepository;
    private final WalletServiceClient walletClient;
    private final ObjectMapper objectMapper;
    private final PaymentMetrics metrics;
    private final TransactionOperations tx;

    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";

    private record Registration(Payment payment, boolean isNew) {}

    public PaymentApplicationService(
            PaymentRepository paymentRepository,
            OutboxEventRepository outboxRepository,
            WalletServiceClient walletClient,
            ObjectMapper objectMapper,
            PaymentMetrics metrics,
            TransactionOperations tx) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.walletClient = walletClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.tx = tx;
    }

    /**
     * Deliberately NOT one big transaction. The wallet reservation is a remote call that
     * commits in the wallet-service's own database, so it can never be rolled back together
     * with our transaction. Doing it inside our transaction meant a failure after the call
     * left funds reserved with no payment record (and nothing to release them).
     *
     * <ol>
     *   <li>TX 1: durably record the payment as PENDING (idempotency-key advisory lock held here).</li>
     *   <li>No transaction: reserve funds. Wallet de-duplicates on paymentId, so retrying is safe.</li>
     *   <li>TX 3: move PENDING to PENDING_FRAUD_REVIEW (or REJECTED) and write the outbox event.</li>
     * </ol>
     * If anything fails after step 1 the payment stays PENDING, and
     * {@link #recoverStalePayments} re-drives steps 2-3 until it completes.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Payment createPayment(CreatePaymentCommand command) {
        var registration = tx.execute(status -> register(command));
        if (!registration.isNew()) {
            return registration.payment();
        }
        return reserveAndAdvance(registration.payment());
    }

    /**
     * Re-drives payments stuck in PENDING (crash, wallet outage or DB error after TX 1).
     * Safe to run on several instances at once: the wallet reservation is idempotent and
     * {@code advanceAfterReserve} re-checks the status under a row lock.
     *
     * @return number of payments that moved out of PENDING
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int recoverStalePayments(Duration staleAfter, int batchSize) {
        var cutoff = Instant.now().minus(staleAfter);
        List<Payment> stale = tx.execute(status -> paymentRepository.findStalePending(cutoff, batchSize));
        if (stale == null) {
            return 0;
        }

        int recovered = 0;
        for (var payment : stale) {
            try {
                if (reserveAndAdvance(payment).getStatus() != PaymentStatus.PENDING) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                log.warn("recovery failed for paymentId={}; will retry on next run", payment.getId(), e);
            }
        }
        return recovered;
    }

    private Registration register(CreatePaymentCommand command) {
        // Serialize creation requests that use the same idempotency key.
        // PostgreSQL releases this advisory lock when the transaction ends.
        paymentRepository.lockByIdempotencyKey(command.idempotencyKey());

        var existing = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return new Registration(returnExistingOrThrow(existing.get(), command), false);
        }

        var payment = Payment.create(
                command.payerId(),
                command.payeeId(),
                command.amount(),
                command.currency(),
                command.idempotencyKey()
        );
        paymentRepository.save(payment);
        metrics.recordCreated();
        return new Registration(payment, true);
    }

    private Payment reserveAndAdvance(Payment payment) {
        String rejectionReason = null;
        try {
            // No DB transaction or connection is held during this remote call.
            walletClient.reserve(
                    payment.getId(),
                    payment.getPayerId(),
                    payment.getAmount(),
                    payment.getCurrency()
            );
        } catch (InsufficientFundsException e) {
            rejectionReason = INSUFFICIENT_FUNDS;
        } catch (RestClientResponseException e) {
            if (!e.getStatusCode().is4xxClientError()) {
                return stayPending(payment, e);
            }
            rejectionReason = "Wallet rejected the reservation (HTTP " + e.getStatusCode().value() + ")";
        } catch (ResourceAccessException e) {
            return stayPending(payment, e);
        }

        final String reason = rejectionReason;
        return tx.execute(status -> advanceAfterReserve(payment.getId(), reason));
    }

    private Payment stayPending(Payment payment, Exception cause) {
        log.warn("wallet unavailable for paymentId={}; leaving PENDING for recovery job: {}",
                payment.getId(), cause.toString());
        return payment;
    }

    private Payment advanceAfterReserve(UUID paymentId, String rejectionReason) {
        var payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return payment; // already advanced by another thread or the recovery job
        }

        if (rejectionReason != null) {
            payment.reject(rejectionReason);
            paymentRepository.save(payment);

            enqueue(
                    payment.getId(),
                    "payment.rejected",
                    "payment-rejected",
                    new PaymentRejectedEvent(
                            UUID.randomUUID(),
                            Instant.now(),
                            payment.getId(),
                            rejectionReason
                    )
            );

            metrics.recordRejected(
                    INSUFFICIENT_FUNDS.equals(rejectionReason) ? "insufficient_funds" : "wallet_error");

            log.info("paymentId={} status=REJECTED reason={}", payment.getId(), rejectionReason);
            return payment;
        }

        payment.markPendingFraudReview();
        paymentRepository.save(payment);

        enqueue(
                payment.getId(),
                "fraud.analysis.requested",
                "fraud-analysis-requested",
                new FraudAnalysisRequestedEvent(
                        UUID.randomUUID(),
                        Instant.now(),
                        payment.getId(),
                        payment.getPayerId(),
                        payment.getAmount(),
                        payment.getCurrency()
                )
        );

        log.info("paymentId={} status=PENDING_FRAUD_REVIEW outbox queued", payment.getId());
        return payment;
    }

    private Payment returnExistingOrThrow(
            Payment existing,
            CreatePaymentCommand command) {

        boolean sameRequest =
                existing.getPayerId().equals(command.payerId())
                        && existing.getPayeeId().equals(command.payeeId())
                        && existing.getAmount().compareTo(command.amount()) == 0
                        && existing.getCurrency().equals(command.currency());

        if (!sameRequest) {
            throw new IdempotencyKeyConflictException(
                    command.idempotencyKey()
            );
        }

        log.info(
                "Returning existing payment {} for idempotency key {}",
                existing.getId(),
                command.idempotencyKey()
        );

        return existing;
    }

    @Transactional(readOnly = true)
    public Payment findById(UUID id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    public void processFraudResult(FraudAnalysisCompletedEvent event) {
        var payment = paymentRepository.findByIdForUpdate(event.paymentId())
                .orElseThrow(
                        () -> new PaymentNotFoundException(event.paymentId())
                );

        if (payment.getStatus() != PaymentStatus.PENDING_FRAUD_REVIEW) {
            log.info(
                    "Ignoring fraud result for paymentId={} because status={}",
                    payment.getId(),
                    payment.getStatus()
            );
            return;
        }

        switch (event.decision()) {
            case APPROVED -> {
                walletClient.settle(
                        payment.getId(),
                        payment.getPayerId(),
                        payment.getPayeeId(),
                        payment.getAmount()
                );

                payment.approve();
                paymentRepository.save(payment);

                enqueue(
                        payment.getId(),
                        "payment.approved",
                        "payment-approved",
                        new PaymentApprovedEvent(
                                UUID.randomUUID(),
                                Instant.now(),
                                payment.getId(),
                                payment.getPayerId()
                        )
                );

                metrics.recordApproved();

                log.info(
                        "paymentId={} status=APPROVED",
                        payment.getId()
                );
            }

            case REJECTED -> {
                walletClient.release(
                        payment.getId(),
                        payment.getPayerId(),
                        payment.getAmount()
                );

                payment.reject(event.reason());
                paymentRepository.save(payment);

                enqueue(
                        payment.getId(),
                        "payment.rejected",
                        "payment-rejected",
                        new PaymentRejectedEvent(
                                UUID.randomUUID(),
                                Instant.now(),
                                payment.getId(),
                                event.reason()
                        )
                );

                metrics.recordRejected("fraud");

                log.info(
                        "paymentId={} status=REJECTED reason={}",
                        payment.getId(),
                        event.reason()
                );
            }
        }
    }

    private void enqueue(
            UUID aggregateId,
            String eventType,
            String topic,
            Object event) {

        try {
            outboxRepository.save(
                    OutboxEvent.of(
                            aggregateId,
                            eventType,
                            topic,
                            objectMapper.writeValueAsString(event)
                    )
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize outbox event",
                    e
            );
        }
    }
}
