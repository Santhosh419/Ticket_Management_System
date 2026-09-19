package com.intellidesk.security;

import com.intellidesk.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users by email (email is our principal username). Declaring this bean
 * makes Spring Boot abandon its auto-generated in-memory default user.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email == null ? "" : email.trim().toLowerCase())
                .map(IntelliDeskUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));
    }
}
