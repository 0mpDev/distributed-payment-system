package com.finflow.notification.infrastructure.persistence;

import com.finflow.notification.domain.notification.NotificationLog;
import com.finflow.notification.domain.notification.NotificationLogRepository;
import com.finflow.notification.domain.notification.NotificationType;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class NotificationLogRepositoryAdapter implements NotificationLogRepository {

    private final SpringDataNotificationLogRepository delegate;

    NotificationLogRepositoryAdapter(SpringDataNotificationLogRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public NotificationLog save(NotificationLog log) {
        return delegate.save(log);
    }

    @Override
    public boolean existsByPaymentIdAndType(UUID paymentId, NotificationType type) {
        return delegate.existsByPaymentIdAndType(paymentId, type);
    }
}
