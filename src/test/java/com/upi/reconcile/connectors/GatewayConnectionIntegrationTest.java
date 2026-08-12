package com.upi.reconcile.connectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.connectors.crypto.HmacSignatureVerifier;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.WebhookEventRepository;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the gateway connection lifecycle.
 *
 * <p>Tests the full connect → disconnect → webhook rejection flow, duplicate
 * ACTIVE connection conflict (409), and reconnect with webhook secret rotation.
 *
 * <p>Uses Testcontainers for Postgres, Kafka, and Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Testcontainers
class GatewayConnectionIntegrationTest {

    // ── Testcontainers ────────────────────────────────────────────

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("upi_reconcile_test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ── Autowired beans ───────────────────────────────────────────

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private MerchantGatewayConnectionRepository connectionRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private StateTransitionRepository stateTransitionRepository;
    @Autowired private BankRepository bankRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;

    // ── Test fixtures ─────────────────────────────────────────────

    private static final UUID REMITTER_BANK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BENEFICIARY_BANK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TEST_MERCHANT_EMAIL = "gateway-conn-test@example.com";
    private static final String TEST_MERCHANT_PASSWORD = "testPassword123";

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE state_transitions, webhook_events, transactions, " +
                        "merchant_gateway_connections, merchants CASCADE");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Seed banks
        if (bankRepository.findById(REMITTER_BANK_ID).isEmpty()) {
            bankRepository.save(Bank.builder()
                    .bankId(REMITTER_BANK_ID)
                    .name("Test Remitter Bank")
                    .historicalBdRate(new BigDecimal("0.0200"))
                    .historicalTdRate(new BigDecimal("0.0100"))
                    .historicalDeemedApprovedRate(new BigDecimal("0.0050"))
                    .build());
        }
        if (bankRepository.findById(BENEFICIARY_BANK_ID).isEmpty()) {
            bankRepository.save(Bank.builder()
                    .bankId(BENEFICIARY_BANK_ID)
                    .name("Test Beneficiary Bank")
                    .historicalBdRate(new BigDecimal("0.0150"))
                    .historicalTdRate(new BigDecimal("0.0080"))
                    .historicalDeemedApprovedRate(new BigDecimal("0.0040"))
                    .build());
        }
    }

    /**
     * Creates a test merchant (login identity only — no gateway connections).
     */
    private Merchant createMerchant() {
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = Merchant.builder()
                .merchantId(merchantId)
                .name("Gateway Conn Test Merchant")
                .email(TEST_MERCHANT_EMAIL)
                .passwordHash(passwordEncoder.encode(TEST_MERCHANT_PASSWORD))
                .businessName("Test Business")
                .createdAt(OffsetDateTime.now())
                .build();
        merchantRepository.save(merchant);
        return merchant;
    }

    private String jwtFor(UUID merchantId) {
        return jwtTokenProvider.generateToken(merchantId, TEST_MERCHANT_EMAIL);
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 1: Connect → Disconnect → Webhook rejected with 410
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Disconnecting a gateway stops new transactions from being created via that connection's webhook URL")
    void disconnectGateway_blocksSubsequentWebhooks() throws Exception {

        Merchant merchant = createMerchant();
        UUID merchantId = merchant.getMerchantId();
        String jwt = jwtFor(merchantId);

        // ── Step 1: Create a Razorpay connection ─────────────────
        Map<String, Object> connectRequest = new LinkedHashMap<>();
        connectRequest.put("gateway", "razorpay");
        connectRequest.put("apiKey", "rzp_test_key_001");
        connectRequest.put("apiSecret", "rzp_test_secret_001");

        MvcResult connectResult = mockMvc.perform(post("/api/connections")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(connectRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connectionId").exists())
                .andExpect(jsonPath("$.webhookSecret").exists())
                .andReturn();

        var connectResponse = objectMapper.readValue(
                connectResult.getResponse().getContentAsString(), Map.class);
        String connectionId = (String) connectResponse.get("connectionId");
        String webhookSecret = (String) connectResponse.get("webhookSecret");

        // ── Step 2: Verify the webhook works while ACTIVE ────────
        String paymentId1 = "pay_ACTIVE_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload1 = buildRazorpayPayload(paymentId1, 50000, "captured");
        String json1 = objectMapper.writeValueAsString(payload1);
        String signature1 = HmacSignatureVerifier.computeHmac(json1, webhookSecret);

        mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .param("connection_id", connectionId)
                        .header("X-Razorpay-Signature", signature1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"));

        assertThat(transactionRepository.count()).isEqualTo(1);

        // ── Step 3: Disconnect the connection ────────────────────
        mockMvc.perform(delete("/api/connections/" + connectionId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Connection disconnected"));

        // Verify connection status is DISCONNECTED
        MerchantGatewayConnection conn = connectionRepository
                .findById(UUID.fromString(connectionId)).orElseThrow();
        assertThat(conn.getStatus()).isEqualTo(MerchantGatewayConnection.STATUS_DISCONNECTED);
        assertThat(conn.getDisconnectedAt()).isNotNull();

        // ── Step 4: Webhook is rejected with 410 Gone ────────────
        String paymentId2 = "pay_DISCONN_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload2 = buildRazorpayPayload(paymentId2, 30000, "captured");
        String json2 = objectMapper.writeValueAsString(payload2);
        String signature2 = HmacSignatureVerifier.computeHmac(json2, webhookSecret);

        mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .param("connection_id", connectionId)
                        .header("X-Razorpay-Signature", signature2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json2))
                .andExpect(status().isGone());

        // No new transaction should have been created
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 2: Duplicate ACTIVE connection → 409 Conflict
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Creating a duplicate ACTIVE connection to the same gateway returns 409 Conflict")
    void duplicateActiveConnection_returns409() throws Exception {

        Merchant merchant = createMerchant();
        UUID merchantId = merchant.getMerchantId();
        String jwt = jwtFor(merchantId);

        Map<String, Object> connectRequest = new LinkedHashMap<>();
        connectRequest.put("gateway", "razorpay");
        connectRequest.put("apiKey", "rzp_test_key_001");
        connectRequest.put("apiSecret", "rzp_test_secret_001");
        String json = objectMapper.writeValueAsString(connectRequest);

        // First connection → 201 Created
        mockMvc.perform(post("/api/connections")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        // Second connection to same gateway → 409 Conflict
        Map<String, Object> connectRequest2 = new LinkedHashMap<>();
        connectRequest2.put("gateway", "razorpay");
        connectRequest2.put("apiKey", "rzp_test_key_002");
        connectRequest2.put("apiSecret", "rzp_test_secret_002");

        mockMvc.perform(post("/api/connections")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(connectRequest2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.existing_connection_id").exists());

        // Only one connection should exist
        assertThat(connectionRepository.findByMerchant_MerchantId(merchantId)).hasSize(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 3: Reconnect rotates webhook_secret and reactivates
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Reconnecting re-activates the connection and rotates the webhook secret")
    void reconnect_reactivatesAndRotatesWebhookSecret() throws Exception {

        Merchant merchant = createMerchant();
        UUID merchantId = merchant.getMerchantId();
        String jwt = jwtFor(merchantId);

        // ── Step 1: Create connection ────────────────────────────
        Map<String, Object> connectRequest = new LinkedHashMap<>();
        connectRequest.put("gateway", "razorpay");
        connectRequest.put("apiKey", "rzp_test_key_001");
        connectRequest.put("apiSecret", "rzp_test_secret_001");

        MvcResult createResult = mockMvc.perform(post("/api/connections")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(connectRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        var createResponse = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), Map.class);
        String connectionId = (String) createResponse.get("connectionId");
        String originalSecret = (String) createResponse.get("webhookSecret");

        // ── Step 2: Disconnect ───────────────────────────────────
        mockMvc.perform(delete("/api/connections/" + connectionId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        // ── Step 3: Reconnect with fresh credentials ─────────────
        Map<String, Object> reconnectRequest = new LinkedHashMap<>();
        reconnectRequest.put("gateway", "razorpay");
        reconnectRequest.put("apiKey", "rzp_test_key_ROTATED");
        reconnectRequest.put("apiSecret", "rzp_test_secret_ROTATED");

        MvcResult reconnectResult = mockMvc.perform(post("/api/connections/" + connectionId + "/reconnect")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reconnectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionId").value(connectionId))
                .andExpect(jsonPath("$.webhookSecret").exists())
                .andExpect(jsonPath("$.webhookUrl").exists())
                .andReturn();

        var reconnectResponse = objectMapper.readValue(
                reconnectResult.getResponse().getContentAsString(), Map.class);
        String newSecret = (String) reconnectResponse.get("webhookSecret");

        // ── Step 4: Verify secret was rotated ────────────────────
        assertThat(newSecret).isNotEqualTo(originalSecret);
        assertThat(newSecret).hasSize(64);
        assertThat(newSecret).matches("[0-9a-f]{64}");

        // ── Step 5: Verify connection is ACTIVE ──────────────────
        MerchantGatewayConnection conn = connectionRepository
                .findById(UUID.fromString(connectionId)).orElseThrow();
        assertThat(conn.getStatus()).isEqualTo(MerchantGatewayConnection.STATUS_ACTIVE);
        assertThat(conn.getDisconnectedAt()).isNull();
        assertThat(conn.getWebhookSecret()).isEqualTo(newSecret);

        // ── Step 6: Verify webhooks work with the NEW secret ─────
        String paymentId = "pay_RECONN_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload = buildRazorpayPayload(paymentId, 25000, "captured");
        String json = objectMapper.writeValueAsString(payload);
        String signature = HmacSignatureVerifier.computeHmac(json, newSecret);

        mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .param("connection_id", connectionId)
                        .header("X-Razorpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"));

        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 4: List connections shows correct status
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /api/connections returns all connections with correct statuses")
    void listConnections_returnsAllWithStatus() throws Exception {

        Merchant merchant = createMerchant();
        UUID merchantId = merchant.getMerchantId();
        String jwt = jwtFor(merchantId);

        // Create two connections to different gateways
        Map<String, Object> razorpayReq = Map.of(
                "gateway", "razorpay", "apiKey", "key1", "apiSecret", "secret1");
        Map<String, Object> payuReq = Map.of(
                "gateway", "payu", "apiKey", "key2", "apiSecret", "secret2");

        mockMvc.perform(post("/api/connections")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(razorpayReq)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/connections")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payuReq)))
                .andExpect(status().isCreated());

        // List should show 2 connections
        mockMvc.perform(get("/api/connections")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].connectionId").exists())
                .andExpect(jsonPath("$[0].gateway").exists())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper — build Razorpay payload
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> buildRazorpayPayload(String paymentId, int amountPaise, String status) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", paymentId);
        entity.put("entity", "payment");
        entity.put("amount", amountPaise);
        entity.put("currency", "INR");
        entity.put("status", status);
        entity.put("order_id", "order_" + UUID.randomUUID().toString().substring(0, 8));
        entity.put("method", "upi");
        entity.put("description", "Test payment");
        entity.put("error_code", null);
        entity.put("error_description", null);
        entity.put("error_source", null);
        entity.put("error_step", null);
        entity.put("error_reason", null);

        Map<String, Object> paymentWrapper = new LinkedHashMap<>();
        paymentWrapper.put("entity", entity);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment", paymentWrapper);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entity", "event");
        root.put("account_id", "acc_TEST123456");
        root.put("event", "payment." + status);
        root.put("contains", new String[]{"payment"});
        root.put("payload", payload);
        root.put("created_at", System.currentTimeMillis() / 1000);

        return root;
    }
}
