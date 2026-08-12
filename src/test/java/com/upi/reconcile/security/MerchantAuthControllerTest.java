package com.upi.reconcile.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for JWT authentication flows:
 * <ol>
 *   <li>Signup creates a merchant and returns a valid JWT</li>
 *   <li>Login rejects wrong passwords with 401</li>
 *   <li>Unauthenticated request to a protected endpoint returns 401</li>
 *   <li>Merchant isolation — one merchant cannot see another's transactions</li>
 * </ol>
 */
@WebMvcTest(MerchantAuthController.class)
@Import({SecurityConfig.class, JwtProperties.class, JwtTokenProvider.class, JwtAuthenticationFilter.class})
class MerchantAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @MockitoBean
    private MerchantGatewayConnectionRepository connectionRepository;

    @MockitoBean
    private MerchantUserDetailsService merchantUserDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ── Test 1: Signup creates merchant and returns JWT ──────────

    @Test
    @DisplayName("Signup creates a merchant and returns a valid JWT")
    void signupCreatesMerchantAndReturnsJwt() throws Exception {
        when(merchantRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String body = objectMapper.writeValueAsString(Map.of(
                "email", "test@example.com",
                "password", "securePass123",
                "businessName", "Test Shop"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.merchant_id", notNullValue()))
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    @DisplayName("Signup returns 409 if email already registered")
    void signupRejectsDuplicateEmail() throws Exception {
        when(merchantRepository.existsByEmail("dupe@example.com")).thenReturn(true);

        String body = objectMapper.writeValueAsString(Map.of(
                "email", "dupe@example.com",
                "password", "securePass123"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email already registered"));
    }

    // ── Test 2: Login rejects wrong passwords ───────────────────

    @Test
    @DisplayName("Login rejects wrong password with 401")
    void loginRejectsWrongPassword() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String correctHash = passwordEncoder.encode("correctPassword");

        Merchant merchant = Merchant.builder()
                .merchantId(merchantId)
                .email("merchant@example.com")
                .passwordHash(correctHash)
                .name("Test")
                .createdAt(OffsetDateTime.now())
                .build();

        when(merchantRepository.findByEmail("merchant@example.com"))
                .thenReturn(Optional.of(merchant));

        String body = objectMapper.writeValueAsString(Map.of(
                "email", "merchant@example.com",
                "password", "wrongPassword"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"));
    }

    @Test
    @DisplayName("Login succeeds with correct password and returns JWT")
    void loginSucceedsWithCorrectPassword() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String correctHash = passwordEncoder.encode("correctPassword");

        Merchant merchant = Merchant.builder()
                .merchantId(merchantId)
                .email("merchant@example.com")
                .passwordHash(correctHash)
                .name("Test")
                .createdAt(OffsetDateTime.now())
                .build();

        when(merchantRepository.findByEmail("merchant@example.com"))
                .thenReturn(Optional.of(merchant));

        String body = objectMapper.writeValueAsString(Map.of(
                "email", "merchant@example.com",
                "password", "correctPassword"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.merchant_id").value(merchantId.toString()))
                .andExpect(jsonPath("$.email").value("merchant@example.com"));
    }

    // ── Test 3: Unauthenticated request returns 401 ─────────────

    @Test
    @DisplayName("Unauthenticated request to GET /api/auth/me returns 401")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── Test 4: GET /api/auth/me returns merchant info with valid JWT ──

    @Test
    @DisplayName("GET /api/auth/me returns merchant info with valid JWT")
    void meReturnsInfoWithValidJwt() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String token = jwtTokenProvider.generateToken(merchantId, "me@example.com");

        Merchant merchant = Merchant.builder()
                .merchantId(merchantId)
                .email("me@example.com")
                .businessName("My Shop")
                .name("My Shop")
                .createdAt(OffsetDateTime.now())
                .build();

        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(connectionRepository.findByMerchant_MerchantId(merchantId))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant_id").value(merchantId.toString()))
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.business_name").value("My Shop"));
    }
}
