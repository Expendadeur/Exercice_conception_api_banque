package com.banque.controller;

import com.banque.model.Compte;
import com.banque.model.Operation;
import com.banque.service.BanqueService;
import com.banque.security.ApplicationConsommatrice;
import com.banque.security.ApplicationConsommatriceRepository;
import com.banque.security.JwtUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/banque")
@CrossOrigin("*")
public class BanqueController {

    @Autowired
    private BanqueService banqueService;

    @Autowired
    private ApplicationConsommatriceRepository appRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    // Structure interne pour recevoir les requêtes d'authentification des applications
    public static class AuthRequest {
        public String clientId;
        public String clientSecret;
    }

    // Structure interne indispensable pour renvoyer le Jeton au format JSON valide
    public static class AuthResponse {
        private String token;
        public AuthResponse(String token) { this.token = token; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    // 1. Point d'accès public d'authentification B2B (CORRIGÉ)
    @PostMapping("/auth")
    public ResponseEntity<?> authentifierApplication(@RequestBody AuthRequest request) {
        // CORRECTION : Utilisation de la vraie méthode Java 8 .orElse(null)
        ApplicationConsommatrice app = appRepository.findByClientId(request.clientId).orElse(null);

        if (app != null && passwordEncoder.matches(request.clientSecret, app.getClientSecret())) {
            String token = jwtUtil.generateToken(app.getClientId(), app.getRole());
            return ResponseEntity.ok(new AuthResponse(token));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Identifiants de l'application incorrects.");
    }

    // 2. Créer un nouveau compte bancaire (Soumis à validation JWT du consommateur)
    @PostMapping("/comptes")
    public ResponseEntity<?> creerCompte(@RequestBody Compte compte) {
        try {
            String appName = SecurityContextHolder.getContext().getAuthentication().getName();
            System.out.println("Compte créé par le consommateur système : " + appName);
            return ResponseEntity.status(HttpStatus.CREATED).body(banqueService.creerCompte(compte));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 3. Voir le solde et les infos d'un compte
    @GetMapping("/comptes/{id}")
    public ResponseEntity<?> voirSolde(@PathVariable String id) {
        try {
            return ResponseEntity.ok(banqueService.obtenirCompte(id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    // 4. Effectuer un dépôt
    @PostMapping("/depot")
    public ResponseEntity<String> depot(@RequestParam String id, @RequestParam double montant) {
        try {
            String appName = SecurityContextHolder.getContext().getAuthentication().getName();
            banqueService.depot(id, montant);
            return ResponseEntity.ok("Dépôt traité avec succès par l'application : " + appName);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 5. Effectuer un retrait
    @PostMapping("/retrait")
    public ResponseEntity<String> retrait(@RequestParam String id, @RequestParam double montant) {
        try {
            String appName = SecurityContextHolder.getContext().getAuthentication().getName();
            banqueService.retrait(id, montant);
            return ResponseEntity.ok("Retrait validé par l'application partenaire : " + appName);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 6. Effectuer un virement
    @PostMapping("/virement")
    public ResponseEntity<String> virement(@RequestParam String sourceId, @RequestParam String destId, @RequestParam double montant) {
        try {
            String appName = SecurityContextHolder.getContext().getAuthentication().getName();
            banqueService.virement(sourceId, destId, montant);
            return ResponseEntity.ok("Virement orchestré par l'application : " + appName);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 7. Configurer un plafond de crédit (Découvert)
    @PostMapping("/credit")
    public ResponseEntity<String> configurerCredit(@RequestParam String id, @RequestParam double montantMax) {
        try {
            banqueService.accorderCredit(id, montantMax);
            return ResponseEntity.ok("Plafond de crédit mis à jour.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 8. Obtenir l'historique complet d'un compte
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