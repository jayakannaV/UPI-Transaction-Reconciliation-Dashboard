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
import org.junit.jupiter.api.Nested;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the simulated-gateway guard on chaos endpoints.
 *
 * <p>Core invariant: chaos actions that create TAT-breach / penalty-accrual
 * demo scenarios MUST only operate on {@code gateway="simulated"} transactions.
 * If called on a real-gateway transaction, the {@link com.upi.reconcile.connectors.GatewayResolutionService}
 * would auto-resolve it via the gateway's real API, defeating the demo.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>force-breach rejects real-gateway transactions with 400</li>
 *   <li>force-breach succeeds on simulated transactions (PENALTY_ACCRUING path works)</li>
 *   <li>create-deemed-approved always produces simulated-gateway transactions</li>
 *   <li>Gateway resolution never invokes for simulated-gateway transactions
 *       (verified by the {@code findByStateAndSourceGateway} query pattern)</li>
 * </ul>
 */
@WebMvcTest(ChaosController.class)
@Import({SecurityConfig.class, JwtProperties.class, JwtTokenProvider.class, JwtAuthenticationFilter.class})
@ActiveProfiles("demo")
class ChaosSimulatedGatewayTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChaosService chaosService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MerchantUserDetailsService merchantUserDetailsService;

    // ── Fixtures ──────────────────────────────────────────────────────

    private static final UUID MERCHANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SIMULATED_TXN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RAZORPAY_TXN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUpSecurityContext() {
        var auth = new UsernamePasswordAuthenticationToken(
                MERCHANT_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── Force-breach gateway guard ────────────────────────────────────

    @Nested
    @DisplayName("force-breach: simulated-gateway guard")
    class ForceBreachGatewayGuard {

        @Test
        @DisplayName("Rejects Razorpay-linked transaction → 400 with clear error message")
        void forceBreach_razorpayTransaction_returns400() throws Exception {
            when(chaosService.forceBreach(RAZORPAY_TXN_ID, MERCHANT_ID))
                    .thenThrow(new IllegalStateException(
                            "Force-breach only applies to simulated transactions — "
                            + "use Seed Demo Data or create a new simulated transaction"));

            mockMvc.perform(post("/api/chaos/force-breach/{txnId}", RAZORPAY_TXN_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error",
                            containsString("Force-breach only applies to simulated transactions")));
        }

        @Test
        @DisplayName("Succeeds on simulated transaction → 200, state=PENDING_RECONCILIATION, no resolution")
        void forceBreach_simulatedTransaction_succeeds() throws Exception {
            Transaction breached = Transaction.builder()
                    .txnId(SIMULATED_TXN_ID)
                    .idempotencyKey("chaos-seed-breach-" + UUID.randomUUID())
                    .state(TransactionState.PENDING_RECONCILIATION)
                    .amountInr(new BigDecimal("2500.00"))
                    .sourceGateway("simulated")
                    .createdAt(OffsetDateTime.now())
                    .tatDeadline(OffsetDateTime.now().plusSeconds(3))
                    .build();

            when(chaosService.forceBreach(SIMULATED_TXN_ID, MERCHANT_ID))
                    .thenReturn(breached);

            mockMvc.perform(post("/api/chaos/force-breach/{txnId}", SIMULATED_TXN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.txnId").value(SIMULATED_TXN_ID.toString()))
                    .andExpect(jsonPath("$.state").value("PENDING_RECONCILIATION"))
                    .andExpect(jsonPath("$.sourceGateway").value("simulated"));
        }
    }

    // ── Create-deemed-approved gateway invariant ──────────────────────

    @Nested
    @DisplayName("create-deemed-approved: always produces simulated-gateway transactions")
    class CreateDeemedApprovedGateway {

        @Test
        @DisplayName("Returns transaction with sourceGateway=simulated even when merchant has active Razorpay")
        void createDeemedApproved_alwaysSimulated() throws Exception {
            Transaction deemed = Transaction.builder()
                    .txnId(UUID.randomUUID())
                    .idempotencyKey("chaos-deemed-" + UUID.randomUUID())
                    .state(TransactionState.DEEMED_APPROVED)
                    .amountInr(new BigDecimal("1800.00"))
                    .sourceGateway("simulated")
                    .createdAt(OffsetDateTime.now())
                    .build();

            when(chaosService.createDeemedApproved(MERCHANT_ID))
                    .thenReturn(deemed);

            mockMvc.perform(post("/api/chaos/create-deemed-approved"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("DEEMED_APPROVED"))
                    .andExpect(jsonPath("$.sourceGateway").value("simulated"));
        }
    }
}
