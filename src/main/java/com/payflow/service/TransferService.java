package com.payflow.service;

import com.payflow.dto.TransferRequest;
import com.payflow.dto.TransferResponse;
import com.payflow.entity.IdempotencyKey;
import com.payflow.entity.Transfer;
import com.payflow.entity.TransferStatus;
import com.payflow.entity.Wallet;
import com.payflow.exception.BadRequestException;
import com.payflow.exception.DuplicateResourceException;
import com.payflow.exception.InsufficientBalanceException;
import com.payflow.exception.ResourceNotFoundException;
import com.payflow.repository.IdempotencyKeyRepository;
import com.payflow.repository.TransferRepository;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private TransferService self;

    public TransferService(TransferRepository transferRepository,
                           WalletRepository walletRepository,
                           UserRepository userRepository,
                           IdempotencyKeyRepository idempotencyKeyRepository) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    public TransferResponse transfer(String idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new BadRequestException("Idempotency-Key header is required and cannot be blank");
        }

        String trimmedKey = idempotencyKey.trim();
        String fingerprint = calculateFingerprint(request);

        // Fast path: check if idempotency key was already recorded
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKey(trimmedKey);
        if (existing.isPresent()) {
            return handleExistingKey(existing.get(), fingerprint);
        }

        // Execute transfer inside transactional boundary
        try {
            TransferService service = self != null ? self : this;
            return service.executeTransferWithIdempotency(trimmedKey, fingerprint, request);
        } catch (DataIntegrityViolationException e) {
            // Concurrent race: another transaction committed the same key simultaneously.
            // Retrieve the winner's committed idempotency record.
            for (int i = 0; i < 20; i++) {
                Optional<IdempotencyKey> keyRecord = idempotencyKeyRepository.findByKey(trimmedKey);
                if (keyRecord.isPresent()) {
                    return handleExistingKey(keyRecord.get(), fingerprint);
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw e;
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public TransferResponse executeTransferWithIdempotency(String idempotencyKey, String fingerprint, TransferRequest request) {
        // Re-check key inside transaction
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleExistingKey(existing.get(), fingerprint);
        }

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

        // Re-check idempotency key after acquiring locks in case another transaction just completed with this key
        Optional<IdempotencyKey> existingAfterLock = idempotencyKeyRepository.findByKey(idempotencyKey);
        if (existingAfterLock.isPresent()) {
            return handleExistingKey(existingAfterLock.get(), fingerprint);
        }

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

        IdempotencyKey record = IdempotencyKey.builder()
                .key(idempotencyKey)
                .requestFingerprint(fingerprint)
                .transfer(savedTransfer)
                .build();

        idempotencyKeyRepository.save(record);

        return TransferResponse.fromEntity(savedTransfer);
    }

    private TransferResponse handleExistingKey(IdempotencyKey existingKey, String currentFingerprint) {
        if (!existingKey.getRequestFingerprint().equals(currentFingerprint)) {
            throw new DuplicateResourceException("Idempotency-Key has already been used with a different request");
        }
        return TransferResponse.fromEntity(existingKey.getTransfer());
    }

    public static String calculateFingerprint(TransferRequest request) {
        BigDecimal normalizedAmount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String raw = request.fromUserId() + "|" + request.toUserId() + "|" + normalizedAmount.toPlainString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
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
