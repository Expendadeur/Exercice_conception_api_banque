package com.banque.controller;

import com.banque.model.Compte;
import com.banque.model.Operation;
import com.banque.service.BanqueService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/banque")
@CrossOrigin("*") // Indispensable pour éviter les blocages CORS lors du déploiement
public class BanqueController {

    @Autowired
    private BanqueService banqueService;

    // 1. Créer un nouveau compte
    @PostMapping("/comptes")
    public ResponseEntity<?> creerCompte(@RequestBody Compte compte) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(banqueService.creerCompte(compte));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 2. Voir le solde et les infos d'un compte
    @GetMapping("/comptes/{id}")
    public ResponseEntity<?> voirSolde(@PathVariable String id) {
        try {
            return ResponseEntity.ok(banqueService.obtenirCompte(id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    // 3. Effectuer un dépôt
    @PostMapping("/depot")
    public ResponseEntity<String> depot(@RequestParam String id, @RequestParam double montant) {
        try {
            banqueService.depot(id, montant);
            return ResponseEntity.ok("Dépôt de " + montant + "€ réussi sur le compte " + id);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 4. Effectuer un retrait
    @PostMapping("/retrait")
    public ResponseEntity<String> retrait(@RequestParam String id, @RequestParam double montant) {
        try {
            banqueService.retrait(id, montant);
            return ResponseEntity.ok("Retrait de " + montant + "€ réussi sur le compte " + id);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 5. Effectuer un virement
    @PostMapping("/virement")
    public ResponseEntity<String> virement(@RequestParam String sourceId, @RequestParam String destId, @RequestParam double montant) {
        try {
            banqueService.virement(sourceId, destId, montant);
            return ResponseEntity.ok("Virement de " + montant + "€ effectué avec succès.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 6. Configurer un plafond de crédit (Découvert)
    @PostMapping("/credit")
    public ResponseEntity<String> configurerCredit(@RequestParam String id, @RequestParam double montantMax) {
        try {
            banqueService.accorderCredit(id, montantMax);
            return ResponseEntity.ok("Plafond de crédit mis à jour à " + montantMax + "€");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 7. Obtenir l'historique complet d'un compte
    @GetMapping("/comptes/{id}/historique")
    public ResponseEntity<?> voirHistorique(@PathVariable String id) {
        try {
            List<Operation> historique = banqueService.obtenirHistorique(id);
            return ResponseEntity.ok(historique);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }
}
