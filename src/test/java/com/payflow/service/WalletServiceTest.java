package com.payflow.service;

import com.payflow.dto.WalletResponse;
import com.payflow.entity.User;
import com.payflow.entity.Wallet;
import com.payflow.exception.DuplicateResourceException;
import com.payflow.exception.ResourceNotFoundException;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WalletService walletService;

    @Test
    @DisplayName("Should create wallet starting with balance 0.00 for existing user")
    void shouldCreateWalletSuccessfully() {
        User user = User.builder().id(1L).name("Bob").email("bob@example.com").build();
        Wallet savedWallet = Wallet.builder()
                .id(10L)
                .user(user)
                .balance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(walletRepository.existsByUserId(1L)).thenReturn(false);
        when(walletRepository.save(any(Wallet.class))).thenReturn(savedWallet);

        WalletResponse response = walletService.createWallet(1L);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.balance()).isEqualByComparingTo("0.00");
        verify(walletRepository).save(any(Wallet.class));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when creating wallet for nonexistent user")
    void shouldThrowWhenCreatingWalletForNonexistentUser() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.createWallet(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with id: 99");

        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw DuplicateResourceException when creating a second wallet for same user")
    void shouldThrowWhenUserAlreadyHasWallet() {
        User user = User.builder().id(1L).name("Bob").email("bob@example.com").build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(walletRepository.existsByUserId(1L)).thenReturn(true);

        assertThatThrownBy(() -> walletService.createWallet(1L))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Wallet already exists for user id: 1");

        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should get wallet by user id successfully")
    void shouldGetWalletByUserId() {
        User user = User.builder().id(1L).name("Bob").email("bob@example.com").build();
        Wallet wallet = Wallet.builder()
                .id(10L)
                .user(user)
                .balance(new BigDecimal("0.00"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(userRepository.existsById(1L)).thenReturn(true);
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.getWalletByUserId(1L);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when getting wallet for nonexistent user")
    void shouldThrowWhenGettingWalletForNonexistentUser() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> walletService.getWalletByUserId(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with id: 99");
    }

    @Test
    @DisplayName("Should successfully deposit money into wallet and update balance")
    void shouldDepositMoneySuccessfully() {
        User user = User.builder().id(1L).name("Bob").email("bob@example.com").build();
        Wallet wallet = Wallet.builder()
                .id(10L)
                .user(user)
                .balance(new BigDecimal("1000.00"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(userRepository.existsById(1L)).thenReturn(true);
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.payflow.dto.DepositRequest request = new com.payflow.dto.DepositRequest(new BigDecimal("2500.00"));
        WalletResponse response = walletService.deposit(1L, request);

        assertThat(response).isNotNull();
        assertThat(response.balance()).isEqualByComparingTo("3500.00");
        verify(walletRepository).save(wallet);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when depositing into nonexistent user")
    void shouldThrowWhenDepositingForNonexistentUser() {
        when(userRepository.existsById(99L)).thenReturn(false);

        com.payflow.dto.DepositRequest request = new com.payflow.dto.DepositRequest(new BigDecimal("100.00"));
        assertThatThrownBy(() -> walletService.deposit(99L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with id: 99");

        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when depositing into user without wallet")
    void shouldThrowWhenDepositingForUserWithoutWallet() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.empty());

        com.payflow.dto.DepositRequest request = new com.payflow.dto.DepositRequest(new BigDecimal("100.00"));
        assertThatThrownBy(() -> walletService.deposit(1L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Wallet not found for user id: 1");

        verify(walletRepository, never()).save(any());
    }
}
