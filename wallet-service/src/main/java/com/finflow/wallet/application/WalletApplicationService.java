package com.finflow.wallet.application;

import com.finflow.wallet.application.command.CreateWalletCommand;
import com.finflow.wallet.application.command.ReleaseReservationCommand;
import com.finflow.wallet.application.command.ReserveBalanceCommand;
import com.finflow.wallet.application.command.SettlePaymentCommand;
import com.finflow.wallet.domain.exception.WalletAlreadyExistsException;
import com.finflow.wallet.domain.exception.WalletNotFoundException;
import com.finflow.wallet.domain.wallet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class WalletApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WalletApplicationService.class);

    private final WalletRepository walletRepository;
    private final WalletEntryRepository entryRepository;

    public WalletApplicationService(WalletRepository walletRepository, WalletEntryRepository entryRepository) {
        this.walletRepository = walletRepository;
        this.entryRepository = entryRepository;
    }

    public Wallet createWallet(CreateWalletCommand command) {
        if (walletRepository.existsByUserId(command.userId())) {
            throw new WalletAlreadyExistsException(command.userId());
        }
        var wallet = Wallet.create(command.userId(), command.initialBalance(), command.currency());
        return walletRepository.save(wallet);
    }

    @Transactional(readOnly = true)
    public Wallet findByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }

    public void reserve(ReserveBalanceCommand command) {
        if (entryRepository.existsByPaymentIdAndType(command.paymentId(), EntryType.RESERVATION)) {
            log.warn("paymentId={} RESERVATION already exists — skipping (idempotent)", command.paymentId());
            return;
        }

        var wallet = walletRepository.findByUserIdWithLock(command.userId())
                .orElseThrow(() -> new WalletNotFoundException(command.userId()));

        wallet.reserve(command.amount());
        walletRepository.save(wallet);

        entryRepository.save(WalletEntry.of(wallet.getId(), command.paymentId(), EntryType.RESERVATION, command.amount()));
        log.info("walletId={} paymentId={} amount={} type=RESERVATION", wallet.getId(), command.paymentId(), command.amount());
    }


//    public void settle(SettlePaymentCommand command) {
//        if (entryRepository.existsByPaymentIdAndType(
//                command.paymentId(), EntryType.SETTLEMENT)
//                || entryRepository.existsByPaymentIdAndType(
//                command.paymentId(), EntryType.CREDIT)) {
//            log.warn("paymentId={} already has settlement entries; skipping",
//                    command.paymentId());
//            return;
//        }
//
//        var payer = walletRepository.findByUserIdWithLock(command.payerId())
//                .orElseThrow(() -> new WalletNotFoundException(command.payerId()));
//
//        var payee = walletRepository.findByUserIdWithLock(command.payeeId())
//                .orElseThrow(() -> new WalletNotFoundException(command.payeeId()));
//
//        payer.settle(command.amount());
//        payee.credit(command.amount());
//
//        walletRepository.save(payer);
//        walletRepository.save(payee);
//
//        entryRepository.save(WalletEntry.of(
//                payer.getId(), command.paymentId(), EntryType.SETTLEMENT,
//                command.amount()));
//
//        entryRepository.save(WalletEntry.of(
//                payee.getId(), command.paymentId(), EntryType.CREDIT,
//                command.amount()));
//
//        log.info("paymentId={} settled: payer debited, payee credited",
//                command.paymentId());
//    }

    public void settle(SettlePaymentCommand command) {
        boolean settlementExists =
                entryRepository.existsByPaymentIdAndType(
                        command.paymentId(), EntryType.SETTLEMENT);

        boolean creditExists =
                entryRepository.existsByPaymentIdAndType(
                        command.paymentId(), EntryType.CREDIT);

        // A completed transfer must have both ledger entries.
        if (settlementExists && creditExists) {
            log.info("paymentId={} already settled; skipping",
                    command.paymentId());
            return;
        }

        // Never silently accept an incomplete transfer.
        if (settlementExists != creditExists) {
            throw new IllegalStateException(
                    "Inconsistent wallet settlement for payment "
                            + command.paymentId());
        }

        if (command.payerId().equals(command.payeeId())) {
            throw new IllegalArgumentException(
                    "Payer and payee must be different users");
        }

        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Settlement amount must be positive");
        }

        // Lock wallets in a consistent order to reduce deadlock risk.
        UUID firstId = command.payerId().compareTo(command.payeeId()) < 0
                ? command.payerId() : command.payeeId();

        UUID secondId = firstId.equals(command.payerId())
                ? command.payeeId() : command.payerId();

        var firstWallet = walletRepository.findByUserIdWithLock(firstId)
                .orElseThrow(() -> new WalletNotFoundException(firstId));

        var secondWallet = walletRepository.findByUserIdWithLock(secondId)
                .orElseThrow(() -> new WalletNotFoundException(secondId));

        var payer = firstId.equals(command.payerId())
                ? firstWallet : secondWallet;

        var payee = firstId.equals(command.payeeId())
                ? firstWallet : secondWallet;

        payer.settle(command.amount());
        payee.credit(command.amount());

        walletRepository.save(payer);
        walletRepository.save(payee);

        entryRepository.save(WalletEntry.of(
                payer.getId(),
                command.paymentId(),
                EntryType.SETTLEMENT,
                command.amount()));

        entryRepository.save(WalletEntry.of(
                payee.getId(),
                command.paymentId(),
                EntryType.CREDIT,
                command.amount()));

        log.info("paymentId={} settled; payer debited and payee credited",
                command.paymentId());
    }



    public void release(ReleaseReservationCommand command) {
        if (entryRepository.existsByPaymentIdAndType(command.paymentId(), EntryType.RELEASE)) {
            log.warn("paymentId={} RELEASE already exists — skipping (idempotent)", command.paymentId());
            return;
        }

        var wallet = walletRepository.findByUserIdWithLock(command.userId())
                .orElseThrow(() -> new WalletNotFoundException(command.userId()));

        wallet.release(command.amount());
        walletRepository.save(wallet);

        entryRepository.save(WalletEntry.of(wallet.getId(), command.paymentId(), EntryType.RELEASE, command.amount()));
        log.info("walletId={} paymentId={} amount={} type=RELEASE", wallet.getId(), command.paymentId(), command.amount());
    }
}
