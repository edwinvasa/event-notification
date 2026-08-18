package com.edwin.eventnotification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;

import com.edwin.eventnotification.adapter.in.rest.security.ApiKeyAuthenticationConverter;
import com.edwin.eventnotification.adapter.in.rest.security.ApiKeyAuthenticationProvider;
import com.edwin.eventnotification.application.port.out.ApiKeyRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public AuthenticationManager authenticationManager(ApiKeyRepository apiKeyRepository) {
        // Exposing this as its own bean (instead of building it inline inside the filter chain)
        // is what makes Spring Boot's UserDetailsServiceAutoConfiguration back off - without an
        // AuthenticationManager bean present, it still creates a default in-memory user and logs
        // a generated password on every startup, even though nothing in this app uses it.
        return new ProviderManager(new ApiKeyAuthenticationProvider(apiKeyRepository));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager authenticationManager)
            throws Exception {
        AuthenticationFilter apiKeyFilter =
                new AuthenticationFilter(authenticationManager, new ApiKeyAuthenticationConverter());
        apiKeyFilter.setSuccessHandler((request, response, authentication) -> {
            // Authentication succeeded: let the request continue to the controller instead of
            // committing a response (the default behavior assumes a redirect-based login flow).
        });
        apiKeyFilter.setFailureHandler(
                new AuthenticationEntryPointFailureHandler(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
