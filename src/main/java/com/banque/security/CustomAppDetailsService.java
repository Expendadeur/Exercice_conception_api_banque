package com.banque.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomAppDetailsService implements UserDetailsService {

    @Autowired
    private ApplicationConsommatriceRepository repository;

    @Override
    public UserDetails loadUserByUsername(String clientId) throws UsernameNotFoundException {
        ApplicationConsommatrice app = repository.findByClientId(clientId)
                .orElseThrow(() -> new UsernameNotFoundException("Application consommatrice introuvable : " + clientId));

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(app.getRole()));

        return new User(app.getClientId(), app.getClientSecret(), authorities);
    }
}