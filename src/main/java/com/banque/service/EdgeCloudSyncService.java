package com.banque.service;

import com.banque.model.Compte;
import com.banque.model.EdgeSyncQueue;
import com.banque.model.Operation;
import com.banque.security.EdgeSyncQueueRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Synchronisation Edge <-> Cloud. CE SERVICE TOURNE SUR LE NŒUD EDGE (le PC) : c'est toujours l'Edge qui appelle
 * le Cloud (le Cloud ne peut pas joindre une adresse privée comme 10.x.x.x).
 *
 *  - toutes les 10 s : Edge -> Cloud, opérations PENDING de la file (POST /sync/operation)
 *  - toutes les 20 s : Cloud -> Edge, comptes et opérations (GET /sync/comptes, /sync/operations),
 *                      et envoi vers le Cloud des comptes locaux absents du Cloud (POST /sync/compte)
 *
 * À définir UNIQUEMENT sur le nœud Edge : cloud.url, edge.sync.client-id, edge.sync.client-secret.
 * Sur le Cloud, ne rien définir : le service reste inactif.
 * Les PIN ne sont JAMAIS rejoués : l'opération a déjà été autorisée à l'Edge par le titulaire.
 */
@Service
public class EdgeCloudSyncService {

    @Autowired
    private EdgeSyncQueueRepository queueRepository;

    @Autowired
    private com.banque.repository.CompteRepository compteRepository;

    @Autowired
    private com.banque.repository.OperationRepository operationRepository;

    @Value("${cloud.url:https://api-banque-expendadeur.onrender.com}")
    private String cloudUrl;

    @Value("${edge.sync.client-id:}")
    private String syncClientId;

    @Value("${edge.sync.client-secret:}")
    private String syncClientSecret;

    /** Délai maximal d'un appel au Cloud (un Cloud en veille peut mettre longtemps à répondre). */
    @Value("${edge.sync.timeout-ms:20000}")
    private int timeoutMs;

    private RestTemplate restTemplate;
    private volatile String cloudToken;

    /** Dernier état journalisé par tâche : on n'écrit dans la console que lors d'un changement. */
    private final Map<String, String> etats = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeoutMs);
        f.setReadTimeout(timeoutMs);
        restTemplate = new RestTemplate(f);

        String raison = raisonInactive(true);
        if (raison == null) {
            System.out.println("[SYNC] ACTIVE -> Cloud : " + base());
        } else {
            System.err.println("[SYNC] INACTIVE : " + raison);
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private String base() {
        String u = (cloudUrl == null) ? "" : cloudUrl.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** null si la synchro peut tourner, sinon la raison (affichée dans la console). */
    private String raisonInactive(boolean bloquerLocalhost) {
        if (base().isEmpty()) return "cloud.url non défini";
        String u = base().toLowerCase();
        if (bloquerLocalhost && (u.contains("localhost") || u.contains("127.0.0.1"))) {
            return "cloud.url pointe sur cette machine (" + base() + ")";
        }
        if (syncClientId == null || syncClientId.trim().isEmpty()
                || syncClientSecret == null || syncClientSecret.trim().isEmpty()) {
            return "edge.sync.client-id / edge.sync.client-secret non définis";
        }
        return null;
    }

    private void etat(String tache, String cle, String message) {
        String ancien = etats.put(tache, cle);
        if (!cle.equals(ancien)) {
            if ("OK".equals(cle)) System.out.println(message); else System.err.println(message);
        }
    }

    private HttpHeaders entetesAuth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cloudToken);
        return headers;
    }

    private String obtenirJeton() {
        Map<String, String> body = new HashMap<>();
        body.put("clientId", syncClientId.trim());
        body.put("clientSecret", syncClientSecret.trim());
        Map<?, ?> rep = restTemplate.postForObject(base() + "/api/banque/auth", body, Map.class);
        if (rep == null || rep.get("token") == null) throw new RestClientException("Jeton cloud absent de la réponse");
        return (String) rep.get("token");
    }

    // ------------------------------------------------------------------ Edge -> Cloud : file d'attente

    @Scheduled(fixedDelay = 10000)
    public void synchroniserEdgeVersCloud() {
        List<EdgeSyncQueue> enAttente = queueRepository.findByStatutSynchronisation("PENDING");
        if (enAttente.isEmpty()) return;

        String raison = raisonInactive(false);
        if (raison != null) {
            etat("edge->cloud", raison, "[SYNC Edge->Cloud] " + enAttente.size()
                    + " opération(s) en attente mais synchro INACTIVE : " + raison);
            return;
        }

        // Respect de l'ordre chronologique (un dépôt doit précéder le retrait qui en dépend)
        enAttente.sort(Comparator.comparing(EdgeSyncQueue::getId));
        System.out.println("[SYNC Edge->Cloud] " + enAttente.size() + " opération(s) à synchroniser.");

        for (EdgeSyncQueue tx : enAttente) {
            try {
                if (cloudToken == null) cloudToken = obtenirJeton();
                envoyer(tx);
                tx.setStatutSynchronisation("SYNCED");
                etat("edge->cloud", "OK", "[SYNC Edge->Cloud] Cloud joignable : file synchronisée.");
            } catch (HttpClientErrorException e) {
                int code = e.getStatusCode().value();
                if (code == 401) {
                    cloudToken = null; // jeton expiré ou identifiants refusés : on réessaiera au prochain cycle
                    etat("edge->cloud", "HTTP401", "[SYNC Edge->Cloud] HTTP 401 : jeton ou identifiants de synchro refusés par le Cloud.");
                    return;
                }
                if (code == 403 || code == 404 || code == 405) {
                    // Problème de configuration / de version du Cloud : l'opération n'est PAS perdue, elle reste PENDING
                    etat("edge->cloud", "HTTP" + code, "[SYNC Edge->Cloud] HTTP " + code
                            + " sur /sync/operation (rôle absent ou Cloud sur une ancienne version). Opérations conservées en PENDING.");
                    return;
                }
                // Autre 4xx = rejet métier (solde insuffisant, compte inconnu...) : inutile de réessayer
                tx.setStatutSynchronisation("FAILED");
                System.err.println("[SYNC Edge->Cloud] Rejet définitif tx " + tx.getId() + " : " + e.getResponseBodyAsString());
            } catch (RestClientException e) {
                // Réseau / 5xx : reste PENDING, on stoppe le cycle pour préserver l'ordre
                etat("edge->cloud", "reseau", "[SYNC Edge->Cloud] Cloud injoignable, nouvel essai plus tard : " + e.getMessage());
                return;
            }
            queueRepository.save(tx);
        }
    }

    private void envoyer(EdgeSyncQueue tx) {
        Map<String, Object> body = new HashMap<>();
        body.put("compteId", tx.getCompteId());
        body.put("typeOperation", tx.getTypeOperation().toUpperCase());
        body.put("montant", tx.getMontant());
        body.put("compteDestinationId", tx.getCompteDestinationId());

        restTemplate.postForEntity(base() + "/api/banque/sync/operation",
                new HttpEntity<>(body, entetesAuth()), String.class);
    }

    // ------------------------------------------------------------------ Edge -> Cloud : un compte

    /**
     * Pousse un compte local vers le Cloud (Edge -> Cloud).
     * Appelé à la création d'un compte sur le nœud Edge et par la réconciliation Cloud <-> Edge.
     * @return true si le Cloud a accepté le compte
     */
    public boolean synchroniserCompteVersCloud(Compte compte) {
        if (raisonInactive(true) != null) return false;
        try {
            if (cloudToken == null) cloudToken = obtenirJeton();

            Map<String, Object> body = new HashMap<>();
            body.put("id", compte.getId());
            body.put("titulaire", compte.getTitulaire());
            body.put("solde", compte.getSolde());
            body.put("plafondCredit", compte.getPlafondCredit());
            body.put("codeSecret", compte.getCodeSecret());
            body.put("tentativesPinEchouees", compte.getTentativesPinEchouees());
            body.put("verrouilleJusqua", compte.getVerrouilleJusqua());

            restTemplate.postForEntity(base() + "/api/banque/sync/compte",
                    new HttpEntity<>(body, entetesAuth()), String.class);
            System.out.println("[SYNC Edge->Cloud] Compte " + compte.getId() + " synchronisé vers le Cloud.");
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) cloudToken = null;
            System.err.println("[SYNC Edge->Cloud] Rejet compte " + compte.getId() + " : HTTP "
                    + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            System.err.println("[SYNC Edge->Cloud] Cloud injoignable pour compte " + compte.getId() + " : " + e.getMessage());
        }
        return false;
    }

    // ------------------------------------------------------------------ Cloud -> Edge : comptes et opérations

    /**
     * Réplique les comptes et opérations du Cloud vers la base locale, et pousse vers le Cloud les comptes
     * locaux qui lui manquent. Chaque échec est journalisé (une seule fois tant que l'erreur ne change pas).
     */
    @Scheduled(fixedDelay = 20000)
    public void synchroniserCloudVersEdge() {
        String raison = raisonInactive(true);
        if (raison != null) {
            etat("cloud->edge", raison, "[SYNC Cloud->Edge] INACTIVE : " + raison);
            return;
        }

        try {
            if (cloudToken == null) cloudToken = obtenirJeton();
            HttpEntity<Void> requete = new HttpEntity<>(entetesAuth());

            // ─── 1. comptes (Cloud -> Edge) ───
            List<Map<String, Object>> comptesDuCloud;
            try {
                ResponseEntity<List<Map<String, Object>>> rep = restTemplate.exchange(
                        base() + "/api/banque/sync/comptes", HttpMethod.GET, requete,
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {});
                comptesDuCloud = (rep.getBody() == null) ? new ArrayList<Map<String, Object>>() : rep.getBody();
            } catch (HttpClientErrorException e) {
                int code = e.getStatusCode().value();
                if (code == 401) cloudToken = null;
                String aide = (code == 404) ? " (route absente : le Cloud exécute probablement une ancienne version)"
                            : (code == 403) ? " (le rôle de l'application de synchro n'est pas accepté par le Cloud)"
                            : (code == 401) ? " (identifiants de synchro refusés)" : "";
                etat("cloud->edge", "HTTP" + code, "[SYNC Cloud->Edge] GET /sync/comptes refusé : HTTP " + code + aide);
                return;
            } catch (RestClientException e) {
                etat("cloud->edge", "reseau", "[SYNC Cloud->Edge] Cloud injoignable : " + e.getMessage());
                return;
            }

            // Comptes ayant des opérations locales en attente : on ne touche pas à leur solde local
            Set<String> concernes = new HashSet<>();
            for (EdgeSyncQueue q : queueRepository.findByStatutSynchronisation("PENDING")) {
                if (q.getCompteId() != null) concernes.add(q.getCompteId());
                if (q.getCompteDestinationId() != null) concernes.add(q.getCompteDestinationId());
            }

            int importes = 0, majs = 0, pousses = 0, ops = 0;
            Set<String> idsCloud = new HashSet<>();

            for (Map<String, Object> map : comptesDuCloud) {
                String id = (String) map.get("id");
                if (id == null) continue;
                idsCloud.add(id);

                double soldeCloud = ((Number) map.get("solde")).doubleValue();
                double plafondCloud = ((Number) map.get("plafondCredit")).doubleValue();
                String titulaire = (String) map.get("titulaire");
                String codeSecret = (String) map.get("codeSecret");

                Compte local = compteRepository.findById(id).orElse(null);
                if (local == null) {
                    Compte nouveau = new Compte(id, titulaire, soldeCloud, plafondCloud);
                    nouveau.setCodeSecret(codeSecret != null ? codeSecret : "");
                    compteRepository.save(nouveau);
                    importes++;
                } else if (!concernes.contains(id)
                        && (local.getSolde() != soldeCloud || local.getPlafondCredit() != plafondCloud)) {
                    local.setSolde(soldeCloud);
                    local.setPlafondCredit(plafondCloud);
                    if (codeSecret != null && !codeSecret.isEmpty()) local.setCodeSecret(codeSecret);
                    compteRepository.save(local);
                    majs++;
                }
            }

            // ─── 1b. comptes (Edge -> Cloud) : tout compte local absent du Cloud lui est poussé ───
            List<Compte> comptesLocaux = compteRepository.findAll();
            for (Compte local : comptesLocaux) {
                if (!idsCloud.contains(local.getId())) {
                    if (synchroniserCompteVersCloud(local)) pousses++;
                }
            }

            // ─── 2. opérations (Cloud -> Edge) ───
            try {
                ResponseEntity<List<Operation>> repOps = restTemplate.exchange(
                        base() + "/api/banque/sync/operations", HttpMethod.GET, requete,
                        new ParameterizedTypeReference<List<Operation>>() {});
                if (repOps.getBody() != null) {
                    List<Operation> opsLocales = operationRepository.findAll();
                    for (Operation opCloud : repOps.getBody()) {
                        boolean existe = opsLocales.stream().anyMatch(opLocal ->
                                opLocal.getType().equalsIgnoreCase(opCloud.getType())
                                && Math.abs(opLocal.getMontant() - opCloud.getMontant()) < 0.001
                                && (opCloud.getCompteSourceId() == null
                                    || opCloud.getCompteSourceId().equals(opLocal.getCompteSourceId())));
                        if (!existe) {
                            operationRepository.save(new Operation(
                                    opCloud.getType(), opCloud.getMontant(),
                                    opCloud.getDateOperation() != null ? opCloud.getDateOperation() : new Date(),
                                    opCloud.getCompteSourceId(), opCloud.getCompteDestinationId()));
                            ops++;
                        }
                    }
                }
            } catch (RestClientException e) {
                System.err.println("[SYNC Cloud->Edge] Opérations non répliquées : " + e.getMessage());
            }

            if (importes + majs + pousses + ops > 0) {
                System.out.println("[SYNC Cloud<->Edge] comptes importés=" + importes + ", soldes mis à jour=" + majs
                        + ", comptes envoyés au Cloud=" + pousses + ", opérations répliquées=" + ops);
            }
            etat("cloud->edge", "OK", "[SYNC Cloud<->Edge] Cloud joignable : " + comptesDuCloud.size()
                    + " compte(s) côté Cloud, " + comptesLocaux.size() + " en local.");

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) cloudToken = null;
            etat("cloud->edge", "HTTP" + e.getStatusCode().value(),
                    "[SYNC Cloud->Edge] Refus du Cloud : HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            etat("cloud->edge", "ERR:" + e.getClass().getSimpleName(), "[SYNC Cloud->Edge] Échec : " + e);
        }
    }
}
