package com.banque.service;

import com.banque.model.Compte;
import com.banque.model.Operation;
import com.banque.repository.CompteRepository;
import com.banque.repository.OperationRepository;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BanqueService {

    @Autowired
    private CompteRepository compteRepository;

    @Autowired
    private OperationRepository operationRepository;

    // Consulter un compte avec verrou de sécurité
    public Compte obtenirCompte(String id) {
        return compteRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RuntimeException("Erreur : Le compte " + id + " n'existe pas."));
    }

    // Créer un compte initial
    public Compte creerCompte(Compte compte) {
        if (compteRepository.existsById(compte.getId())) {
            throw new RuntimeException("Erreur : Ce numéro de compte existe déjà.");
        }
        return compteRepository.save(compte);
    }

    // Opération de Dépôt
    public void depot(String id, double montant) {
        if (montant <= 0) throw new RuntimeException("Le montant doit être supérieur à 0");
        
        Compte compte = obtenirCompte(id);
        compte.setSolde(compte.getSolde() + montant);
        compteRepository.save(compte);

        // Sauvegarde de l'opération
        Operation op = new Operation("DEPOT", montant, new Date(), id, null);
        operationRepository.save(op);
    }

    // Opération de Retrait
    public void retrait(String id, double montant) {
        if (montant <= 0) throw new RuntimeException("Le montant doit être supérieur à 0");
        
        Compte compte = obtenirCompte(id);
        
        // Vérification Solde + Plafond de crédit autorisé
        if (compte.getSolde() + compte.getPlafondCredit() < montant) {
            throw new RuntimeException("Solde et crédit insuffisants pour effectuer ce retrait.");
        }
        
        compte.setSolde(compte.getSolde() - montant);
        compteRepository.save(compte);

        // Sauvegarde de l'opération
        Operation op = new Operation("RETRAIT", montant, new Date(), id, null);
        operationRepository.save(op);
    }

    // Opération de Virement de compte à compte
    public void virement(String sourceId, String destId, double montant) {
        if (sourceId.equals(destId)) throw new RuntimeException("Impossible de faire un virement sur le même compte.");
        if (montant <= 0) throw new RuntimeException("Le montant doit être supérieur à 0");

        // Retrait du compte source (gère la vérification du solde)
        retrait(sourceId, montant);
        
        // Dépôt sur le compte destination
        Compte compteDest = obtenirCompte(destId);
        compteDest.setSolde(compteDest.getSolde() + montant);
        compteRepository.save(compteDest);

        // Sauvegarde d'une seule opération de type VIREMENT liant les deux comptes
        Operation op = new Operation("VIREMENT", montant, new Date(), sourceId, destId);
        operationRepository.save(op);
    }

    // Configuration ou mise à jour du crédit (découvert autorisé)
    public void accorderCredit(String id, double montantMax) {
        if (montantMax < 0) throw new RuntimeException("Le plafond de crédit ne peut pas être négatif.");
        Compte compte = obtenirCompte(id);
        compte.setPlafondCredit(montantMax);
        compteRepository.save(compte);

        Operation op = new Operation("AUTORISATION_CREDIT", montantMax, new Date(), id, null);
        operationRepository.save(op);
    }

    // Récupérer l'historique complet des transactions d'un compte
    public List<Operation> obtenirHistorique(String compteId) {
        // On vérifie d'abord si le compte existe
        obtenirCompte(compteId);
        return operationRepository.findByCompteId(compteId);
    }
}