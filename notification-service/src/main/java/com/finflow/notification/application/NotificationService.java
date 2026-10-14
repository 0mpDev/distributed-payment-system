package com.finflow.notification.application;

import com.finflow.notification.domain.notification.NotificationLog;
import com.finflow.notification.domain.notification.NotificationLogRepository;
import com.finflow.notification.domain.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository repository;

    public NotificationService(NotificationLogRepository repository) {
        this.repository = repository;
    }

    public void notifyPaymentApproved(UUID recipientId, UUID paymentId) {
        notifyOnce(recipientId, paymentId, NotificationType.PAYMENT_APPROVED);
    }

    public void notifyPaymentRejected(UUID recipientId, UUID paymentId) {
        notifyOnce(recipientId, paymentId, NotificationType.PAYMENT_REJECTED);
    }

    // Kafka is at-least-once, so the same event can be redelivered. The unique index on
    // (payment_id, type) is the real guarantee; this check just avoids the noisy exception on the common path.
    private void notifyOnce(UUID recipientId, UUID paymentId, NotificationType type) {
        if (repository.existsByPaymentIdAndType(paymentId, type)) {
            log.warn("type={} paymentId={} already sent - skipping duplicate delivery", type, paymentId);
            return;
        }
        log.info("channel=EMAIL type={} recipientId={} paymentId={}", type, recipientId, paymentId);
        repository.save(NotificationLog.of(recipientId, paymentId, type));
    }
}
