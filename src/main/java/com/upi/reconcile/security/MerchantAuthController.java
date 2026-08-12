package com.upi.reconcile.security;

import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Authentication controller — signup, login, and "who am I" endpoints.
 *
 * <pre>
 * POST /api/auth/signup   → creates Merchant + returns JWT
 * POST /api/auth/login    → validates credentials + returns JWT
 * GET  /api/auth/me       → returns authenticated merchant info
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class MerchantAuthController {

    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    // ── POST /api/auth/signup ─────────────────────────────────────

    public record SignupRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
            String businessName) {
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest request) {
        if (merchantRepository.existsByEmail(request.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Email already registered"));
        }

        UUID merchantId = UUID.randomUUID();
        Merchant merchant = Merchant.builder()
                .merchantId(merchantId)
                .name(request.businessName() != null ? request.businessName()
                        : "Merchant-" + merchantId.toString().substring(0, 8))
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .businessName(request.businessName())
                // Stub gateway fields — merchant calls /api/merchants/connect later
                .connectedGateway("")
                .encryptedApiKey("")
                .encryptedApiSecret("")
                .createdAt(OffsetDateTime.now())
                .build();

        merchantRepository.save(merchant);

        String token = jwtTokenProvider.generateToken(merchantId, request.email());

        log.info("Merchant signed up — id={}, email={}", merchantId, request.email());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "token", token,
                "merchant_id", merchantId,
                "email", request.email()));
    }

    // ── POST /api/auth/login ──────────────────────────────────────

    public record LoginRequest(
            @NotBlank String email,
            @NotBlank String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Merchant merchant = merchantRepository.findByEmail(request.email()).orElse(null);

        if (merchant == null ||
                !passwordEncoder.matches(request.password(), merchant.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }

        String token = jwtTokenProvider.generateToken(
                merchant.getMerchantId(), merchant.getEmail());

        log.info("Merchant logged in — id={}, email={}", merchant.getMerchantId(), request.email());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "merchant_id", merchant.getMerchantId(),
                "email", merchant.getEmail()));
    }

    // ── GET /api/auth/me ──────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        UUID merchantId = MerchantContextHolder.currentMerchantId();
        Merchant merchant = merchantRepository.findById(merchantId).orElse(null);

        if (merchant == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Merchant not found"));
        }

        return ResponseEntity.ok(Map.of(
                "merchant_id", merchant.getMerchantId(),
                "email", merchant.getEmail(),
                "business_name", merchant.getBusinessName() != null ? merchant.getBusinessName() : "",
                "connected_gateway", merchant.getConnectedGateway(),
                "created_at", merchant.getCreatedAt().toString()));
    }
}
