package com.intellidesk.auth.service;

import com.intellidesk.auth.JwtProperties;
import com.intellidesk.auth.JwtService;
import com.intellidesk.auth.dto.AuthResponse;
import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.common.exception.DuplicateResourceException;
import com.intellidesk.common.exception.InvalidCredentialsException;
import com.intellidesk.security.IntelliDeskUserDetails;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-layer unit tests with Mockito: business rules and security
 * invariants (no plaintext stored, fixed role, enum-translate exceptions)
 * verified without Spring or a database.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String JWT_SECRET = "auth-service-unit-test-secret-0123456789abcdef";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                new JwtService(new JwtProperties(JWT_SECRET, 30)));
    }

    private User user(String email, Role role, boolean active) {
        return new User(email, "$2a$10$storedhashstoredhashstoredhashstoredhash", "Test User", null, role);
    }

    @Test
    void registerHashesPasswordAssignsCustomerRoleAndReturnsToken() {
        when(userRepository.existsByEmail("new@test.local")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("$2a$10$encoded");
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, User.class));

        AuthResponse response = authService.register(
                new RegisterRequest("New@Test.local", "Passw0rd!", "New User", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("new@test.local");           // normalized lowercase
        assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$encoded");    // hash, never the raw value
        assertThat(saved.getPasswordHash()).doesNotContain("Passw0rd!");
        assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);               // role is fixed server-side
        assertThat(saved.isActive()).isTrue();

        assertThat(response.token()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().email()).isEqualTo("new@test.local");
        assertThat(response.user().role()).isEqualTo("CUSTOMER");
    }

    @Test
    void registerDuplicateEmailIsRejected() {
        when(userRepository.existsByEmail("taken@test.local")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("taken@test.local", "Passw0rd!", "Dup", null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("taken@test.local");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginSuccessReturnsTokenForCorrectCredentials() {
        User customer = user("login@test.local", Role.CUSTOMER, true);
        IntelliDeskUserDetails principal = new IntelliDeskUserDetails(customer);
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        AuthResponse response = authService.login(new LoginRequest("login@test.local", "whatever"));

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().email()).isEqualTo("login@test.local");
        assertThat(response.user().role()).isEqualTo("CUSTOMER");
    }

    @Test
    void loginBadCredentialsBecomesInvalidCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("login@test.local", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void loginDeactivatedAccountIsRejected() {
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("off"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("login@test.local", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("deactivated");
    }
}
