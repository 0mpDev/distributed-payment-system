package com.finflow.payment.infrastructure.recovery;

import com.finflow.payment.application.PaymentApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Safety net for the create-payment flow: finds payments that were recorded but never advanced
 * (service crashed, wallet-service was down, DB error after the wallet reservation) and re-drives them.
 */
@Component
public class PendingPaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentRecoveryJob.class);

    // Must comfortably exceed the wallet read timeout (10s) so we never race a request that is still in flight.
    private static final Duration STALE_AFTER = Duration.ofSeconds(30);
    private static final int BATCH_SIZE = 50;

    private final PaymentApplicationService paymentService;

    public PendingPaymentRecoveryJob(PaymentApplicationService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void run() {
        int recovered = paymentService.recoverStalePayments(STALE_AFTER, BATCH_SIZE);
        if (recovered > 0) {
            log.info("recovered {} stale PENDING payments", recovered);
        }
    }
}
