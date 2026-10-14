
package com.finflow.payment.domain.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByIdForUpdate(UUID id);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    void lockByIdempotencyKey(String idempotencyKey);

    /** PENDING payments created before {@code olderThan}, oldest first (used by the recovery job). */
    List<Payment> findStalePending(Instant olderThan, int limit);

    Payment save(Payment payment);
}
