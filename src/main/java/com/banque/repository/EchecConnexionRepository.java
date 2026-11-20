package com.banque.repository;

import com.banque.model.EchecConnexion;
import java.util.Date;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EchecConnexionRepository extends JpaRepository<EchecConnexion, Long> {

    @Query("SELECT COUNT(e) FROM EchecConnexion e WHERE e.compteId = :id AND e.dateEchec >= :depuis")
    long compterDepuis(@Param("id") String id, @Param("depuis") Date depuis);
}
