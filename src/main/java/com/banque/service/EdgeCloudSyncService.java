package com.banque.service;

import com.banque.model.EdgeSyncQueue;
import com.banque.security.EdgeSyncQueueRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Synchronise la file d'attente Edge vers le Cloud.
 * Les PIN ne sont JAMAIS stockés ni rejoués : l'opération a déjà été autorisée à l'Edge par le titulaire.
 * La synchro s'authentifie comme application B2B (rôle ROLE_EDGE_SYNC) et appelle /api/banque/sync/operation.
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

    private final RestTemplate restTemplate = new RestTemplate();
    private volatile String cloudToken;

    @Scheduled(fixedDelay = 10000)
    public void synchroniserEdgeVersCloud() {
        List<EdgeSyncQueue> enAttente = queueRepository.findByStatutSynchronisation("PENDING");
        if (enAttente.isEmpty()) return;

        if (syncClientId.isEmpty() || syncClientSecret.isEmpty()) {
            System.err.println("[MOTEUR SYNC EDGE] edge.sync.client-id / client-secret non configurés.");
            return;
        }

        // Respect de l'ordre chronologique (un dépôt doit précéder le retrait qui en dépend)
        enAttente.sort(Comparator.comparing(EdgeSyncQueue::getId));
        System.out.println("[MOTEUR SYNC EDGE] " + enAttente.size() + " opération(s) à synchroniser.");

        for (EdgeSyncQueue tx : enAttente) {
            try {
                if (cloudToken == null) cloudToken = obtenirJeton();
                envoyer(tx);
                tx.setStatutSynchronisation("SYNCED");
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    cloudToken = null; // jeton expiré : on réessaiera au prochain cycle
                    return;
                }
                // 4xx métier (solde insuffisant, compte inconnu...) : inutile de réessayer
                tx.setStatutSynchronisation("FAILED");
                System.err.println("[MOTEUR SYNC EDGE] Rejet définitif tx " + tx.getId() + " : " + e.getResponseBodyAsString());
            } catch (RestClientException e) {
                // Réseau / 5xx : reste PENDING, on stoppe le cycle pour préserver l'ordre
                System.err.println("[MOTEUR SYNC EDGE] Cloud injoignable, nouvel essai plus tard : " + e.getMessage());
                return;
            }
            queueRepository.save(tx);
        }
    }

    private String obtenirJeton() {
        Map<String, String> body = new HashMap<>();
        body.put("clientId", syncClientId);
        body.put("clientSecret", syncClientSecret);
        Map<?, ?> rep = restTemplate.postForObject(cloudUrl + "/api/banque/auth", body, Map.class);
        if (rep == null || rep.get("token") == null) throw new RestClientException("Jeton cloud absent de la réponse");
        return (String) rep.get("token");
    }

    private void envoyer(EdgeSyncQueue tx) {
        Map<String, Object> body = new HashMap<>();
        body.put("compteId", tx.getCompteId());
        body.put("typeOperation", tx.getTypeOperation().toUpperCase());
        body.put("montant", tx.getMontant());
        body.put("compteDestinationId", tx.getCompteDestinationId());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cloudToken);

        restTemplate.postForEntity(cloudUrl + "/api/banque/sync/operation", new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Pousse un compte local vers le Cloud (Edge → Cloud).
     * Appelé immédiatement lors de la création d'un compte sur le nœud Edge,
     * et par le scheduler lors de la réconciliation bidirectionnelle.
     */
    public void synchroniserCompteVersCloud(com.banque.model.Compte compte) {
        if (syncClientId.isEmpty() || syncClientSecret.isEmpty()) return;
        if (cloudUrl.contains("localhost") || cloudUrl.contains("127.0.0.1")) return;
        try {
            if (cloudToken == null) cloudToken = obtenirJeton();

            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("id", compte.getId());
            body.put("titulaire", compte.getTitulaire());
            body.put("solde", compte.getSolde());
            body.put("plafondCredit", compte.getPlafondCredit());
            body.put("codeSecret", compte.getCodeSecret());
            body.put("tentativesPinEchouees", compte.getTentativesPinEchouees());
            body.put("verrouilleJusqua", compte.getVerrouilleJusqua());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cloudToken);

            restTemplate.postForEntity(
                cloudUrl + "/api/banque/sync/compte",
                new HttpEntity<>(body, headers),
                String.class
            );
            System.out.println("[SYNC Edge->Cloud] Compte " + compte.getId() + " synchronisé vers Render Cloud.");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) cloudToken = null;
            System.err.println("[SYNC Edge->Cloud] Rejet compte " + compte.getId() + " : " + e.getResponseBodyAsString());
        } catch (Exception e) {
            System.err.println("[SYNC Edge->Cloud] Cloud injoignable pour compte " + compte.getId() + " : " + e.getMessage());
        }
    }

    /**
     * Synchronise les tables du Cloud vers la base locale Edge (Pull bidirectionnel).
     * Garantit que les comptes créés ou modifiés sur le Cloud ainsi que les opérations
     * passées sur le Cloud sont immédiatement répliquées sur le nœud Edge local.
     */
    @Scheduled(fixedDelay = 20000)
    public void synchroniserCloudVersEdge() {
        if (syncClientId.isEmpty() || syncClientSecret.isEmpty()) return;
        if (cloudUrl.contains("localhost") || cloudUrl.contains("127.0.0.1")) return;

        try {
            if (cloudToken == null) cloudToken = obtenirJeton();

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(cloudToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            // ─── 1. SYNCHRONISATION TABLE 'comptes' (Cloud -> Edge) ───
            List<Map<String, Object>> comptesDuCloud = null;
            try {
                org.springframework.http.ResponseEntity<List<Map<String, Object>>> repComptes = restTemplate.exchange(
                    cloudUrl + "/api/banque/sync/comptes",
                    org.springframework.http.HttpMethod.GET,
                    requestEntity,
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
                );

                comptesDuCloud = repComptes.getBody();
                if (comptesDuCloud != null) {
                    for (Map<String, Object> map : comptesDuCloud) {
                        String id = (String) map.get("id");
                        if (id == null) continue;

                        com.banque.model.Compte local = compteRepository.findById(id).orElse(null);
                        double soldeCloud = ((Number) map.get("solde")).doubleValue();
                        double plafondCloud = ((Number) map.get("plafondCredit")).doubleValue();
                        String titulaire = (String) map.get("titulaire");
                        String codeSecret = (String) map.get("codeSecret");

                        if (local == null) {
                            // Nouveau compte créé sur le Cloud -> Création en base locale
                            com.banque.model.Compte nouveau = new com.banque.model.Compte(id, titulaire, soldeCloud, plafondCloud);
                            nouveau.setCodeSecret(codeSecret != null ? codeSecret : "");
                            compteRepository.save(nouveau);
                            System.out.println("[SYNC BIDIRECTIONNELLE] Nouveau compte Cloud importé en local : " + id);
                        } else {
                            // S'assurer qu'aucune opération locale pour ce compte n'est en attente (PENDING)
                            boolean hasPending = queueRepository.findByStatutSynchronisation("PENDING").stream()
                                    .anyMatch(q -> id.equals(q.getCompteId()) || id.equals(q.getCompteDestinationId()));
                            if (!hasPending && (local.getSolde() != soldeCloud || local.getPlafondCredit() != plafondCloud)) {
                                local.setSolde(soldeCloud);
                                local.setPlafondCredit(plafondCloud);
                                if (codeSecret != null && !codeSecret.isEmpty()) {
                                    local.setCodeSecret(codeSecret);
                                }
                                compteRepository.save(local);
                                System.out.println("[SYNC BIDIRECTIONNELLE] Solde compte " + id + " équilibré depuis le Cloud : " + soldeCloud);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Erreur ponctuelle réseau / timeout
            }

            // ─── 1b. SYNCHRONISATION TABLE 'comptes' (Edge -> Cloud) ───
            // Tout compte existant en local mais absent sur le Cloud est immédiatement poussé vers le Cloud !
            if (comptesDuCloud != null) {
                List<com.banque.model.Compte> comptesLocaux = compteRepository.findAll();
                for (com.banque.model.Compte local : comptesLocaux) {
                    boolean existeSurCloud = comptesDuCloud.stream()
                            .anyMatch(cCloud -> local.getId().equals(cCloud.get("id")));
                    if (!existeSurCloud) {
                        System.out.println("[SYNC BIDIRECTIONNELLE] Compte local " + local.getId() + " absent sur le Cloud -> Synchronisation vers Render...");
                        synchroniserCompteVersCloud(local);
                    }
                }
            }

            // ─── 2. SYNCHRONISATION TABLE 'operations' (Cloud -> Edge) ───
            try {
                org.springframework.http.ResponseEntity<List<com.banque.model.Operation>> repOps = restTemplate.exchange(
                    cloudUrl + "/api/banque/sync/operations",
                    org.springframework.http.HttpMethod.GET,
                    requestEntity,
                    new org.springframework.core.ParameterizedTypeReference<List<com.banque.model.Operation>>() {}
                );

                if (repOps.getBody() != null) {
                    List<com.banque.model.Operation> opsLocales = operationRepository.findAll();
                    for (com.banque.model.Operation opCloud : repOps.getBody()) {
                        boolean existe = opsLocales.stream().anyMatch(opLocal ->
                            opLocal.getType().equalsIgnoreCase(opCloud.getType()) &&
                            Math.abs(opLocal.getMontant() - opCloud.getMontant()) < 0.001 &&
                            (opCloud.getCompteSourceId() == null || opCloud.getCompteSourceId().equals(opLocal.getCompteSourceId()))
                        );

                        if (!existe) {
                            com.banque.model.Operation nouvelle = new com.banque.model.Operation(
                                opCloud.getType(),
                                opCloud.getMontant(),
                                opCloud.getDateOperation() != null ? opCloud.getDateOperation() : new java.util.Date(),
                                opCloud.getCompteSourceId(),
                                opCloud.getCompteDestinationId()
                            );
                            operationRepository.save(nouvelle);
                            System.out.println("[SYNC BIDIRECTIONNELLE] Opération Cloud répliquée en base locale : " + opCloud.getType() + " de " + opCloud.getMontant());
                        }
                    }
                }
            } catch (Exception e) {
                // Erreur ponctuelle réseau / timeout
            }

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                cloudToken = null;
            }
        } catch (Exception e) {
            // Cloud injoignable
        }
    }
}
