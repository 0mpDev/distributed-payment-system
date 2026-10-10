
package com.finflow.wallet.application.command;

import java.math.BigDecimal;
import java.util.UUID;

public record SettlePaymentCommand(
        UUID paymentId,
        UUID payerId,
        UUID payeeId,
        BigDecimal amount
) {
}
