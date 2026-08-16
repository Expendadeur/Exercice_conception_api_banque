package com.banque.security;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationConsommatriceRepository extends JpaRepository<ApplicationConsommatrice, String> {
    Optional<ApplicationConsommatrice> findByClientId(String clientId);
}