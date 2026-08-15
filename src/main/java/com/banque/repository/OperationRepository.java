package com.banque.repository;

import com.banque.model.Operation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OperationRepository extends JpaRepository<Operation, Long> {
    
    // Requête pour récupérer l'historique d'un compte spécifique (en tant que source ou destination)
    @Query("SELECT o FROM Operation o WHERE o.compteSourceId = :compteId OR o.compteDestinationId = :compteId ORDER BY o.dateOperation DESC")
    List<Operation> findByCompteId(@Param("compteId") String compteId);
}