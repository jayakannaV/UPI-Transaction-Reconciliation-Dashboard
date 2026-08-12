package com.upi.reconcile.security;

import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a {@link Merchant} by email for Spring Security authentication.
 */
@Service
@RequiredArgsConstructor
public class MerchantUserDetailsService implements UserDetailsService {

    private final MerchantRepository merchantRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Merchant merchant = merchantRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Merchant not found with email: " + email));
        return new MerchantUserDetails(merchant);
    }
}
