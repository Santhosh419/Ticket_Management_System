package com.intellidesk.security;

import com.intellidesk.auth.JwtService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher;

/**
 * The whole HTTP security contract in one place:
 *
 * <pre>
 *   request -> JwtAuthenticationFilter -> authorization rules -> controller
 * </pre>
 *
 * Decisions worth being able to defend in an interview:
 * <ul>
 *   <li>CSRF off: there are no cookies and no server-side sessions - a stolen
 *       CSRF token game does not apply to a pure Bearer-token API.</li>
 *   <li>STATELESS sessions: Spring never creates an HTTP session; every
 *       request must present a valid JWT.</li>
 *   <li>/api/auth/register and /api/auth/login are the only public API paths;
 *       Swagger/OpenAPI stays open for developer experience; everything else
 *       requires authentication (secure by default).</li>
 *   <li>Path-level rules (hasRole) for the role namespaces PLUS method-level
 *       @PreAuthorize on controllers (defense in depth).</li>
 *   <li>401 vs 403 handled by dedicated JSON handlers, not defaults.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   UserDetailsService userDetailsService) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // 1. public: registration + login
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // 2. public: OpenAPI/Swagger documentation
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 3. error dispatches (served by Boot's /error) must not be re-authorized
                        .requestMatchers(new DispatcherTypeRequestMatcher(DispatcherType.ERROR)).permitAll()
                        // 4. role namespaces
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/agent/**").hasRole("AGENT")
                        .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                        // 5. everything else under the API: any valid identity
                        .requestMatchers("/api/**").authenticated()
                        // 6. anything non-API: also protected (secure by default)
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * DaoAuthenticationProvider compares the submitted password against the
     * stored BCrypt hash via the PasswordEncoder and hides "user not found"
     * inside "bad credentials" (account-enumeration defense). Exposed as the
     * app-wide AuthenticationManager for the login flow.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
