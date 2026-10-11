
package com.finflow.payment.infrastructure.persistence;

import com.finflow.payment.domain.payment.Payment;
import com.finflow.payment.domain.payment.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class PaymentRepositoryAdapter implements PaymentRepository {

    private final SpringDataPaymentRepository delegate;

    @PersistenceContext
    private EntityManager entityManager;

    PaymentRepositoryAdapter(SpringDataPaymentRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(UUID id) {
        return delegate.findByIdForUpdate(id);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return delegate.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public void lockByIdempotencyKey(String idempotencyKey) {
        entityManager.createNativeQuery(
                        "SELECT pg_advisory_xact_lock(" +
                                "hashtextextended(:idempotencyKey, 0))"
                )
                .setParameter("idempotencyKey", idempotencyKey)
                .getSingleResult();
    }

    @Override
    public Payment save(Payment payment) {
        return delegate.save(payment);
    }
}
