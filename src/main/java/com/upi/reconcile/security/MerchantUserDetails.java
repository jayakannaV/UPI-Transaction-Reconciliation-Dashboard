package com.upi.reconcile.security;

import com.upi.reconcile.connectors.domain.Merchant;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security {@link UserDetails} adapter wrapping a {@link Merchant}.
 * <p>
 * Carries the merchant's UUID so the JWT filter can build an
 * {@code Authentication} with the full merchant identity.
 */
@Getter
public class MerchantUserDetails implements UserDetails {

    private final UUID merchantId;
    private final String email;
    private final String passwordHash;
    private final String businessName;

    public MerchantUserDetails(Merchant merchant) {
        this.merchantId = merchant.getMerchantId();
        this.email = merchant.getEmail();
        this.passwordHash = merchant.getPasswordHash();
        this.businessName = merchant.getBusinessName();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
