package com.banque.service;

import com.banque.model.Compte;
import com.banque.model.Operation;
import com.banque.model.EchecConnexion;
import com.banque.repository.CompteRepository;
import com.banque.repository.EchecConnexionRepository;
import com.banque.repository.OperationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BanqueService {

    private static final int MAX_TENTATIVES = 5;
    private static final long DUREE_VERROUILLAGE_MS = 15 * 60 * 1000L;
    private static final String MSG_ECHEC = "Identifiants invalides.";
    private static final Pattern PIN_FORMAT = Pattern.compile("\\d{4,6}");

    @Autowired
    private CompteRepository compteRepository;

    @Autowired
    private OperationRepository operationRepository;

    @Autowired
    private EchecConnexionRepository echecConnexionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private volatile String dummyHash;

    // ---------- Comptes ----------

    /** Lecture avec verrou (utilisée par les opérations qui modifient le solde). */
    public Compte obtenirCompte(String id) {
        return compteRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RuntimeException("Erreur : Le compte " + id + " n'existe pas."));
    }

    /** Lecture simple, sans verrou (consultation). */
    @Transactional(readOnly = true)
    public Compte consulterCompte(String id) {
        return compteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Erreur : Le compte " + id + " n'existe pas."));
    }

    public Compte creerCompte(Compte compte, String pinClair) {
        if (compte.getId() == null || compte.getId().trim().isEmpty())
            throw new RuntimeException("Erreur : Le numéro de compte est obligatoire.");
        validerFormatPin(pinClair);
        if (!Double.isFinite(compte.getSolde()) || compte.getSolde() < 0
                || !Double.isFinite(compte.getPlafondCredit()) || compte.getPlafondCredit() < 0)
            throw new RuntimeException("Erreur : Solde ou plafond de crédit invalide.");
        if (compteRepository.existsById(compte.getId()))
            throw new RuntimeException("Erreur : Ce numéro de compte existe déjà.");

        compte.setCodeSecret(passwordEncoder.encode(pinClair.trim())); // hash BCrypt
        compte.setTentativesPinEchouees(0);
        compte.setVerrouilleJusqua(0L);
        return compteRepository.save(compte);
    }

    // ---------- Authentification du titulaire (PIN haché) ----------

    /**
     * Vérifie le PIN avec BCrypt, gère le verrouillage anti brute-force.
     * noRollbackFor : le compteur d'échecs doit être conservé même si on lève l'exception.
     */
    @Transactional(noRollbackFor = ClientAuthException.class)
    public Compte authentifierTitulaire(String compteId, String pin) {
        long maintenant = System.currentTimeMillis();
        Compte compte = (compteId == null) ? null : compteRepository.findByIdForUpdate(compteId).orElse(null);

        if (compte == null || pin == null) {
            // Dépense un temps BCrypt équivalent pour ne pas révéler si le compte existe
            passwordEncoder.matches("dummy", dummyHash());
            throw new ClientAuthException(HttpStatus.UNAUTHORIZED, MSG_ECHEC);
        }
        if (compte.getVerrouilleJusqua() > maintenant) {
            throw new ClientAuthException(HttpStatus.LOCKED,
                    "Compte temporairement verrouillé suite à trop d'échecs. Réessayez plus tard.");
        }

        if (!pinCorrespond(compte, pin.trim())) {
            int n = compte.getTentativesPinEchouees() + 1;
            if (n >= MAX_TENTATIVES) {
                compte.setVerrouilleJusqua(maintenant + DUREE_VERROUILLAGE_MS);
                n = 0;
            }
            compte.setTentativesPinEchouees(n);
            compteRepository.save(compte);
            echecConnexionRepository.save(new EchecConnexion(compteId, new Date())); // variable echecs_connexion_24h
            throw new ClientAuthException(HttpStatus.UNAUTHORIZED, MSG_ECHEC);
        }

        compte.setTentativesPinEchouees(0);
        compte.setVerrouilleJusqua(0L);
        return compteRepository.save(compte);
    }

    @Transactional(noRollbackFor = ClientAuthException.class)
    public void changerPin(String compteId, String ancienPin, String nouveauPin) {
        validerFormatPin(nouveauPin);
        if (ancienPin != null && nouveauPin.trim().equals(ancienPin.trim()))
            throw new RuntimeException("Erreur : Le nouveau code secret doit être différent de l'ancien.");

        Compte compte = authentifierTitulaire(compteId, ancienPin);
        compte.setCodeSecret(passwordEncoder.encode(nouveauPin.trim()));
        compteRepository.save(compte);
    }

    private boolean pinCorrespond(Compte compte, String pin) {
        String stocke = compte.getCodeSecret();
        if (stocke == null) return false;
        if (stocke.startsWith("$2")) { // hash BCrypt
            return passwordEncoder.matches(pin, stocke);
        }
        // Migration : ancien PIN en clair -> comparaison à temps constant puis re-hachage
        boolean ok = MessageDigest.isEqual(stocke.getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8));
        if (ok) compte.setCodeSecret(passwordEncoder.encode(pin));
        return ok;
    }

    private void validerFormatPin(String pin) {
        if (pin == null || !PIN_FORMAT.matcher(pin.trim()).matches())
            throw new RuntimeException("Erreur : Le code secret doit contenir 4 à 6 chiffres.");
    }

    private String dummyHash() {
        if (dummyHash == null) dummyHash = passwordEncoder.encode("dummy-pin");
        return dummyHash;
    }

    // ---------- Opérations ----------

    private void validerMontant(double montant) {
        if (!Double.isFinite(montant) || montant <= 0)
            throw new RuntimeException("Le montant doit être un nombre supérieur à 0");
    }

    public void depot(String id, double montant) {
        validerMontant(montant);
        Compte compte = obtenirCompte(id);
        compte.setSolde(compte.getSolde() + montant);
        compteRepository.save(compte);
        operationRepository.save(new Operation("DEPOT", montant, new Date(), id, null));
    }

    public void retrait(String id, double montant) {
        validerMontant(montant);
        Compte compte = obtenirCompte(id);
        if (compte.getSolde() + compte.getPlafondCredit() < montant)
            throw new RuntimeException("Solde et crédit insuffisants pour effectuer ce retrait.");
        compte.setSolde(compte.getSolde() - montant);
        compteRepository.save(compte);
        operationRepository.save(new Operation("RETRAIT", montant, new Date(), id, null));
    }

    public void virement(String sourceId, String destId, double montant) {
        if (sourceId.equals(destId)) throw new RuntimeException("Impossible de faire un virement sur le même compte.");
        validerMontant(montant);

        // Verrous pris dans un ordre déterministe pour éviter les interblocages (A->B et B->A simultanés)
        boolean sourceEnPremier = sourceId.compareTo(destId) < 0;
        Compte premier = obtenirCompte(sourceEnPremier ? sourceId : destId);
        Compte second = obtenirCompte(sourceEnPremier ? destId : sourceId);
        Compte source = sourceEnPremier ? premier : second;
        Compte dest = sourceEnPremier ? second : premier;

        if (source.getSolde() + source.getPlafondCredit() < montant)
            throw new RuntimeException("Solde et crédit insuffisants pour effectuer ce virement.");

        source.setSolde(source.getSolde() - montant);
        dest.setSolde(dest.getSolde() + montant);
        compteRepository.save(source);
        compteRepository.save(dest);

        // Une seule opération VIREMENT (l'ancien code en créait aussi une RETRAIT parasite)
        operationRepository.save(new Operation("VIREMENT", montant, new Date(), sourceId, destId));
    }

    public void accorderCredit(String id, double montantMax) {
        if (!Double.isFinite(montantMax) || montantMax < 0)
            throw new RuntimeException("Le plafond de crédit ne peut pas être négatif.");
        Compte compte = obtenirCompte(id);
        compte.setPlafondCredit(montantMax);
        compteRepository.save(compte);
        operationRepository.save(new Operation("AUTORISATION_CREDIT", montantMax, new Date(), id, null));
    }

    @Transactional(readOnly = true)
    public List<Operation> obtenirHistorique(String compteId) {
        consulterCompte(compteId);
        return operationRepository.findByCompteId(compteId);
    }
}