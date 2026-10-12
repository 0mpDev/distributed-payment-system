
package com.finflow.payment.infrastructure.persistence;

import com.finflow.payment.domain.payment.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentRepositoryAdapter.class)
class PaymentRepositoryAdapterIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("finflow_test")
                    .withUsername("finflow")
                    .withPassword("finflow");

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                POSTGRES::getDriverClassName);
    }

    @Test
    void sameIdempotencyKeyMakesSecondTransactionWaitForFirstThroughRepositoryAdapter()
            throws Exception {

        var transactionTemplate =
                new TransactionTemplate(transactionManager);

        String key = "adapter-lock-test-" + UUID.randomUUID();

        var firstTransactionHasLock = new CountDownLatch(1);
        var releaseFirstTransaction = new CountDownLatch(1);
        var secondTransactionStarted = new CountDownLatch(1);
        var secondTransactionAcquiredLock = new CountDownLatch(1);
        var secondLockAcquired = new AtomicBoolean(false);

        try (var executor = Executors.newFixedThreadPool(2)) {

            var first = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        paymentRepository.lockByIdempotencyKey(key);
                        firstTransactionHasLock.countDown();

                        try {
                            if (!releaseFirstTransaction.await(
                                    10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "Timed out waiting to release first transaction"
                                );
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    })
            );

            assertThat(firstTransactionHasLock.await(5, TimeUnit.SECONDS))
                    .as("First transaction should acquire the lock")
                    .isTrue();

            var second = executor.submit(() -> {
                secondTransactionStarted.countDown();

                transactionTemplate.executeWithoutResult(status -> {
                    paymentRepository.lockByIdempotencyKey(key);
                    secondLockAcquired.set(true);
                    secondTransactionAcquiredLock.countDown();
                });
            });

            assertThat(secondTransactionStarted.await(5, TimeUnit.SECONDS))
                    .as("Second transaction should start")
                    .isTrue();

            try {
                assertThat(secondTransactionAcquiredLock.await(
                        500, TimeUnit.MILLISECONDS))
                        .as("Second transaction must wait while first holds the lock")
                        .isFalse();
            } finally {
                releaseFirstTransaction.countDown();
            }

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertThat(secondLockAcquired).isTrue();
        }
    }
}
