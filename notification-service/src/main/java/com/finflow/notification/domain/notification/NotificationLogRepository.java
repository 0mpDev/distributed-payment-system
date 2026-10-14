package com.finflow.notification.domain.notification;

import java.util.UUID;

public interface NotificationLogRepository {
    NotificationLog save(NotificationLog log);

    boolean existsByPaymentIdAndType(UUID paymentId, NotificationType type);
}
