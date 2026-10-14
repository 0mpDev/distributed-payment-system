package com.finflow.notification.infrastructure.persistence;

import com.finflow.notification.domain.notification.NotificationLog;
import com.finflow.notification.domain.notification.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataNotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    boolean existsByPaymentIdAndType(UUID paymentId, NotificationType type);
}
