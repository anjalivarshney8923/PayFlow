package com.payflow.service;

import com.payflow.dto.TransferRequest;
import com.payflow.dto.TransferResponse;
import com.payflow.entity.Transfer;
import com.payflow.entity.TransferStatus;
import com.payflow.entity.User;
import com.payflow.entity.Wallet;
import com.payflow.exception.BadRequestException;
import com.payflow.exception.InsufficientBalanceException;
import com.payflow.exception.ResourceNotFoundException;
import com.payflow.repository.TransferRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransferService transferService;

    @Test
    @DisplayName("Should successfully transfer money between wallets")
    void shouldTransferSuccessfully() {
        User sender = User.builder().id(1L).name("Sender").email("sender@example.com").build();
        User receiver = User.builder().id(2L).name("Receiver").email("receiver@example.com").build();

        Wallet senderWallet = Wallet.builder()
                .id(10L)
                .user(sender)
                .balance(new BigDecimal("10000.00"))
                .build();

        Wallet receiverWallet = Wallet.builder()
                .id(20L)
                .user(receiver)
                .balance(new BigDecimal("2000.00"))
                .build();

        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(walletRepository.findByUserIdWithLock(1L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdWithLock(2L)).thenReturn(Optional.of(receiverWallet));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer t = invocation.getArgument(0);
            t.setId(100L);
            t.setCreatedAt(LocalDateTime.now());
            return t;
        });

        TransferRequest request = new TransferRequest(1L, 2L, new BigDecimal("1000.00"));
        TransferResponse response = transferService.transfer(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.fromWalletId()).isEqualTo(10L);
        assertThat(response.toWalletId()).isEqualTo(20L);
        assertThat(response.amount()).isEqualByComparingTo("1000.00");
        assertThat(response.status()).isEqualTo(TransferStatus.SUCCESS);

        assertThat(senderWallet.getBalance()).isEqualByComparingTo("9000.00");
        assertThat(receiverWallet.getBalance()).isEqualByComparingTo("3000.00");

        verify(walletRepository).save(senderWallet);
        verify(walletRepository).save(receiverWallet);
        verify(transferRepository).save(any(Transfer.class));
    }

    @Test
    @DisplayName("Should throw BadRequestException when sender and receiver are the same user")
    void shouldThrowWhenSenderAndReceiverAreSame() {
        TransferRequest request = new TransferRequest(1L, 1L, new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Sender and receiver cannot be the same user");

        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when sender user does not exist")
    void shouldThrowWhenSenderDoesNotExist() {
        when(userRepository.existsById(1L)).thenReturn(false);

        TransferRequest request = new TransferRequest(1L, 2L, new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Sender user not found");

        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when receiver user does not exist")
    void shouldThrowWhenReceiverDoesNotExist() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(false);

        TransferRequest request = new TransferRequest(1L, 2L, new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Receiver user not found");

        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when sender wallet does not exist")
    void shouldThrowWhenSenderWalletDoesNotExist() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(walletRepository.findByUserIdWithLock(1L)).thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(1L, 2L, new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Wallet not found for user id: 1");

        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when receiver wallet does not exist")
    void shouldThrowWhenReceiverWalletDoesNotExist() {
        User sender = User.builder().id(1L).build();
        Wallet senderWallet = Wallet.builder().id(10L).user(sender).balance(new BigDecimal("1000.00")).build();

        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(walletRepository.findByUserIdWithLock(1L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdWithLock(2L)).thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(1L, 2L, new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Wallet not found for user id: 2");

        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw InsufficientBalanceException when sender has insufficient funds")
    void shouldThrowWhenInsufficientBalance() {
        User sender = User.builder().id(1L).build();
        User receiver = User.builder().id(2L).build();
        Wallet senderWallet = Wallet.builder().id(10L).user(sender).balance(new BigDecimal("500.00")).build();
        Wallet receiverWallet = Wallet.builder().id(20L).user(receiver).balance(new BigDecimal("2000.00")).build();

        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(walletRepository.findByUserIdWithLock(1L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdWithLock(2L)).thenReturn(Optional.of(receiverWallet));

        TransferRequest request = new TransferRequest(1L, 2L, new BigDecimal("1000.00"));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient wallet balance");

        assertThat(senderWallet.getBalance()).isEqualByComparingTo("500.00");
        assertThat(receiverWallet.getBalance()).isEqualByComparingTo("2000.00");
        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should retrieve transfer by ID")
    void shouldGetTransferById() {
        Wallet fromWallet = Wallet.builder().id(10L).build();
        Wallet toWallet = Wallet.builder().id(20L).build();
        Transfer transfer = Transfer.builder()
                .id(100L)
                .fromWallet(fromWallet)
                .toWallet(toWallet)
                .amount(new BigDecimal("500.00"))
                .status(TransferStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        when(transferRepository.findById(100L)).thenReturn(Optional.of(transfer));

        TransferResponse response = transferService.getTransferById(100L);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.fromWalletId()).isEqualTo(10L);
        assertThat(response.toWalletId()).isEqualTo(20L);
        assertThat(response.amount()).isEqualByComparingTo("500.00");
        assertThat(response.status()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    @DisplayName("Should retrieve all transfers for a user")
    void shouldGetTransfersByUserId() {
        Wallet fromWallet = Wallet.builder().id(10L).build();
        Wallet toWallet = Wallet.builder().id(20L).build();
        Transfer transfer = Transfer.builder()
                .id(100L)
                .fromWallet(fromWallet)
                .toWallet(toWallet)
                .amount(new BigDecimal("500.00"))
                .status(TransferStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.existsById(1L)).thenReturn(true);
        when(transferRepository.findByUserId(1L)).thenReturn(List.of(transfer));

        List<TransferResponse> list = transferService.getTransfersByUserId(1L);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(100L);
    }
}
