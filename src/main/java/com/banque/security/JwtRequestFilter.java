package com.banque.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.io.IOException;
import java.util.Collections;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CustomAppDetailsService customAppDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtUtil.parseClaims(header.substring(7)); // signature + expiration

                if (jwtUtil.estRevoque(claims.getId())) {
                    logger.warn("Jeton révoqué (logout) présenté.");
                } else {
                    UsernamePasswordAuthenticationToken auth;

                    if (JwtUtil.TYPE_CLIENT.equals(claims.get("typ", String.class))) {
                        // Jeton TITULAIRE
                        ClientPrincipal principal = new ClientPrincipal(
                                claims.getSubject(), claims.get("app", String.class),
                                claims.getId(), claims.getExpiration());
                        auth = new UsernamePasswordAuthenticationToken(principal, null,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_TITULAIRE")));
                    } else {
                        // Jeton APPLICATION B2B
                        UserDetails userDetails = customAppDetailsService.loadUserByUsername(claims.getSubject());
                        auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    }
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
                logger.warn("Jeton JWT invalide ou application inconnue");
            }
        }
        chain.doFilter(request, response);
    }
}
