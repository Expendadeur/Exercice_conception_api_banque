package com.banque.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Application B2B authentifiée (jeton APP), par opposition à un titulaire. */
    private static final String APP_SEULEMENT = "isAuthenticated() and !hasAuthority('ROLE_TITULAIRE')";

    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable()
            .exceptionHandling().authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            .and()
            .authorizeRequests()
            .antMatchers("/api/banque/auth").permitAll()                               // login application B2B
            .antMatchers("/api/banque/sync/**").hasAnyAuthority("ROLE_EDGE_SYNC", "ROLE_APP")         // synchro bidirectionnelle Edge <-> Cloud
            .antMatchers("/api/banque/client/login").access(APP_SEULEMENT)             // login titulaire (via une app)
            .antMatchers("/api/banque/client/**").hasAuthority("ROLE_TITULAIRE")       // me, logout, changer-pin
            .antMatchers(HttpMethod.POST, "/api/banque/comptes").access(APP_SEULEMENT) // création de compte
            .antMatchers("/api/banque/credit").access(APP_SEULEMENT)
            .antMatchers("/api/banque/depot", "/api/banque/retrait", "/api/banque/virement",
                         "/api/banque/comptes/**").hasAuthority("ROLE_TITULAIRE")
            .anyRequest().denyAll()
            .and()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);

        http.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
