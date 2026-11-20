package com.banque.repository;

import com.banque.model.Operation;
import java.util.Date;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OperationRepository extends JpaRepository<Operation, Long> {

    @Query("SELECT o FROM Operation o WHERE o.compteSourceId = :compteId OR o.compteDestinationId = :compteId ORDER BY o.dateOperation DESC")
    List<Operation> findByCompteId(@Param("compteId") String compteId);

    // ---- Variables anti-fraude ----

    /** nb_operations_precedentes_24h */
    @Query("SELECT COUNT(o) FROM Operation o WHERE o.compteSourceId = :id AND o.type IN ('DEPOT','RETRAIT','VIREMENT') AND o.dateOperation >= :depuis")
    long compterOperationsDepuis(@Param("id") String id, @Param("depuis") Date depuis);

    /** montant_precedent_moyen_30j (null s'il n'y a aucun historique) */
    @Query("SELECT AVG(o.montant) FROM Operation o WHERE o.compteSourceId = :id AND o.type IN ('DEPOT','RETRAIT','VIREMENT') AND o.dateOperation >= :depuis")
    Double moyenneMontantDepuis(@Param("id") String id, @Param("depuis") Date depuis);

    /** nouveau_beneficiaire : aucun virement antérieur vers ce destinataire */
    @Query("SELECT COUNT(o) FROM Operation o WHERE o.compteSourceId = :src AND o.compteDestinationId = :dest AND o.type = 'VIREMENT'")
    long compterVirementsVers(@Param("src") String src, @Param("dest") String dest);
}