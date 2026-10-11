
package com.finflow.wallet.application;

import com.finflow.wallet.domain.exception.InsufficientFundsException;
import com.finflow.wallet.application.command.ReserveBalanceCommand;
import com.finflow.wallet.application.command.ReleaseReservationCommand;
import com.finflow.wallet.WalletServiceApplication;
import com.finflow.wallet.application.command.SettlePaymentCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = WalletServiceApplication.class)
@Testcontainers
class WalletSettlementIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("wallet_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WalletApplicationService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void settle_WhenDatabaseConstraintFails_RollsBackWalletChangesAndEntries() {
        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        UUID payerWalletId = UUID.randomUUID();
        UUID payeeWalletId = UUID.randomUUID();

        insertWallet(payerWalletId, payerId, "500.00");
        insertWallet(payeeWalletId, payeeId, "50.00");

        jdbcTemplate.execute("""
                CREATE FUNCTION wallet.reject_credit_entry()
                RETURNS trigger AS $$
                BEGIN
                    IF NEW.type = 'CREDIT' THEN
                        RAISE EXCEPTION 'Forced failure for rollback test';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);

        jdbcTemplate.execute("""
                CREATE TRIGGER reject_credit_entry_trigger
                BEFORE INSERT ON wallet.wallet_entries
                FOR EACH ROW EXECUTE FUNCTION wallet.reject_credit_entry()
                """);

        try {
            assertThatThrownBy(() ->
                    walletService.settle(new SettlePaymentCommand(
                            paymentId, payerId, payeeId,
                            new BigDecimal("100.00"))))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS reject_credit_entry_trigger
                    ON wallet.wallet_entries
                    """);
            jdbcTemplate.execute("""
                    DROP FUNCTION IF EXISTS wallet.reject_credit_entry()
                    """);
        }

        BigDecimal payerBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet.wallets WHERE id = ?",
                BigDecimal.class, payerWalletId);

        BigDecimal payeeBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet.wallets WHERE id = ?",
                BigDecimal.class, payeeWalletId);

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet.wallet_entries WHERE payment_id = ?",
                Integer.class, paymentId);

        assertThat(payerBalance).isEqualByComparingTo("500.00");
        assertThat(payeeBalance).isEqualByComparingTo("50.00");
        assertThat(entryCount).isZero();
    }


    @Test
    void settle_WhenCalledConcurrentlyForSamePayment_TransfersMoneyOnlyOnce()
            throws Exception {

        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        UUID payerWalletId = UUID.randomUUID();
        UUID payeeWalletId = UUID.randomUUID();

        insertWallet(payerWalletId, payerId, "500.00");
        jdbcTemplate.update(
                "UPDATE wallet.wallets SET reserved_balance = ? WHERE id = ?",
                new BigDecimal("100.00"),
                payerWalletId
        );
        insertWallet(payeeWalletId, payeeId, "50.00");

        SettlePaymentCommand command = new SettlePaymentCommand(
                paymentId,
                payerId,
                payeeId,
                new BigDecimal("100.00")
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {

            Future<Throwable> first = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }

                    walletService.settle(command);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            Future<Throwable> second = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }

                    walletService.settle(command);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("Both requests should be ready")
                    .isTrue();

            start.countDown();

            Throwable firstError = first.get(30, TimeUnit.SECONDS);
            Throwable secondError = second.get(30, TimeUnit.SECONDS);

            BigDecimal payerBalance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class,
                    payerWalletId
            );

            BigDecimal payeeBalance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class,
                    payeeWalletId
            );

            Integer settlementCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM wallet.wallet_entries
                    WHERE payment_id = ? AND type = 'SETTLEMENT'
                    """,
                    Integer.class,
                    paymentId
            );

            Integer creditCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM wallet.wallet_entries
                    WHERE payment_id = ? AND type = 'CREDIT'
                    """,
                    Integer.class,
                    paymentId
            );

            // The transfer must happen exactly once.
            assertThat(payerBalance).isEqualByComparingTo("400.00");
            assertThat(payeeBalance).isEqualByComparingTo("150.00");
            assertThat(settlementCount).isEqualTo(1);
            assertThat(creditCount).isEqualTo(1);

            // Both duplicate requests should be handled without an error.
            assertThat(firstError)
                    .as("First concurrent settlement should succeed")
                    .isNull();

            assertThat(secondError)
                    .as("Second concurrent settlement should be idempotent")
                    .isNull();
        }
    }

    @Test
    void reserve_WhenCalledConcurrentlyForSamePayment_ReservesMoneyOnlyOnce()
            throws Exception {

        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        insertWallet(walletId, userId, "500.00");

        ReserveBalanceCommand command = new ReserveBalanceCommand(
                paymentId,
                userId,
                new BigDecimal("100.00"),
                "BRL"
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> first = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    walletService.reserve(command);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            Future<Throwable> second = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    walletService.reserve(command);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable firstError = first.get(30, TimeUnit.SECONDS);
            Throwable secondError = second.get(30, TimeUnit.SECONDS);

            BigDecimal balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class,
                    walletId
            );

            BigDecimal reservedBalance = jdbcTemplate.queryForObject(
                    "SELECT reserved_balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class,
                    walletId
            );

            Integer reservationCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM wallet.wallet_entries
                    WHERE payment_id = ? AND type = 'RESERVATION'
                    """,
                    Integer.class,
                    paymentId
            );

            assertThat(balance).isEqualByComparingTo("500.00");
            assertThat(reservedBalance).isEqualByComparingTo("100.00");
            assertThat(reservationCount).isEqualTo(1);
            assertThat(firstError).as("First reservation").isNull();
            assertThat(secondError).as("Duplicate reservation").isNull();
        }
    }

    @Test
    void release_WhenCalledConcurrentlyForSamePayment_ReleasesMoneyOnlyOnce()
            throws Exception {

        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        insertWallet(walletId, userId, "500.00");

        // Set up funds reserved by this payment.
        jdbcTemplate.update(
                "UPDATE wallet.wallets SET reserved_balance = ? WHERE id = ?",
                new BigDecimal("100.00"),
                walletId
        );

        jdbcTemplate.update(
                """
                INSERT INTO wallet.wallet_entries
                    (id, wallet_id, payment_id, type, amount, created_at)
                VALUES (?, ?, ?, 'RESERVATION', ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                walletId,
                paymentId,
                new BigDecimal("100.00")
        );

        ReleaseReservationCommand command = new ReleaseReservationCommand(
                paymentId,
                userId,
                new BigDecimal("100.00")
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> first = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    walletService.release(command);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            Future<Throwable> second = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    walletService.release(command);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable firstError = first.get(30, TimeUnit.SECONDS);
            Throwable secondError = second.get(30, TimeUnit.SECONDS);

            BigDecimal balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class,
                    walletId
            );

            BigDecimal reservedBalance = jdbcTemplate.queryForObject(
                    "SELECT reserved_balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class,
                    walletId
            );

            Integer releaseCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM wallet.wallet_entries
                    WHERE payment_id = ? AND type = 'RELEASE'
                    """,
                    Integer.class,
                    paymentId
            );

            assertThat(balance).isEqualByComparingTo("500.00");
            assertThat(reservedBalance).isEqualByComparingTo("0.00");
            assertThat(releaseCount).isEqualTo(1);
            assertThat(firstError).as("First release").isNull();
            assertThat(secondError).as("Duplicate release").isNull();
        }
    }


    @Test
    void reserve_WhenConcurrentPaymentsExceedAvailableBalance_AllowsOnlyOne()
            throws Exception {

        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        UUID firstPaymentId = UUID.randomUUID();
        UUID secondPaymentId = UUID.randomUUID();

        insertWallet(walletId, userId, "500.00");

        ReserveBalanceCommand firstCommand = new ReserveBalanceCommand(
                firstPaymentId, userId, new BigDecimal("400.00"), "BRL"
        );

        ReserveBalanceCommand secondCommand = new ReserveBalanceCommand(
                secondPaymentId, userId, new BigDecimal("400.00"), "BRL"
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> first = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    walletService.reserve(firstCommand);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            Future<Throwable> second = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                    }
                    walletService.reserve(secondCommand);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable firstError = first.get(30, TimeUnit.SECONDS);
            Throwable secondError = second.get(30, TimeUnit.SECONDS);

            BigDecimal balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class, walletId
            );

            BigDecimal reservedBalance = jdbcTemplate.queryForObject(
                    "SELECT reserved_balance FROM wallet.wallets WHERE id = ?",
                    BigDecimal.class, walletId
            );

            Integer reservationCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM wallet.wallet_entries
                    WHERE type = 'RESERVATION'
                      AND payment_id IN (?, ?)
                    """,
                    Integer.class, firstPaymentId, secondPaymentId
            );

            assertThat(balance).isEqualByComparingTo("500.00");
            assertThat(reservedBalance).isEqualByComparingTo("400.00");
            assertThat(reservationCount).isEqualTo(1);

            assertThat(firstError == null ^ secondError == null)
                    .as("Exactly one reservation should succeed")
                    .isTrue();

            Throwable failedRequest = firstError != null ? firstError : secondError;

            assertThat(failedRequest)
                    .isInstanceOf(InsufficientFundsException.class);
        }
    }


    private void insertWallet(UUID walletId, UUID userId, String balance) {
        jdbcTemplate.update("""
                INSERT INTO wallet.wallets
                    (id, user_id, balance, reserved_balance, currency,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, 0, 'BRL', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                walletId, userId, new BigDecimal(balance));
    }
}
