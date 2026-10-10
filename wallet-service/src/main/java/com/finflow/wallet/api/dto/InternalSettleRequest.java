
package com.finflow.wallet.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record InternalSettleRequest(
        @NotNull UUID paymentId,
        @NotNull UUID payerId,
        @NotNull UUID payeeId,
        @NotNull BigDecimal amount
) {}
