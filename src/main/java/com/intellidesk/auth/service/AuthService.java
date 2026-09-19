package com.intellidesk.auth.service;

import com.intellidesk.auth.JwtService;
import com.intellidesk.auth.dto.AuthResponse;
import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.auth.dto.UserResponse;
import com.intellidesk.common.exception.DuplicateResourceException;
import com.intellidesk.common.exception.InvalidCredentialsException;
import com.intellidesk.security.IntelliDeskUserDetails;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login use cases.
 *
 * <p>Registration: normalize -> uniqueness check -> BCrypt hash -> save with
 * the fixed CUSTOMER role. There is no code path where a client chooses a
 * role, so privilege escalation through registration is impossible.</p>
 *
 * <p>Login: the {@link AuthenticationManager} (DaoAuthenticationProvider)
 * does the BCrypt comparison; we translate Spring's exceptions into our own
 * so the API never leaks Spring Security internals. "Bad credentials" and
 * "user not found" produce the SAME message - attackers must not be able to
 * discover which emails exist (account-enumeration defense).</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email is already registered: " + email);
        }

        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                normalizePhone(request.phone()),
                Role.CUSTOMER
        );
        User saved = userRepository.save(user);
        log.info("Registered new customer id={} email={}", saved.getId(), saved.getEmail());

        return buildAuthResponse(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizeEmail(request.email()), request.password()));
            IntelliDeskUserDetails principal = (IntelliDeskUserDetails) authentication.getPrincipal();
            log.info("Login succeeded for email={}", principal.getUser().getEmail());
            return buildAuthResponse(principal.getUser());
        } catch (DisabledException ex) {
            // Identity matches but the account is off - still 401 from the client's view
            throw new InvalidCredentialsException("Account is deactivated");
        } catch (BadCredentialsException ex) {
            throw new InvalidCredentialsException("Invalid email or password");
        } catch (AuthenticationException ex) {
            // Any other provider failure must not leak provider internals
            throw new InvalidCredentialsException("Invalid email or password");
        }
    }

    private AuthResponse buildAuthResponse(User user) {
        JwtService.IssuedToken token = jwtService.issue(user.getEmail(), user.getRole(), user.getId());
        return new AuthResponse(token.value(), "Bearer", token.expiresAt(), UserResponse.from(user));
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
