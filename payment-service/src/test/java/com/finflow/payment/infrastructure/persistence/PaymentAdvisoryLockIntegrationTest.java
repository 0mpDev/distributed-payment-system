package com.finflow.payment.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PaymentAdvisoryLockIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("finflow_test")
                    .withUsername("finflow")
                    .withPassword("finflow");

    @Test
    void sameIdempotencyKeyMakesSecondTransactionWaitForFirst() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        dataSource.setDriverClassName("org.postgresql.Driver");

        var jdbcTemplate = new JdbcTemplate(dataSource);

        var transactionManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        dataSource
                );
        var transactionTemplate = new TransactionTemplate(transactionManager);

        String key = "lock-test-" + UUID.randomUUID();

        var firstTransactionHasLock = new CountDownLatch(1);
        var releaseFirstTransaction = new CountDownLatch(1);
        var secondTransactionStarted = new CountDownLatch(1);
        var secondTransactionAcquiredLock = new CountDownLatch(1);
        var secondLockAcquired = new AtomicBoolean(false);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject(
                                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                                Object.class,
                                key
                        );

                        firstTransactionHasLock.countDown();

                        try {
                            if (!releaseFirstTransaction.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "Timed out waiting to release the first transaction"
                                );
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    })
            );

            assertThat(firstTransactionHasLock.await(5, TimeUnit.SECONDS))
                    .as("first transaction should acquire the advisory lock")
                    .isTrue();

            Future<?> second = executor.submit(() -> {
                secondTransactionStarted.countDown();

                transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                            Object.class,
                            key
                    );

                    secondLockAcquired.set(true);
                    secondTransactionAcquiredLock.countDown();
                });
            });

            assertThat(secondTransactionStarted.await(5, TimeUnit.SECONDS))
                    .as("second transaction should start")
                    .isTrue();

            try {
                assertThat(secondTransactionAcquiredLock.await(500, TimeUnit.MILLISECONDS))
                        .as("second transaction should wait while first holds the lock")
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
