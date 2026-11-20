package com.banque.controller;

import com.banque.model.Compte;
import com.banque.model.EdgeSyncQueue;
import com.banque.model.Operation;
import com.banque.security.ApplicationConsommatrice;
import com.banque.security.ApplicationConsommatriceRepository;
import com.banque.security.ClientPrincipal;
import com.banque.security.EdgeSyncQueueRepository;
import com.banque.security.JwtUtil;
import com.banque.service.BanqueService;
import com.banque.service.ClientAuthException;
import com.banque.service.IaAntiFraudeService;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/banque")
@CrossOrigin("*")
public class BanqueController {

    private static final String ZONE_EDGE = "EDGE_NODE_BUJUMBURA_LOCAL";

    @Autowired private BanqueService banqueService;
    @Autowired private ApplicationConsommatriceRepository appRepository;
    @Autowired private EdgeSyncQueueRepository edgeQueueRepository;
    @Autowired private com.banque.repository.CompteRepository compteRepository;
    @Autowired private com.banque.repository.OperationRepository operationRepository;
    @Autowired private com.banque.service.EdgeCloudSyncService edgeCloudSyncService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private IaAntiFraudeService iaAntiFraudeService;

    // ====================== DTO ======================

    public static class AuthRequest {
        public String clientId;
        public String clientSecret;
    }

    public static class AuthResponse {
        private final String token;
        private final String routingZone;
        public AuthResponse(String token, String routingZone) { this.token = token; this.routingZone = routingZone; }
        public String getToken() { return token; }
        public String getRoutingZone() { return routingZone; }
    }

    public static class ClientLoginRequest {
        public String compteId;
        public String pin;
    }

    public static class ClientLoginResponse {
        private final String token;
        private final String tokenType = "Bearer";
        private final long expiresInSeconds;
        private final String compteId;
        private final String titulaire;
        public ClientLoginResponse(String token, long expiresInSeconds, String compteId, String titulaire) {
            this.token = token; this.expiresInSeconds = expiresInSeconds;
            this.compteId = compteId; this.titulaire = titulaire;
        }
        public String getToken() { return token; }
        public String getTokenType() { return tokenType; }
        public long getExpiresInSeconds() { return expiresInSeconds; }
        public String getCompteId() { return compteId; }
        public String getTitulaire() { return titulaire; }
    }

    public static class ChangerPinRequest {
        public String ancienPin;
        public String nouveauPin;
    }

    public static class MontantRequest {
        public double montant;
    }

    public static class VirementRequest {
        public String destId;
        public double montant;
    }

    public static class CreerCompteRequest {
        public String id;
        public String titulaire;
        public double solde;
        public double plafondCredit;
        public String pin;
    }

    public static class SyncRequest {
        public String compteId;
        public String typeOperation;
        public double montant;
        public String compteDestinationId;
    }

    public static class CompteSyncDto {
        public String id;
        public String titulaire;
        public double solde;
        public double plafondCredit;
        public String codeSecret;
        public int tentativesPinEchouees;
        public long verrouilleJusqua;

        public CompteSyncDto() {}
        public CompteSyncDto(Compte c) {
            this.id = c.getId();
            this.titulaire = c.getTitulaire();
            this.solde = c.getSolde();
            this.plafondCredit = c.getPlafondCredit();
            this.codeSecret = c.getCodeSecret();
            this.tentativesPinEchouees = c.getTentativesPinEchouees();
            this.verrouilleJusqua = c.getVerrouilleJusqua();
        }
    }

    // ====================== Utilitaires ======================

    private String extraireIpClient(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim(); // première IP de la chaîne de proxys
        }
        return ip;
    }

    /** Titulaire authentifié par jeton utilisateur, sinon 403. */
    private ClientPrincipal clientCourant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ClientPrincipal) {
            return (ClientPrincipal) auth.getPrincipal();
        }
        throw new ClientAuthException(HttpStatus.FORBIDDEN, "Jeton titulaire requis.");
    }

    /** Autorisation : un titulaire ne peut accéder qu'à son propre compte. */
    private void verifierPropriete(ClientPrincipal client, String compteId) {
        if (!client.getCompteId().equals(compteId)) {
            throw new ClientAuthException(HttpStatus.FORBIDDEN, "Accès refusé à ce compte.");
        }
    }

    @ExceptionHandler(ClientAuthException.class)
    public ResponseEntity<String> handleClientAuth(ClientAuthException e) {
        return ResponseEntity.status(e.getStatus()).body(e.getMessage());
    }

    // ====================== 1. Authentification B2B (application) ======================

    @PostMapping("/auth")
    public ResponseEntity<?> authentifierApplication(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        if (request == null || request.clientId == null || request.clientSecret == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Identifiants de l'application incorrects.");
        }
        ApplicationConsommatrice app = appRepository.findById(request.clientId).orElse(null);

        if (app != null && passwordEncoder.matches(request.clientSecret.trim(), app.getClientSecret().trim())) {
            String zone = iaAntiFraudeService.geolocaliserIpClient(extraireIpClient(httpRequest));
            String token = jwtUtil.generateToken(app.getClientId(), app.getRole());
            return ResponseEntity.ok(new AuthResponse(token, zone));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Identifiants de l'application incorrects.");
    }

    // ====================== 2. Espace titulaire : login / me / logout / changer-pin ======================

    /** Appelé par une application B2B authentifiée (jeton APP) pour le compte d'un titulaire. */
    @PostMapping("/client/login")
    public ResponseEntity<?> loginClient(@RequestBody ClientLoginRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String appId = auth.getName();

        Compte compte = banqueService.authentifierTitulaire(req.compteId, req.pin); // ClientAuthException -> 401/423
        String token = jwtUtil.generateClientToken(compte.getId(), appId);
        return ResponseEntity.ok(new ClientLoginResponse(
                token, jwtUtil.getClientExpirationSeconds(), compte.getId(), compte.getTitulaire()));
    }

    /**
     * Profil + comptes autorisés. Le modèle actuel n'a pas d'entité "Client" :
     * un titulaire correspond à un seul compte, donc la liste contient ce compte.
     */
    @GetMapping("/client/me")
    public ResponseEntity<?> moi() {
        ClientPrincipal client = clientCourant();
        try {
            Compte compte = banqueService.consulterCompte(client.getCompteId());

            Map<String, Object> profil = new HashMap<>();
            profil.put("titulaire", compte.getTitulaire());
            profil.put("compteId", compte.getId());

            Map<String, Object> infoCompte = new HashMap<>();
            infoCompte.put("id", compte.getId());
            infoCompte.put("solde", compte.getSolde());
            infoCompte.put("plafondCredit", compte.getPlafondCredit());

            Map<String, Object> reponse = new HashMap<>();
            reponse.put("profil", profil);
            reponse.put("comptes", Collections.singletonList(infoCompte));
            return ResponseEntity.ok(reponse);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    /** Révoque le jeton courant (liste noire par jti jusqu'à son expiration). */
    @PostMapping("/client/logout")
    public ResponseEntity<String> logout() {
        ClientPrincipal client = clientCourant();
        jwtUtil.revoquer(client.getJti(), client.getExpiration());
        return ResponseEntity.ok("Déconnexion effectuée.");
    }

    /** PIN dans un corps JSON (HTTPS obligatoire en production), compte déduit du jeton. */
    @PostMapping("/client/changer-pin")
    public ResponseEntity<String> changerCodeSecret(@RequestBody ChangerPinRequest req) {
        ClientPrincipal client = clientCourant();
        try {
            banqueService.changerPin(client.getCompteId(), req.ancienPin, req.nouveauPin);
            jwtUtil.revoquer(client.getJti(), client.getExpiration()); // force une reconnexion
            return ResponseEntity.ok("Succès : code secret mis à jour. Veuillez vous reconnecter.");
        } catch (ClientAuthException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ====================== 3. Opérations (titulaire authentifié) ======================

    @PostMapping("/depot")
    public ResponseEntity<String> depot(@RequestBody MontantRequest req, HttpServletRequest httpRequest) {
        return executerOperation("DEPOT", clientCourant(), null, req.montant, httpRequest);
    }

    @PostMapping("/retrait")
    public ResponseEntity<String> retrait(@RequestBody MontantRequest req, HttpServletRequest httpRequest) {
        return executerOperation("RETRAIT", clientCourant(), null, req.montant, httpRequest);
    }

    @PostMapping("/virement")
    public ResponseEntity<String> virement(@RequestBody VirementRequest req, HttpServletRequest httpRequest) {
        return executerOperation("VIREMENT", clientCourant(), req.destId, req.montant, httpRequest);
    }

    private ResponseEntity<String> executerOperation(String type, ClientPrincipal client, String destId,
                                                     double montant, HttpServletRequest httpRequest) {
        try {
            String compteId = client.getCompteId(); // source = toujours le compte du jeton
            if (!Double.isFinite(montant) || montant <= 0) {
                return ResponseEntity.badRequest().body("Le montant doit être un nombre supérieur à 0");
            }
            if ("VIREMENT".equals(type) && (destId == null || destId.trim().isEmpty())) {
                return ResponseEntity.badRequest().body("Le compte destinataire est obligatoire.");
            }

            // 🤖 Anti-fraude (clé de limitation = compte du titulaire)
            Map<String, Object> contexte = new HashMap<>();
            if (iaAntiFraudeService.analyserSuspicionFraude(compteId, type, montant, contexte)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("[BLOCAGE IA] " + type + " rejeté pour suspicion de fraude.");
            }

            String zone = iaAntiFraudeService.geolocaliserIpClient(extraireIpClient(httpRequest));

            // 1. Exécution de l'opération en base locale (met à jour immédiatement les soldes et l'historique)
            switch (type) {
                case "DEPOT":   banqueService.depot(compteId, montant); break;
                case "RETRAIT": banqueService.retrait(compteId, montant); break;
                default:        banqueService.virement(compteId, destId, montant);
            }

            // 2. Si l'opération a été traitée sur le nœud Edge, la placer dans la file d'attente pour synchronisation vers Render
            if (ZONE_EDGE.equals(zone)) {
                EdgeSyncQueue tx = "VIREMENT".equals(type)
                        ? new EdgeSyncQueue(compteId, type, montant, destId)
                        : new EdgeSyncQueue(compteId, type, montant);
                edgeQueueRepository.save(tx);
                return ResponseEntity.ok("[EDGE LOCAL] " + type + " de " + montant
                        + " BIF exécuté en base locale et mis en file d'attente pour synchronisation vers Render.");
            }

            return ResponseEntity.ok("[CLOUD CENTRAL] " + type + " de " + montant + " BIF traité en base centrale.");
        } catch (ClientAuthException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ====================== 4. Synchronisation Bidirectionnelle Edge <-> Cloud ======================

    @PostMapping("/sync/operation")
    public ResponseEntity<String> synchroniser(@RequestBody SyncRequest req) {
        try {
            if (req.typeOperation == null) return ResponseEntity.badRequest().body("typeOperation manquant.");
            switch (req.typeOperation) {
                case "DEPOT":    banqueService.depot(req.compteId, req.montant); break;
                case "RETRAIT":  banqueService.retrait(req.compteId, req.montant); break;
                case "VIREMENT": banqueService.virement(req.compteId, req.compteDestinationId, req.montant); break;
                default: return ResponseEntity.badRequest().body("Type d'opération inconnu.");
            }
            return ResponseEntity.ok("Opération synchronisée.");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/sync/comptes")
    public ResponseEntity<List<CompteSyncDto>> synchroniserComptes() {
        List<Compte> comptes = compteRepository.findAll();
        List<CompteSyncDto> dtoList = comptes.stream()
                .map(CompteSyncDto::new)
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(dtoList);
    }

    @GetMapping("/sync/operations")
    public ResponseEntity<List<Operation>> synchroniserOperations() {
        return ResponseEntity.ok(operationRepository.findAll());
    }

    @PostMapping("/sync/compte")
    public ResponseEntity<?> synchroniserCompte(@RequestBody CompteSyncDto dto) {
        try {
            if (dto.id == null || dto.id.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("ID de compte manquant.");
            }
            Compte compte = compteRepository.findById(dto.id).orElse(null);
            if (compte == null) {
                compte = new Compte(dto.id, dto.titulaire, dto.solde, dto.plafondCredit);
                compte.setCodeSecret(dto.codeSecret != null ? dto.codeSecret : "");
                compte.setTentativesPinEchouees(dto.tentativesPinEchouees);
                compte.setVerrouilleJusqua(dto.verrouilleJusqua);
                compteRepository.save(compte);
                return ResponseEntity.status(HttpStatus.CREATED).body("Compte " + dto.id + " créé via synchronisation.");
            } else {
                compte.setTitulaire(dto.titulaire);
                compte.setSolde(dto.solde);
                compte.setPlafondCredit(dto.plafondCredit);
                if (dto.codeSecret != null && !dto.codeSecret.isEmpty()) {
                    compte.setCodeSecret(dto.codeSecret);
                }
                compteRepository.save(compte);
                return ResponseEntity.ok("Compte " + dto.id + " mis à jour via synchronisation.");
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Erreur synchro compte: " + e.getMessage());
        }
    }

    // ====================== 5. Consultation / administration ======================

    /** Réservé aux applications B2B (voir SecurityConfig). Le PIN est haché par le service. */
    @PostMapping("/comptes")
    public ResponseEntity<?> creerCompte(@RequestBody CreerCompteRequest req, HttpServletRequest httpRequest) {
        try {
            Compte compte = new Compte(req.id, req.titulaire, req.solde, req.plafondCredit);
            Compte cree = banqueService.creerCompte(compte, req.pin);

            String zone = iaAntiFraudeService.geolocaliserIpClient(extraireIpClient(httpRequest));
            if (ZONE_EDGE.equals(zone)) {
                edgeCloudSyncService.synchroniserCompteVersCloud(cree);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(cree);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/comptes/{id}")
    public ResponseEntity<?> voirSolde(@PathVariable String id) {
        verifierPropriete(clientCourant(), id);
        try {
            return ResponseEntity.ok(banqueService.consulterCompte(id));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    @PostMapping("/credit")
    public ResponseEntity<String> configurerCredit(@RequestParam String id, @RequestParam double montantMax) {
        try {
            banqueService.accorderCredit(id, montantMax);
            return ResponseEntity.ok("Crédit mis à jour.");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/comptes/{id}/historique")
    public ResponseEntity<?> voirHistorique(@PathVariable String id) {
        verifierPropriete(clientCourant(), id);
        try {
            return ResponseEntity.ok(banqueService.obtenirHistorique(id));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }
}

/*
package com.banque.controller;

import com.banque.model.Compte;
import com.banque.model.Operation;
import com.banque.service.BanqueService;
import com.banque.service.IaAntiFraudeService;
import com.banque.security.ApplicationConsommatrice;
import com.banque.security.ApplicationConsommatriceRepository;
import com.banque.security.JwtUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
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

    @Autowired
    private IaAntiFraudeService iaAntiFraudeService; // Injection de l'IA DevSecOps

    public static class AuthRequest {
        public String clientId;
        public String clientSecret;
    }

    public static class AuthResponse {
        private String token;
        private String routingZone; // Ajout pour la traçabilité Edge Cloud
        public AuthResponse(String token, String routingZone) { this.token = token; this.routingZone = routingZone; }
        public String getToken() { return token; }
        public String getRoutingZone() { return routingZone; }
    }

    // 1. Authentification avec routage dynamique Edge Cloud
    @PostMapping("/auth")
    public ResponseEntity<?> authentifierApplication(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        ApplicationConsommatrice app = appRepository.findById(request.clientId).orElse(null);

        if (app != null && passwordEncoder.matches(request.clientSecret.trim(), app.getClientSecret().trim())) {
            
            // Simulation Edge Cloud : Analyse de l'origine de la requête
            String edgeHeader = httpRequest.getHeader("X-Edge-Node");
            String zoneDeRoutage = (edgeHeader != null) ? "EDGE_NODE_BUJUMBURA_LOCAL" : "CLOUD_CENTRAL_RENDER_EUROPE";

            String token = jwtUtil.generateToken(app.getClientId(), app.getRole());
            return ResponseEntity.ok(new AuthResponse(token, zoneDeRoutage));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Identifiants incorrects.");
    }

    // 2. Effectuer un retrait protégé par l'IA en Edge
    @PostMapping("/retrait")
    public ResponseEntity<String> retrait(@RequestParam String id, @RequestParam double montant) {
        try {
            String appName = SecurityContextHolder.getContext().getAuthentication().getName();

            // SÉCURITÉ DEVSECOPS PAR IA : Interception comportementale avant traitement
            boolean estUneFraude = iaAntiFraudeService.analyserSuspicionFraude(appName, "RETRAIT", montant);
            if (estUneFraude) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("[BLOCAGE IA] Transaction rejetée automatiquement pour suspicion de fraude ou anomalie de fréquence.");
            }

            banqueService.retrait(id, montant);
            return ResponseEntity.ok("Retrait validé par l'application : " + appName);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // --- Les autres méthodes restent identiques à votre version précédente ---
    @PostMapping("/comptes")
    public ResponseEntity<?> creerCompte(@RequestBody Compte compte) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(banqueService.creerCompte(compte));
        } catch (Exception e) { return ResponseEntity.badRequest().body(e.getMessage()); }
    }

    @GetMapping("/comptes/{id}")
    public ResponseEntity<?> voirSolde(@PathVariable String id) {
        try { return ResponseEntity.ok(banqueService.obtenirCompte(id)); } 
        catch (Exception e) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage()); }
    }

    @PostMapping("/depot")
    public ResponseEntity<String> depot(@RequestParam String id, @RequestParam double montant) {
        try { banqueService.depot(id, montant); return ResponseEntity.ok("Dépôt réussi"); } 
        catch (Exception e) { return ResponseEntity.badRequest().body(e.getMessage()); }
    }

    @PostMapping("/virement")
    public ResponseEntity<String> virement(@RequestParam String sourceId, @RequestParam String destId, @RequestParam double montant) {
        try { banqueService.virement(sourceId, destId, montant); return ResponseEntity.ok("Virement réussi"); } 
        catch (Exception e) { return ResponseEntity.badRequest().body(e.getMessage()); }
    }

    @PostMapping("/credit")
    public ResponseEntity<String> configurerCredit(@RequestParam String id, @RequestParam double montantMax) {
        try { banqueService.accorderCredit(id, montantMax); return ResponseEntity.ok("Crédit mis à jour."); } 
        catch (Exception e) { return ResponseEntity.badRequest().body(e.getMessage()); }
    }

    @GetMapping("/comptes/{id}/historique")
    public ResponseEntity<?> voirHistorique(@PathVariable String id) {
        try { return ResponseEntity.ok(banqueService.obtenirHistorique(id)); } 
        catch (Exception e) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage()); }
    }
}

/*package com.banque.controller;

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

   @PostMapping("/auth")
    public ResponseEntity<?> authentifierApplication(@RequestBody AuthRequest request) {
        ApplicationConsommatrice app = appRepository.findById(request.clientId).orElse(null);

        if (app == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Application introuvable en BDD.");
        }

        // LE RETOUR DU VRAI BCRYPT (Sécurisé et aligné avec votre BDD)
        if (passwordEncoder.matches(request.clientSecret.trim(), app.getClientSecret().trim())) {
            String token = jwtUtil.generateToken(app.getClientId(), app.getRole());
            return ResponseEntity.ok(new AuthResponse(token));
        }
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Mot de passe invalide pour cette application.");
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
}*/