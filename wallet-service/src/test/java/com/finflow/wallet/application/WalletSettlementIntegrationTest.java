
package com.finflow.wallet.application;

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
