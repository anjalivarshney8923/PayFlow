package com.payflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.CreateUserRequest;
import com.payflow.dto.DepositRequest;
import com.payflow.dto.TransferRequest;
import com.payflow.dto.UserResponse;
import com.payflow.dto.WalletResponse;
import com.payflow.repository.IdempotencyKeyRepository;
import com.payflow.repository.TransferRepository;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import com.payflow.service.UserService;
import com.payflow.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
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
    @DisplayName("TEST 1 — First request with Idempotency-Key succeeds and creates idempotency record")
    void shouldExecuteFirstRequestSuccessfully() throws Exception {
        Long userA = createTestUserWithWallet("User A", "usera1@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb1@example.com", BigDecimal.ZERO);

        TransferRequest request = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.amount", is(1000.00)))
                .andExpect(jsonPath("$.status", is("SUCCESS")));

        WalletResponse walletA = walletService.getWalletByUserId(userA);
        WalletResponse walletB = walletService.getWalletByUserId(userB);

        assertThat(walletA.balance()).isEqualByComparingTo("9000.00");
        assertThat(walletB.balance()).isEqualByComparingTo("1000.00");
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("TEST 2 — Same key, same request returns cached transfer without duplicate execution")
    void shouldReturnCachedResultForDuplicateRequestWithSameKey() throws Exception {
        Long userA = createTestUserWithWallet("User A", "usera2@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb2@example.com", BigDecimal.ZERO);

        TransferRequest request = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        // First attempt
        String firstResponseStr = mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long firstTransferId = objectMapper.readTree(firstResponseStr).get("id").asLong();

        // Second attempt with exact same key and payload
        String secondResponseStr = mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long secondTransferId = objectMapper.readTree(secondResponseStr).get("id").asLong();

        assertThat(secondTransferId).isEqualTo(firstTransferId);

        // Balances debited/credited only ONCE
        WalletResponse walletA = walletService.getWalletByUserId(userA);
        WalletResponse walletB = walletService.getWalletByUserId(userB);

        assertThat(walletA.balance()).isEqualByComparingTo("9000.00");
        assertThat(walletB.balance()).isEqualByComparingTo("1000.00");
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("TEST 3 — Same key, different amount returns 409 Conflict")
    void shouldReturn409WhenSameKeyUsedWithDifferentAmount() throws Exception {
        Long userA = createTestUserWithWallet("User A", "usera3@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb3@example.com", BigDecimal.ZERO);

        TransferRequest firstRequest = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isCreated());

        TransferRequest differentAmountRequest = new TransferRequest(userA, userB, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(differentAmountRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", is("Idempotency-Key has already been used with a different request")));

        // Balances remain intact from first transfer only
        WalletResponse walletA = walletService.getWalletByUserId(userA);
        WalletResponse walletB = walletService.getWalletByUserId(userB);

        assertThat(walletA.balance()).isEqualByComparingTo("9000.00");
        assertThat(walletB.balance()).isEqualByComparingTo("1000.00");
        assertThat(transferRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("TEST 4 — Same key, different sender/receiver returns 409 Conflict")
    void shouldReturn409WhenSameKeyUsedWithDifferentParties() throws Exception {
        Long userA = createTestUserWithWallet("User A", "usera4@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb4@example.com", new BigDecimal("5000.00"));

        TransferRequest firstRequest = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isCreated());

        // Attempt reverse direction with the exact same key
        TransferRequest reverseRequest = new TransferRequest(userB, userA, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reverseRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", is("Idempotency-Key has already been used with a different request")));

        WalletResponse walletA = walletService.getWalletByUserId(userA);
        WalletResponse walletB = walletService.getWalletByUserId(userB);

        assertThat(walletA.balance()).isEqualByComparingTo("9000.00");
        assertThat(walletB.balance()).isEqualByComparingTo("6000.00");
        assertThat(transferRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("TEST 5 — Missing Idempotency-Key returns 400 Bad Request")
    void shouldReturn400WhenIdempotencyKeyMissing() throws Exception {
        Long userA = createTestUserWithWallet("User A", "usera5@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb5@example.com", BigDecimal.ZERO);

        TransferRequest request = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("Idempotency-Key header is required and cannot be blank")));

        assertThat(walletService.getWalletByUserId(userA).balance()).isEqualByComparingTo("10000.00");
        assertThat(transferRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("TEST 6 — Blank Idempotency-Key returns 400 Bad Request")
    void shouldReturn400WhenIdempotencyKeyBlank() throws Exception {
        Long userA = createTestUserWithWallet("User A", "usera6@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb6@example.com", BigDecimal.ZERO);

        TransferRequest request = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("Idempotency-Key header is required and cannot be blank")));

        assertThat(walletService.getWalletByUserId(userA).balance()).isEqualByComparingTo("10000.00");
        assertThat(transferRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("TEST 7 — Concurrent SAME KEY: 100 concurrent requests create exactly ONE transfer")
    void shouldHandle100ConcurrentSameKeyRequestsSafely() throws Exception {
        int numberOfThreads = 100;
        Long userA = createTestUserWithWallet("User A", "usera7@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb7@example.com", BigDecimal.ZERO);

        TransferRequest request = new TransferRequest(userA, userB, new BigDecimal("100.00"));
        String requestJson = objectMapper.writeValueAsString(request);
        String idempotencyKey = "concurrent-123";

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Long> returnedTransferIds = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/api/transfers")
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestJson))
                            .andReturn();

                    if (result.getResponse().getStatus() == 201 || result.getResponse().getStatus() == 200) {
                        Long transferId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
                        returnedTransferIds.add(transferId);
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
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

        assertThat(completed).as("All concurrent tasks should complete within timeout").isTrue();
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failureCount.get()).isEqualTo(0);

        // Exactly ONE transfer entity and ONE idempotency key entity created in database
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);

        // All 100 requests returned the exact same transfer ID
        Long uniqueTransferId = returnedTransferIds.get(0);
        for (Long id : returnedTransferIds) {
            assertThat(id).isEqualTo(uniqueTransferId);
        }

        // Exactly ONE debit of 100.00 and ONE credit of 100.00 occurred
        WalletResponse walletA = walletService.getWalletByUserId(userA);
        WalletResponse walletB = walletService.getWalletByUserId(userB);

        assertThat(walletA.balance()).isEqualByComparingTo("9900.00");
        assertThat(walletB.balance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("TEST 8 — Concurrent DIFFERENT KEYS: 100 concurrent requests create 100 distinct transfers")
    void shouldHandle100ConcurrentDifferentKeyRequestsSuccessfully() throws Exception {
        int numberOfThreads = 100;
        Long userA = createTestUserWithWallet("User A", "usera8@example.com", new BigDecimal("10000.00"));
        Long userB = createTestUserWithWallet("User B", "userb8@example.com", BigDecimal.ZERO);

        TransferRequest request = new TransferRequest(userA, userB, new BigDecimal("100.00"));
        String requestJson = objectMapper.writeValueAsString(request);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/api/transfers")
                                    .header("Idempotency-Key", "key-unique-" + index)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestJson))
                            .andReturn();

                    if (result.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
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

        assertThat(completed).as("All concurrent tasks should complete within timeout").isTrue();
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failureCount.get()).isEqualTo(0);

        assertThat(transferRepository.count()).isEqualTo(100);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(100);

        WalletResponse walletA = walletService.getWalletByUserId(userA);
        WalletResponse walletB = walletService.getWalletByUserId(userB);

        assertThat(walletA.balance()).isEqualByComparingTo("0.00");
        assertThat(walletB.balance()).isEqualByComparingTo("10000.00");
    }
}
