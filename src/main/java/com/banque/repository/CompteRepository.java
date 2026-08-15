package com.banque.repository;

import com.banque.model.Compte;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CompteRepository extends JpaRepository<Compte, String> {
    
    // Verrouille la ligne en base de données pendant l'opération pour éviter les calculs de solde faussés
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Compte c WHERE c.id = :id")
    Optional<Compte> findByIdForUpdate(@Param("id") String id);
}