
package com.finflow.payment.domain.exception;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key was already used with different payment details: "
                + idempotencyKey);
    }
}
