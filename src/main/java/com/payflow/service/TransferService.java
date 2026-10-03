package com.payflow.service;

import com.payflow.dto.TransferRequest;
import com.payflow.dto.TransferResponse;
import com.payflow.entity.Transfer;
import com.payflow.entity.TransferStatus;
import com.payflow.entity.Wallet;
import com.payflow.exception.BadRequestException;
import com.payflow.exception.InsufficientBalanceException;
import com.payflow.exception.ResourceNotFoundException;
import com.payflow.repository.TransferRepository;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        if (request.fromUserId().equals(request.toUserId())) {
            throw new BadRequestException("Sender and receiver cannot be the same user");
        }

        if (!userRepository.existsById(request.fromUserId())) {
            throw new ResourceNotFoundException("Sender user not found with id: " + request.fromUserId());
        }

        if (!userRepository.existsById(request.toUserId())) {
            throw new ResourceNotFoundException("Receiver user not found with id: " + request.toUserId());
        }

        // To prevent deadlocks between concurrent transfers in opposite directions (e.g. A->B and B->A),
        // we acquire row-level locks in a deterministic order based on user ID (smaller ID first, larger ID second).
        // PESSIMISTIC_WRITE translates to a SQL 'SELECT ... FOR UPDATE', ensuring other concurrent transactions
        // wait until the current transaction commits or rolls back before reading/modifying these wallet rows.
        Long firstUserId = request.fromUserId() < request.toUserId() ? request.fromUserId() : request.toUserId();
        Long secondUserId = request.fromUserId() < request.toUserId() ? request.toUserId() : request.fromUserId();

        Wallet firstWallet = walletRepository.findByUserIdWithLock(firstUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user id: " + firstUserId));

        Wallet secondWallet = walletRepository.findByUserIdWithLock(secondUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user id: " + secondUserId));

        // Assign sender and receiver wallets correctly according to the original transfer direction
        Wallet senderWallet = request.fromUserId().equals(firstWallet.getUser().getId()) ? firstWallet : secondWallet;
        Wallet receiverWallet = request.toUserId().equals(firstWallet.getUser().getId()) ? firstWallet : secondWallet;

        BigDecimal transferAmount = request.amount().setScale(2, RoundingMode.HALF_UP);

        if (senderWallet.getBalance().compareTo(transferAmount) < 0) {
            throw new InsufficientBalanceException("Insufficient wallet balance");
        }

        senderWallet.setBalance(senderWallet.getBalance().subtract(transferAmount));
        receiverWallet.setBalance(receiverWallet.getBalance().add(transferAmount));

        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        Transfer transfer = Transfer.builder()
                .fromWallet(senderWallet)
                .toWallet(receiverWallet)
                .amount(transferAmount)
                .status(TransferStatus.SUCCESS)
                .build();

        Transfer savedTransfer = transferRepository.save(transfer);
        return TransferResponse.fromEntity(savedTransfer);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransferById(Long id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found with id: " + id));
        return TransferResponse.fromEntity(transfer);
    }

    @Transactional(readOnly = true)
    public List<TransferResponse> getTransfersByUserId(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }

        return transferRepository.findByUserId(userId)
                .stream()
                .map(TransferResponse::fromEntity)
                .toList();
    }
}
