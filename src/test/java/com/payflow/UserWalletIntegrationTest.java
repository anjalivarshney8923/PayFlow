package com.payflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.CreateUserRequest;
import com.payflow.repository.UserRepository;
import com.payflow.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserWalletIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() {
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Nested
    @DisplayName("User API Tests")
    class UserTests {

        @Test
        @DisplayName("1. Create user successfully")
        void shouldCreateUserSuccessfully() throws Exception {
            CreateUserRequest request = new CreateUserRequest("Anjali Varshney", "anjali@example.com");

            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", notNullValue()))
                    .andExpect(jsonPath("$.name", is("Anjali Varshney")))
                    .andExpect(jsonPath("$.email", is("anjali@example.com")))
                    .andExpect(jsonPath("$.createdAt", notNullValue()));
        }

        @Test
        @DisplayName("2. Get existing user")
        void shouldGetExistingUser() throws Exception {
            CreateUserRequest request = new CreateUserRequest("Saurabh", "saurabh@example.com");
            String responseString = mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            Long userId = objectMapper.readTree(responseString).get("id").asLong();

            mockMvc.perform(get("/api/users/{id}", userId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(userId.intValue())))
                    .andExpect(jsonPath("$.name", is("Saurabh")))
                    .andExpect(jsonPath("$.email", is("saurabh@example.com")))
                    .andExpect(jsonPath("$.createdAt", notNullValue()));
        }

        @Test
        @DisplayName("3. Get nonexistent user returns 404")
        void shouldReturn404WhenUserNotFound() throws Exception {
            mockMvc.perform(get("/api/users/{id}", 99999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("4. Invalid user data is rejected (blank name & invalid email)")
        void shouldRejectInvalidUserData() throws Exception {
            CreateUserRequest invalidRequest = new CreateUserRequest("", "not-an-email");

            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("5. Duplicate email is rejected with 409")
        void shouldRejectDuplicateEmail() throws Exception {
            CreateUserRequest firstUser = new CreateUserRequest("User One", "duplicate@example.com");
            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(firstUser)))
                    .andExpect(status().isCreated());

            CreateUserRequest duplicateUser = new CreateUserRequest("User Two", "duplicate@example.com");
            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(duplicateUser)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }
    }

    @Nested
    @DisplayName("Wallet API Tests")
    class WalletTests {

        private Long createTestUser(String name, String email) throws Exception {
            CreateUserRequest request = new CreateUserRequest(name, email);
            String responseString = mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(responseString).get("id").asLong();
        }

        @Test
        @DisplayName("1. Create wallet successfully and 2. verify new wallet starts with 0.00")
        void shouldCreateWalletWithZeroBalance() throws Exception {
            Long userId = createTestUser("Wallet Owner", "walletowner@example.com");

            mockMvc.perform(post("/api/wallets/{userId}", userId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", notNullValue()))
                    .andExpect(jsonPath("$.userId", is(userId.intValue())))
                    .andExpect(jsonPath("$.balance", is(0.00)))
                    .andExpect(jsonPath("$.createdAt", notNullValue()))
                    .andExpect(jsonPath("$.updatedAt", notNullValue()));
        }

        @Test
        @DisplayName("3. Get wallet successfully")
        void shouldGetWalletSuccessfully() throws Exception {
            Long userId = createTestUser("John Doe", "john@example.com");

            mockMvc.perform(post("/api/wallets/{userId}", userId))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/wallets/{userId}", userId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", notNullValue()))
                    .andExpect(jsonPath("$.userId", is(userId.intValue())))
                    .andExpect(jsonPath("$.balance", is(0.00)))
                    .andExpect(jsonPath("$.createdAt", notNullValue()))
                    .andExpect(jsonPath("$.updatedAt", notNullValue()));
        }

        @Test
        @DisplayName("4. Creating a second wallet for the same user is rejected with 409")
        void shouldRejectDuplicateWalletCreation() throws Exception {
            Long userId = createTestUser("Single Wallet User", "single@example.com");

            mockMvc.perform(post("/api/wallets/{userId}", userId))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/wallets/{userId}", userId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("5. Creating a wallet for nonexistent user returns 404")
        void shouldReturn404WhenCreatingWalletForNonexistentUser() throws Exception {
            mockMvc.perform(post("/api/wallets/{userId}", 88888L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("6. Getting wallet for nonexistent user returns 404")
        void shouldReturn404WhenGettingWalletForNonexistentUser() throws Exception {
            mockMvc.perform(get("/api/wallets/{userId}", 77777L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }
    }

    @Nested
    @DisplayName("Wallet Deposit API Tests")
    class DepositTests {

        private Long createTestUser(String name, String email) throws Exception {
            CreateUserRequest request = new CreateUserRequest(name, email);
            String responseString = mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(responseString).get("id").asLong();
        }

        private void createTestWallet(Long userId) throws Exception {
            mockMvc.perform(post("/api/wallets/{userId}", userId))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Test 1 — Successful deposit: 0.00 -> 1000.00")
        void shouldDepositSuccessfully() throws Exception {
            Long userId = createTestUser("Deposit User 1", "dep1@example.com");
            createTestWallet(userId);

            com.payflow.dto.DepositRequest depositRequest = new com.payflow.dto.DepositRequest(new java.math.BigDecimal("1000.00"));

            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(depositRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId", is(userId.intValue())))
                    .andExpect(jsonPath("$.balance", is(1000.00)));
        }

        @Test
        @DisplayName("Test 2 — Multiple deposits: 0.00 -> 1000.00 -> 500.00 -> 1500.00")
        void shouldAccumulateMultipleDeposits() throws Exception {
            Long userId = createTestUser("Deposit User 2", "dep2@example.com");
            createTestWallet(userId);

            // First deposit 1000.00
            com.payflow.dto.DepositRequest deposit1 = new com.payflow.dto.DepositRequest(new java.math.BigDecimal("1000.00"));
            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(deposit1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance", is(1000.00)));

            // Second deposit 500.00
            com.payflow.dto.DepositRequest deposit2 = new com.payflow.dto.DepositRequest(new java.math.BigDecimal("500.00"));
            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(deposit2)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance", is(1500.00)));
        }

        @Test
        @DisplayName("Test 3 — Decimal amount deposit: 1000.50")
        void shouldDepositDecimalAmount() throws Exception {
            Long userId = createTestUser("Deposit User 3", "dep3@example.com");
            createTestWallet(userId);

            com.payflow.dto.DepositRequest depositRequest = new com.payflow.dto.DepositRequest(new java.math.BigDecimal("1000.50"));

            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(depositRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance", is(1000.50)));
        }

        @Test
        @DisplayName("Test 4 — Zero amount deposit returns 400 Bad Request")
        void shouldRejectZeroAmountDeposit() throws Exception {
            Long userId = createTestUser("Deposit User 4", "dep4@example.com");
            createTestWallet(userId);

            String requestJson = "{\"amount\": 0.00}";

            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("Test 5 — Negative amount deposit returns 400 Bad Request")
        void shouldRejectNegativeAmountDeposit() throws Exception {
            Long userId = createTestUser("Deposit User 5", "dep5@example.com");
            createTestWallet(userId);

            String requestJson = "{\"amount\": -100.00}";

            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("Test 6 — Nonexistent user deposit returns 404 Not Found")
        void shouldReturn404WhenDepositingForNonexistentUser() throws Exception {
            com.payflow.dto.DepositRequest depositRequest = new com.payflow.dto.DepositRequest(new java.math.BigDecimal("500.00"));

            mockMvc.perform(post("/api/wallets/{userId}/deposit", 99999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(depositRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("Test 7 — Nonexistent wallet deposit returns 404 Not Found")
        void shouldReturn404WhenDepositingForUserWithoutWallet() throws Exception {
            Long userId = createTestUser("User Without Wallet", "nowallet@example.com");

            com.payflow.dto.DepositRequest depositRequest = new com.payflow.dto.DepositRequest(new java.math.BigDecimal("500.00"));

            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(depositRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("Test 8 — Database persistence: retrieve wallet via GET and verify updated balance")
        void shouldPersistDepositedBalanceInDatabase() throws Exception {
            Long userId = createTestUser("Persistence User", "persist@example.com");
            createTestWallet(userId);

            com.payflow.dto.DepositRequest depositRequest = new com.payflow.dto.DepositRequest(new java.math.BigDecimal("750.25"));

            mockMvc.perform(post("/api/wallets/{userId}/deposit", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(depositRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance", is(750.25)));

            // Verify with GET endpoint
            mockMvc.perform(get("/api/wallets/{userId}", userId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId", is(userId.intValue())))
                    .andExpect(jsonPath("$.balance", is(750.25)));
        }
    }
}
