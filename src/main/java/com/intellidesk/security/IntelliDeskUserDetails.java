package com.intellidesk.security;

import com.intellidesk.user.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Adapter that presents our {@link User} entity in Spring Security's
 * {@link UserDetails} language. Wraps the entity instead of extending it -
 * the entity stays a pure JPA concept, security concerns stay in security.
 *
 * <p>Spring convention: authority name = "ROLE_" + role name, which is what
 * {@code hasRole("ADMIN")} checks.</p>
 */
public class IntelliDeskUserDetails implements UserDetails {

    private final User user;

    public IntelliDeskUserDetails(User user) {
        this.user = user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
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

    /** Ties Spring Security's "enabled" concept to our users.active flag. */
    @Override
    public boolean isEnabled() {
        return user.isActive();
    }

    public User getUser() {
        return user;
    }
}
