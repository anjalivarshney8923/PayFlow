package com.payflow.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DepositRequest(
        @NotNull(message = "Amount must not be null")
        @DecimalMin(value = "0.01", message = "Deposit amount must be greater than 0")
        @Digits(integer = 15, fraction = 2, message = "Amount must have at most 2 decimal places")
        @DecimalMax(value = "1000000000.00", message = "Deposit amount exceeds maximum allowable limit")
        BigDecimal amount
) {
}
