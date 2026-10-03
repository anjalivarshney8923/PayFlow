package com.payflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.CreateUserRequest;
import com.payflow.dto.DepositRequest;
import com.payflow.dto.TransferRequest;
import com.payflow.repository.TransferRepository;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransferIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private Long createUser(String name, String email) throws Exception {
        CreateUserRequest request = new CreateUserRequest(name, email);
        String response = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private Long createWallet(Long userId) throws Exception {
        String response = mockMvc.perform(post("/api/wallets/{userId}", userId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void deposit(Long userId, BigDecimal amount) throws Exception {
        DepositRequest request = new DepositRequest(amount);
        mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Test 1 — Successful transfer (A: 10000 -> 9000, B: 2000 -> 3000)")
    void shouldTransferSuccessfully() throws Exception {
        Long userA = createUser("User A", "userA@example.com");
        Long userB = createUser("User B", "userB@example.com");
        createWallet(userA);
        createWallet(userB);

        deposit(userA, new BigDecimal("10000.00"));
        deposit(userB, new BigDecimal("2000.00"));

        TransferRequest transferRequest = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.amount", is(1000.00)))
                .andExpect(jsonPath("$.status", is("SUCCESS")));

        // Verify Wallet A balance is 9000.00
        mockMvc.perform(get("/api/wallets/{userId}", userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(9000.00)));

        // Verify Wallet B balance is 3000.00
        mockMvc.perform(get("/api/wallets/{userId}", userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(3000.00)));
    }

    @Test
    @DisplayName("Test 2 — Transfer record is created and stored with SUCCESS status")
    void shouldStoreTransferRecordWithSuccess() throws Exception {
        Long userA = createUser("Sender", "sender@example.com");
        Long userB = createUser("Receiver", "receiver@example.com");
        Long walletA = createWallet(userA);
        Long walletB = createWallet(userB);

        deposit(userA, new BigDecimal("5000.00"));

        TransferRequest transferRequest = new TransferRequest(userA, userB, new BigDecimal("1500.00"));

        String response = mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long transferId = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(get("/api/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(transferId.intValue())))
                .andExpect(jsonPath("$.fromWalletId", is(walletA.intValue())))
                .andExpect(jsonPath("$.toWalletId", is(walletB.intValue())))
                .andExpect(jsonPath("$.amount", is(1500.00)))
                .andExpect(jsonPath("$.status", is("SUCCESS")))
                .andExpect(jsonPath("$.createdAt", notNullValue()));
    }

    @Test
    @DisplayName("Test 3 — Insufficient balance rejects transfer and leaves balance unchanged")
    void shouldRejectTransferWhenInsufficientBalance() throws Exception {
        Long userA = createUser("Poor User", "poor@example.com");
        Long userB = createUser("Rich User", "rich@example.com");
        createWallet(userA);
        createWallet(userB);

        deposit(userA, new BigDecimal("500.00"));

        TransferRequest transferRequest = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("Insufficient wallet balance")));

        // Verify balance remained 500.00
        mockMvc.perform(get("/api/wallets/{userId}", userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(500.00)));
    }

    @Test
    @DisplayName("Test 4 — Sender does not exist returns 404")
    void shouldReturn404WhenSenderDoesNotExist() throws Exception {
        Long userB = createUser("Valid Receiver", "valrec@example.com");
        createWallet(userB);

        TransferRequest transferRequest = new TransferRequest(99999L, userB, new BigDecimal("100.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("Test 5 — Receiver does not exist returns 404")
    void shouldReturn404WhenReceiverDoesNotExist() throws Exception {
        Long userA = createUser("Valid Sender", "valsend@example.com");
        createWallet(userA);
        deposit(userA, new BigDecimal("500.00"));

        TransferRequest transferRequest = new TransferRequest(userA, 88888L, new BigDecimal("100.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("Test 6 — Sender wallet does not exist returns 404")
    void shouldReturn404WhenSenderWalletDoesNotExist() throws Exception {
        Long userA = createUser("No Wallet Sender", "nosendw@example.com");
        Long userB = createUser("Has Wallet Receiver", "hasrecw@example.com");
        createWallet(userB);

        TransferRequest transferRequest = new TransferRequest(userA, userB, new BigDecimal("100.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("Test 7 — Receiver wallet does not exist returns 404")
    void shouldReturn404WhenReceiverWalletDoesNotExist() throws Exception {
        Long userA = createUser("Has Wallet Sender", "hassendw@example.com");
        Long userB = createUser("No Wallet Receiver", "norecw@example.com");
        createWallet(userA);
        deposit(userA, new BigDecimal("500.00"));

        TransferRequest transferRequest = new TransferRequest(userA, userB, new BigDecimal("100.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("Test 8 — Same sender and receiver returns 400 Bad Request")
    void shouldRejectTransferToSelf() throws Exception {
        Long userA = createUser("Self User", "self@example.com");
        createWallet(userA);
        deposit(userA, new BigDecimal("500.00"));

        TransferRequest transferRequest = new TransferRequest(userA, userA, new BigDecimal("100.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("Sender and receiver cannot be the same user")));
    }

    @Test
    @DisplayName("Test 9 — Zero amount returns 400 Bad Request")
    void shouldRejectZeroAmountTransfer() throws Exception {
        Long userA = createUser("User 9A", "u9a@example.com");
        Long userB = createUser("User 9B", "u9b@example.com");
        createWallet(userA);
        createWallet(userB);

        String json = "{\"fromUserId\": " + userA + ", \"toUserId\": " + userB + ", \"amount\": 0.00}";

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("Test 10 — Negative amount returns 400 Bad Request")
    void shouldRejectNegativeAmountTransfer() throws Exception {
        Long userA = createUser("User 10A", "u10a@example.com");
        Long userB = createUser("User 10B", "u10b@example.com");
        createWallet(userA);
        createWallet(userB);

        String json = "{\"fromUserId\": " + userA + ", \"toUserId\": " + userB + ", \"amount\": -100.00}";

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("Test 11 — Transfer persistence end-to-end flow")
    void shouldPersistTransferAndBalances() throws Exception {
        Long userA = createUser("Alice", "alice@example.com");
        Long userB = createUser("Bob", "bob@example.com");
        createWallet(userA);
        createWallet(userB);

        deposit(userA, new BigDecimal("10000.00"));

        TransferRequest transferRequest = new TransferRequest(userA, userB, new BigDecimal("1000.00"));

        String response = mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long transferId = objectMapper.readTree(response).get("id").asLong();

        // Check GET transfer
        mockMvc.perform(get("/api/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(1000.00)))
                .andExpect(jsonPath("$.status", is("SUCCESS")));

        // Check Wallet A
        mockMvc.perform(get("/api/wallets/{userId}", userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(9000.00)));

        // Check Wallet B
        mockMvc.perform(get("/api/wallets/{userId}", userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(1000.00)));
    }

    @Test
    @DisplayName("Test 12 — User transfer history returns transfers where user is sender or receiver")
    void shouldGetUserTransferHistory() throws Exception {
        Long userA = createUser("History A", "hista@example.com");
        Long userB = createUser("History B", "histb@example.com");
        Long userC = createUser("History C", "histc@example.com");
        createWallet(userA);
        createWallet(userB);
        createWallet(userC);

        deposit(userA, new BigDecimal("10000.00"));
        deposit(userB, new BigDecimal("5000.00"));

        // A -> B
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(userA, userB, new BigDecimal("1000.00")))))
                .andExpect(status().isCreated());

        // B -> A
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(userB, userA, new BigDecimal("500.00")))))
                .andExpect(status().isCreated());

        // C -> B
        deposit(userC, new BigDecimal("2000.00"));
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(userC, userB, new BigDecimal("200.00")))))
                .andExpect(status().isCreated());

        // User A history should have 2 transfers (one as sender, one as receiver)
        mockMvc.perform(get("/api/users/{userId}/transfers", userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // User B history should have 3 transfers (A->B, B->A, C->B)
        mockMvc.perform(get("/api/users/{userId}/transfers", userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        // User C history should have 1 transfer (C->B)
        mockMvc.perform(get("/api/users/{userId}/transfers", userC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Test 13 — Rollback test: failed transfer leaves sender and receiver balances intact")
    void shouldNotChangeBalancesWhenTransferFails() throws Exception {
        Long userA = createUser("Rollback A", "rba@example.com");
        Long userB = createUser("Rollback B", "rbb@example.com");
        createWallet(userA);
        createWallet(userB);

        deposit(userA, new BigDecimal("1000.00"));
        deposit(userB, new BigDecimal("500.00"));

        // Try to transfer 2000.00 (more than balance 1000.00)
        TransferRequest request = new TransferRequest(userA, userB, new BigDecimal("2000.00"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // Verify Wallet A is untouched
        mockMvc.perform(get("/api/wallets/{userId}", userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(1000.00)));

        // Verify Wallet B is untouched
        mockMvc.perform(get("/api/wallets/{userId}", userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(500.00)));
    }
}
