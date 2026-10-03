package com.payflow.dto;

import com.payflow.entity.Transfer;
import com.payflow.entity.TransferStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse(
        Long id,
        Long fromWalletId,
        Long toWalletId,
        BigDecimal amount,
        TransferStatus status,
        LocalDateTime createdAt
) {
    public static TransferResponse fromEntity(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromWallet() != null ? transfer.getFromWallet().getId() : null,
                transfer.getToWallet() != null ? transfer.getToWallet().getId() : null,
                transfer.getAmount(),
                transfer.getStatus(),
                transfer.getCreatedAt()
        );
    }
}
