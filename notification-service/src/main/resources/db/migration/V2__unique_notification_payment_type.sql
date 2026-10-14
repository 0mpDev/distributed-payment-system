-- One notification per payment per type. Makes duplicate Kafka deliveries harmless.
-- Note: this fails if your local DB already contains duplicates; clean them first or `docker compose down -v`.
CREATE UNIQUE INDEX uq_notification_logs_payment_type
    ON notification.notification_logs (payment_id, type);
