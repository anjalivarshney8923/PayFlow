package com.payflow;

import com.payflow.dto.CreateUserRequest;
import com.payflow.dto.DepositRequest;
import com.payflow.dto.TransferRequest;
import com.payflow.dto.UserResponse;
import com.payflow.dto.WalletResponse;
import com.payflow.entity.TransferStatus;
import com.payflow.repository.TransferRepository;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import com.payflow.service.TransferService;
import com.payflow.service.UserService;
import com.payflow.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransferConcurrencyIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        transferRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Long createTestUserWithWallet(String name, String email, BigDecimal initialDeposit) {
        UserResponse user = userService.createUser(new CreateUserRequest(name, email));
        walletService.createWallet(user.id());
        if (initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
            walletService.deposit(user.id(), new DepositRequest(initialDeposit));
        }
        return user.id();
    }

    @Test
    @DisplayName("Concurrency Test 1 — Prevent Overspending: 100 concurrent transfers of ₹100 from ₹10,000 balance")
    void shouldHandle100ConcurrentTransfersWithoutOverspending() throws Exception {
        int numberOfThreads = 100;
        BigDecimal transferAmount = new BigDecimal("100.00");
        BigDecimal initialBalance = new BigDecimal("10000.00");

        Long senderId = createTestUserWithWallet("Sender 1", "sender1@example.com", initialBalance);
        Long receiverId = createTestUserWithWallet("Receiver 1", "receiver1@example.com", BigDecimal.ZERO);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to be ready
                    transferService.transfer(new TransferRequest(senderId, receiverId, transferAmount));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Fire all threads simultaneously
        startLatch.countDown();

        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("All concurrent transfer tasks should complete within timeout").isTrue();
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failureCount.get()).isEqualTo(0);

        WalletResponse senderWallet = walletService.getWalletByUserId(senderId);
        WalletResponse receiverWallet = walletService.getWalletByUserId(receiverId);

        assertThat(senderWallet.balance()).isEqualByComparingTo("0.00");
        assertThat(receiverWallet.balance()).isEqualByComparingTo("10000.00");
        assertThat(transferRepository.count()).isEqualTo(100);
    }

    @Test
    @DisplayName("Concurrency Test 2 — Overspending Must Fail Safely: 100 concurrent transfers of ₹200 from ₹10,000 balance")
    void shouldFailSafelyWhenOverspendingAttemptedConcurrently() throws Exception {
        int numberOfThreads = 100;
        BigDecimal transferAmount = new BigDecimal("200.00");
        BigDecimal initialBalance = new BigDecimal("10000.00");

        Long senderId = createTestUserWithWallet("Sender 2", "sender2@example.com", initialBalance);
        Long receiverId = createTestUserWithWallet("Receiver 2", "receiver2@example.com", BigDecimal.ZERO);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(new TransferRequest(senderId, receiverId, transferAmount));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();

        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("All concurrent transfer tasks should complete within timeout").isTrue();
        assertThat(successCount.get()).isEqualTo(50);
        assertThat(failureCount.get()).isEqualTo(50);

        WalletResponse senderWallet = walletService.getWalletByUserId(senderId);
        WalletResponse receiverWallet = walletService.getWalletByUserId(receiverId);

        // Core invariants: sender never negative, receiver receives exact successful funds, total money conserved
        assertThat(senderWallet.balance()).isEqualByComparingTo("0.00");
        assertThat(receiverWallet.balance()).isEqualByComparingTo("10000.00");
        assertThat(senderWallet.balance().add(receiverWallet.balance())).isEqualByComparingTo("10000.00");
        assertThat(transferRepository.count()).isEqualTo(50);
    }

    @Test
    @DisplayName("Concurrency Test 3 — Opposite-Direction Transfers: 100 concurrent transfers without deadlock")
    void shouldHandleConcurrentOppositeDirectionTransfersWithoutDeadlock() throws Exception {
        int operationsPerDirection = 50;
        int totalOperations = operationsPerDirection * 2;
        BigDecimal transferAmount = new BigDecimal("100.00");
        BigDecimal initialBalance = new BigDecimal("10000.00");

        Long userA = createTestUserWithWallet("User A", "userA_conc@example.com", initialBalance);
        Long userB = createTestUserWithWallet("User B", "userB_conc@example.com", initialBalance);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalOperations);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // 50 transfers from A -> B
        for (int i = 0; i < operationsPerDirection; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(new TransferRequest(userA, userB, transferAmount));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 50 transfers from B -> A
        for (int i = 0; i < operationsPerDirection; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(new TransferRequest(userB, userA, transferAmount));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();

        // 30 second timeout ensures test fails immediately if a deadlock occurs
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("Opposite-direction transfers must complete without deadlock").isTrue();
        assertThat(successCount.get()).isEqualTo(totalOperations);
        assertThat(failureCount.get()).isEqualTo(0);

        WalletResponse walletA = walletService.getWalletByUserId(userA);
        WalletResponse walletB = walletService.getWalletByUserId(userB);

        // Conservation of total money
        BigDecimal totalBalance = walletA.balance().add(walletB.balance());
        assertThat(totalBalance).isEqualByComparingTo("20000.00");

        // Neither wallet became negative
        assertThat(walletA.balance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(walletB.balance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Since 50 * 100 went each way, balances should balance out to 10000.00 each
        assertThat(walletA.balance()).isEqualByComparingTo("10000.00");
        assertThat(walletB.balance()).isEqualByComparingTo("10000.00");
        assertThat(transferRepository.count()).isEqualTo(100);
    }
}
