package com.banque.service;

import com.banque.model.EdgeSyncQueue;
import com.banque.security.EdgeSyncQueueRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EdgeCloudSyncService {

    @Autowired
    private EdgeSyncQueueRepository queueRepository;

    @Autowired
    private BanqueService banqueService;

    /**
     * Traitement asynchrone automatisé (Cron DevOps) toutes les 10 secondes.
     * Synchronise tous les Dépôts, Retraits et Virements accumulés sur l'Edge.
     */
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void synchroniserEdgeVersCloud() {
        List<EdgeSyncQueue> transactionsEnAttente = queueRepository.findByStatutSynchronisation("PENDING");
        
        if (transactionsEnAttente.isEmpty()) return;

        System.out.println("[MOTEUR SYNC EDGE] Détection de " + transactionsEnAttente.size() + " opérations en attente.");

        for (EdgeSyncQueue tx : transactionsEnAttente) {
            try {
                // Routage intelligent selon le type d'opération mémorisé
                switch (tx.getTypeOperation()) {
                    case "DEPOT":
                        banqueService.depot(tx.getCompteId(), tx.getMontant());
                        System.out.println("[MOTEUR SYNC EDGE] Dépôt synchronisé pour le compte " + tx.getCompteId());
                        break;
                        
                    case "RETRAIT":
                        banqueService.retrait(tx.getCompteId(), tx.getMontant());
                        System.out.println("[MOTEUR SYNC EDGE] Retrait synchronisé pour le compte " + tx.getCompteId());
                        break;
                        
                    case "VIREMENT":
                        banqueService.virement(tx.getCompteId(), tx.getCompteDestinationId(), tx.getMontant());
                        System.out.println("[MOTEUR SYNC EDGE] Virement synchronisé de " + tx.getCompteId() + " vers " + tx.getCompteDestinationId());
                        break;
                        
                    default:
                        throw new RuntimeException("Type d'opération inconnu : " + tx.getTypeOperation());
                }
                
                tx.setStatutSynchronisation("SYNCED");
            } catch (Exception e) {
                tx.setStatutSynchronisation("FAILED");
                System.err.println("[MOTEUR SYNC EDGE] Échec d'exécution pour la transaction ID " + tx.getId() + " : " + e.getMessage());
            }
            queueRepository.save(tx);
        }
    }
}