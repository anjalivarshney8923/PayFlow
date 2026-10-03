package com.payflow.service;

import com.payflow.dto.DepositRequest;
import com.payflow.dto.WalletResponse;
import com.payflow.entity.User;
import com.payflow.entity.Wallet;
import com.payflow.exception.DuplicateResourceException;
import com.payflow.exception.ResourceNotFoundException;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    @Transactional
    public WalletResponse createWallet(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (walletRepository.existsByUserId(userId)) {
            throw new DuplicateResourceException("Wallet already exists for user id: " + userId);
        }

        Wallet wallet = Wallet.builder()
                .user(user)
                .balance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();

        Wallet savedWallet = walletRepository.save(wallet);
        return WalletResponse.fromEntity(savedWallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletByUserId(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user id: " + userId));

        return WalletResponse.fromEntity(wallet);
    }

    @Transactional
    public WalletResponse deposit(Long userId, DepositRequest request) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user id: " + userId));

        BigDecimal depositAmount = request.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal newBalance = wallet.getBalance().add(depositAmount);
        wallet.setBalance(newBalance);

        Wallet savedWallet = walletRepository.save(wallet);
        return WalletResponse.fromEntity(savedWallet);
    }
}
