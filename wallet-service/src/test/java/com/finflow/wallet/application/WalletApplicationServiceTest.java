package com.finflow.wallet.application;

import com.finflow.wallet.application.command.CreateWalletCommand;
import com.finflow.wallet.application.command.ReserveBalanceCommand;
import com.finflow.wallet.domain.exception.InsufficientFundsException;
import com.finflow.wallet.domain.exception.WalletAlreadyExistsException;
import com.finflow.wallet.domain.exception.WalletNotFoundException;
import com.finflow.wallet.domain.wallet.Wallet;
import com.finflow.wallet.domain.wallet.WalletEntryRepository;
import com.finflow.wallet.domain.wallet.WalletRepository;
import com.finflow.wallet.application.command.SettlePaymentCommand;
import com.finflow.wallet.domain.wallet.EntryType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletApplicationServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletEntryRepository entryRepository;

    @InjectMocks
    private WalletApplicationService walletService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void createWallet_WithNewUser_CreatesWalletSuccessfully() {
        when(walletRepository.existsByUserId(userId)).thenReturn(false);
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = walletService.createWallet(new CreateWalletCommand(userId, new BigDecimal("1000.00"), "BRL"));

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(result.getAvailableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void createWallet_WithExistingUser_ThrowsWalletAlreadyExists() {
        when(walletRepository.existsByUserId(userId)).thenReturn(true);

        assertThatThrownBy(() ->
                walletService.createWallet(new CreateWalletCommand(userId, BigDecimal.TEN, "BRL"))
        ).isInstanceOf(WalletAlreadyExistsException.class);
    }

    @Test
    void reserve_WithSufficientBalance_DecreasesAvailableBalance() {
        var wallet = Wallet.create(userId, new BigDecimal("500.00"), "BRL");
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.reserve(new ReserveBalanceCommand(UUID.randomUUID(), userId, new BigDecimal("200.00"), "BRL"));

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("300.00");
        assertThat(wallet.getReservedBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void reserve_WithInsufficientBalance_ThrowsInsufficientFunds() {
        var wallet = Wallet.create(userId, new BigDecimal("100.00"), "BRL");
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() ->
                walletService.reserve(new ReserveBalanceCommand(UUID.randomUUID(), userId, new BigDecimal("500.00"), "BRL"))
        ).isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void reserve_ForUnknownUser_ThrowsWalletNotFound() {
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                walletService.reserve(new ReserveBalanceCommand(UUID.randomUUID(), userId, BigDecimal.ONE, "BRL"))
        ).isInstanceOf(WalletNotFoundException.class);
    }


    @Test
    void settle_WithSamePayerAndPayee_ThrowsIllegalArgumentException() {
        UUID walletId = UUID.randomUUID();

        var command = new SettlePaymentCommand(
                UUID.randomUUID(),
                walletId,
                walletId,
                new BigDecimal("100.00")
        );

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Payer and payee must be different users");

        verifyNoInteractions(walletRepository);
    }

    @Test
    void settle_WithZeroAmount_ThrowsIllegalArgumentException() {
        var command = new SettlePaymentCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.ZERO
        );

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Settlement amount must be positive");

        verifyNoInteractions(walletRepository);
    }

    @Test
    void settle_WithNegativeAmount_ThrowsIllegalArgumentException() {
        var command = new SettlePaymentCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("-10.00")
        );

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Settlement amount must be positive");

        verifyNoInteractions(walletRepository);
    }

    @Test
    void settle_WithValidPayment_DebitsPayerAndCreditsPayee() {
        UUID paymentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();

        var payer = Wallet.create(
                payerId, new BigDecimal("500.00"), "BRL");
        var payee = Wallet.create(
                payeeId, new BigDecimal("50.00"), "BRL");

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.SETTLEMENT)).thenReturn(false);
        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.CREDIT)).thenReturn(false);

        when(walletRepository.findByUserIdWithLock(payerId))
                .thenReturn(Optional.of(payer));
        when(walletRepository.findByUserIdWithLock(payeeId))
                .thenReturn(Optional.of(payee));

        when(walletRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new SettlePaymentCommand(
                paymentId, payerId, payeeId, new BigDecimal("100.00"));

        walletService.settle(command);

        assertThat(payer.getBalance()).isEqualByComparingTo("400.00");
        assertThat(payee.getBalance()).isEqualByComparingTo("150.00");

        verify(walletRepository, times(2)).save(any());
        verify(entryRepository, times(2)).save(any());
    }

    @Test
    void settle_WhenPaymentAlreadySettled_SkipsDuplicateSettlement() {
        UUID paymentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();

        var command = new SettlePaymentCommand(
                paymentId,
                payerId,
                payeeId,
                new BigDecimal("100.00")
        );

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.SETTLEMENT))
                .thenReturn(true);

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.CREDIT))
                .thenReturn(true);

        walletService.settle(command);

        // Existing settlement must not modify wallets or create new entries.
        verifyNoInteractions(walletRepository);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void settle_WhenOnlySettlementEntryExists_ThrowsIllegalStateException() {
        UUID paymentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();

        var command = new SettlePaymentCommand(
                paymentId,
                payerId,
                payeeId,
                new BigDecimal("100.00")
        );

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.SETTLEMENT))
                .thenReturn(true);

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.CREDIT))
                .thenReturn(false);

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inconsistent wallet settlement");

        verifyNoInteractions(walletRepository);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void settle_WhenOnlyCreditEntryExists_ThrowsIllegalStateException() {
        UUID paymentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();

        var command = new SettlePaymentCommand(
                paymentId,
                payerId,
                payeeId,
                new BigDecimal("100.00")
        );

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.SETTLEMENT))
                .thenReturn(false);

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.CREDIT))
                .thenReturn(true);

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inconsistent wallet settlement");

        verifyNoInteractions(walletRepository);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void settle_WhenPayerWalletDoesNotExist_ThrowsWalletNotFoundException() {
        UUID paymentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();

        var command = new SettlePaymentCommand(
                paymentId, payerId, payeeId, new BigDecimal("100.00"));

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.SETTLEMENT)).thenReturn(false);
        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.CREDIT)).thenReturn(false);

        UUID firstId = payerId.compareTo(payeeId) < 0 ? payerId : payeeId;

        when(walletRepository.findByUserIdWithLock(firstId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(WalletNotFoundException.class);

        verify(entryRepository, never()).save(any());
    }

    @Test
    void settle_WhenPayeeWalletDoesNotExist_ThrowsWalletNotFoundException() {
        UUID paymentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();

        var command = new SettlePaymentCommand(
                paymentId, payerId, payeeId, new BigDecimal("100.00"));

        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.SETTLEMENT)).thenReturn(false);
        when(entryRepository.existsByPaymentIdAndType(
                paymentId, EntryType.CREDIT)).thenReturn(false);

        UUID firstId = payerId.compareTo(payeeId) < 0 ? payerId : payeeId;
        UUID secondId = firstId.equals(payerId) ? payeeId : payerId;

        when(walletRepository.findByUserIdWithLock(firstId))
                .thenReturn(Optional.of(Wallet.create(
                        firstId, new BigDecimal("500.00"), "BRL")));
        when(walletRepository.findByUserIdWithLock(secondId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(WalletNotFoundException.class);

        verify(walletRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    void settle_WithNullAmount_ThrowsIllegalArgumentException() {
        var command = new SettlePaymentCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThatThrownBy(() -> walletService.settle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Settlement amount must be positive");

        verifyNoInteractions(walletRepository);
    }

}
