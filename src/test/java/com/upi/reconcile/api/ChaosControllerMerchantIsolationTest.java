package com.upi.reconcile.api;

import com.upi.reconcile.domain.ChaosService;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.security.JwtAuthenticationFilter;
import com.upi.reconcile.security.JwtProperties;
import com.upi.reconcile.security.JwtTokenProvider;
import com.upi.reconcile.security.MerchantUserDetailsService;
import com.upi.reconcile.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies merchant isolation for chaos control endpoints.
 *
 * <p>Core security property: a merchant MUST NOT be able to trigger chaos
 * actions against another merchant's transaction IDs. Attempts should
 * return 404 (transaction "not found" from the attacker's perspective),
 * never silently succeed.
 */
@WebMvcTest(ChaosController.class)
@Import({SecurityConfig.class, JwtProperties.class, JwtTokenProvider.class, JwtAuthenticationFilter.class})
@ActiveProfiles("demo")
class ChaosControllerMerchantIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChaosService chaosService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MerchantUserDetailsService merchantUserDetailsService;

    // ── Fixtures ──────────────────────────────────────────────────────

    /** Merchant A — the authenticated merchant making the request. */
    private static final UUID MERCHANT_A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /** Merchant B — the victim merchant whose transaction is targeted. */
    private static final UUID MERCHANT_B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    /** A transaction ID belonging to Merchant B (not Merchant A). */
    private static final UUID MERCHANT_B_TXN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** A transaction ID belonging to Merchant A (the authenticated merchant). */
    private static final UUID MERCHANT_A_TXN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUpSecurityContextAsMerchantA() {
        // Authenticate as Merchant A
        var auth = new UsernamePasswordAuthenticationToken(
                MERCHANT_A_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── Cross-merchant isolation tests ────────────────────────────────

    @Test
    @DisplayName("fire-duplicate: Merchant A cannot target Merchant B's transaction → 404")
    void fireDuplicate_crossMerchant_returns404() throws Exception {
        // ChaosService throws IllegalArgumentException when txn not found for this merchant
        when(chaosService.fireDuplicate(MERCHANT_B_TXN_ID, MERCHANT_A_ID))
                .thenThrow(new IllegalArgumentException(
                        "Transaction not found or not owned by this merchant"));

        mockMvc.perform(post("/api/chaos/fire-duplicate/{txnId}", MERCHANT_B_TXN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        "Transaction not found or not owned by this merchant"));
    }

    @Test
    @DisplayName("force-breach: Merchant A cannot target Merchant B's transaction → 404")
    void forceBreach_crossMerchant_returns404() throws Exception {
        when(chaosService.forceBreach(MERCHANT_B_TXN_ID, MERCHANT_A_ID))
                .thenThrow(new IllegalArgumentException(
                        "Transaction not found or not owned by this merchant"));

        mockMvc.perform(post("/api/chaos/force-breach/{txnId}", MERCHANT_B_TXN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        "Transaction not found or not owned by this merchant"));
    }

    @Test
    @DisplayName("fire-duplicate: Merchant A on own transaction → 200 with DUPLICATE_IGNORED")
    void fireDuplicate_ownTransaction_succeeds() throws Exception {
        WebhookResponse dupResponse = WebhookResponse.builder()
                .txnId(MERCHANT_A_TXN_ID)
                .state(WebhookResponse.DUPLICATE_IGNORED)
                .build();

        when(chaosService.fireDuplicate(MERCHANT_A_TXN_ID, MERCHANT_A_ID))
                .thenReturn(dupResponse);

        mockMvc.perform(post("/api/chaos/fire-duplicate/{txnId}", MERCHANT_A_TXN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value(MERCHANT_A_TXN_ID.toString()))
                .andExpect(jsonPath("$.state").value("DUPLICATE_IGNORED"));
    }

    @Test
    @DisplayName("force-breach: Merchant A on own PENDING_RECONCILIATION txn → 200")
    void forceBreach_ownTransaction_succeeds() throws Exception {
        Transaction breached = Transaction.builder()
                .txnId(MERCHANT_A_TXN_ID)
                .idempotencyKey("test-key-breach")
                .state(TransactionState.PENDING_RECONCILIATION)
                .amountInr(new BigDecimal("1500.00"))
                .sourceGateway("simulated")
                .createdAt(OffsetDateTime.now())
                .tatDeadline(OffsetDateTime.now().plusSeconds(3))
                .build();

        when(chaosService.forceBreach(MERCHANT_A_TXN_ID, MERCHANT_A_ID))
                .thenReturn(breached);

        mockMvc.perform(post("/api/chaos/force-breach/{txnId}", MERCHANT_A_TXN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value(MERCHANT_A_TXN_ID.toString()))
                .andExpect(jsonPath("$.state").value("PENDING_RECONCILIATION"));
    }

    @Test
    @DisplayName("simulate-razorpay-payment: No Razorpay connection → 400")
    void simulateRazorpay_noConnection_returns400() throws Exception {
        when(chaosService.simulateRazorpayPayment(MERCHANT_A_ID))
                .thenReturn(null);

        mockMvc.perform(post("/api/chaos/simulate-razorpay-payment"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Connect Razorpay first to use this action"));
    }

    @Test
    @DisplayName("simulate-missed-webhook: No prior Razorpay SUCCESS transaction → 400")
    void simulateMissedWebhook_noPriorTxn_returns400() throws Exception {
        when(chaosService.simulateMissedWebhook(MERCHANT_A_ID))
                .thenThrow(new IllegalStateException(
                        "Send one real test payment through Razorpay checkout first, then this action will be available."));

        mockMvc.perform(post("/api/chaos/simulate-missed-webhook"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Send one real test payment through Razorpay checkout first, then this action will be available."));
    }

    @Test
    @DisplayName("simulate-missed-webhook: Valid prior txn exists → 200 with new staged txn")
    void simulateMissedWebhook_valid_succeeds() throws Exception {
        Transaction staged = Transaction.builder()
                .txnId(UUID.randomUUID())
                .idempotencyKey("chaos-demo-abcdef12")
                .state(TransactionState.PENDING_RECONCILIATION)
                .amountInr(new BigDecimal("500.00"))
                .sourceGateway("razorpay")
                .createdAt(OffsetDateTime.now())
                .build();

        when(chaosService.simulateMissedWebhook(MERCHANT_A_ID))
                .thenReturn(staged);

        mockMvc.perform(post("/api/chaos/simulate-missed-webhook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value(staged.getTxnId().toString()))
                .andExpect(jsonPath("$.state").value("PENDING_RECONCILIATION"))
                .andExpect(jsonPath("$.sourceGateway").value("razorpay"));
    }

    @Test
    @DisplayName("Unauthenticated request to chaos endpoint → denied (401 or 403)")
    void unauthenticated_returnsDenied() throws Exception {
        // Perform the request as an anonymous user — no JWT present.
        // Spring Security should block the request before it reaches the controller.
        mockMvc.perform(post("/api/chaos/create-deemed-approved")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().is4xxClientError());

        // ChaosService should never be called
        verify(chaosService, never()).createDeemedApproved(any());
    }
}

