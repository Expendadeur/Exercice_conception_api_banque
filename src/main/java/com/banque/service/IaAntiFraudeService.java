package com.banque.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IaAntiFraudeService {

    private final Map<String, Integer> requetesParMinute = new ConcurrentHashMap<>();
    private final Map<String, Long> dernierAccesTime = new ConcurrentHashMap<>();

    private OrtEnvironment env;
    private OrtSession session;

    /** Seuil propre à CE fichier fraud_model.onnx (fraud_model_meta.json : 0.5173954). */
    @Value("${fraude.seuil:0.5173954}")
    private float seuilFraude;

    /** true : en cas de panne du modèle on bloque ; false : on laisse passer (et on log en ERREUR). */
    @Value("${fraude.fail-closed:false}")
    private boolean failClosed;

    // Entrées numériques du modèle (tenseurs float32 [N,1], noms EXACTS de l'ONNX) et leur valeur neutre
    private static final String[] NUMERIQUES = {
        "montant", "nouvel_appareil", "nouveau_beneficiaire", "echecs_connexion_24h", "score_risque_ip",
        "ip_etrangere", "heure", "jour_semaine", "nb_operations_precedentes_24h",
        "montant_precedent_moyen_30j", "ratio_montant_historique"
    };

    // Vocabulaires appris à l'entraînement (lus dans le graphe ONNX). Une autre valeur serait ignorée silencieusement.
    private static final Set<String> PROVINCES = new HashSet<>(Arrays.asList(
        "Bubanza", "Bujumbura", "Bururi", "Cankuzo", "Cibitoke", "Gitega", "Karuzi", "Kayanza", "Kirundo",
        "Makamba", "Muramvya", "Muyinga", "Mwaro", "Ngozi", "Rumonge", "Rutana", "Ruyigi"));
    private static final Set<String> CANAUX = new HashSet<>(Arrays.asList("AGENCE", "AGENT", "MOBILE", "WEB"));

    @PostConstruct
    public void initialiserModeleIA() {
        try {
            env = OrtEnvironment.getEnvironment();
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("fraud_model.onnx")) {
                if (is == null) {
                    System.err.println("[⚠️ IA] fraud_model.onnx introuvable : MODÈLE INACTIF.");
                    return;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                session = env.createSession(bos.toByteArray());
            }
            System.out.println("[IA SÉCURITÉ] Modèle chargé. Entrées attendues : " + session.getInputNames());
        } catch (Exception e) {
            System.err.println("[⚠️ ERREUR IA] Initialisation ONNX impossible : " + e.getMessage());
        }
    }

    public boolean modeleActif() { return session != null; }

    public String geolocaliserIpClient(String ipAddress) {
        if (ipAddress == null || "127.0.0.1".equals(ipAddress) || "0:0:0:0:0:0:0:1".equals(ipAddress)
                || ipAddress.startsWith("10.") || ipAddress.startsWith("192.168.") || ipAddress.startsWith("172.")) {
            return "EDGE_NODE_BUJUMBURA_LOCAL";
        }
        if (ipAddress.startsWith("41.") || ipAddress.startsWith("197.") || ipAddress.startsWith("102.")) {
            return "EDGE_NODE_BUJUMBURA_LOCAL";
        }
        return "CLOUD_CENTRAL_RENDER_EUROPE";
    }

    /** Traduit le vocabulaire de l'application vers celui de l'entraînement (DEPOT -> VERSEMENT). */
    private static String typeOperationModele(String operation) {
        if ("DEPOT".equals(operation)) return "VERSEMENT";
        return operation; // RETRAIT, VIREMENT
    }

    private static String normaliser(String valeur, Set<String> vocabulaire, String parDefaut) {
        if (valeur == null) return parDefaut;
        String v = vocabulaire == CANAUX ? valeur.trim().toUpperCase(Locale.ROOT) : valeur.trim();
        return vocabulaire.contains(v) ? v : parDefaut;
    }

    private static float num(Map<String, Object> ctx, String cle, float parDefaut) {
        Object o = ctx.get(cle);
        return (o instanceof Number) ? ((Number) o).floatValue() : parDefaut;
    }

    public boolean analyserSuspicionFraude(String clientId, String operation, double montant, Map<String, Object> ctx) {
        long maintenant = System.currentTimeMillis();
        long ancien = dernierAccesTime.getOrDefault(clientId, maintenant);
        dernierAccesTime.put(clientId, maintenant);

        // 1. Règles comportementales
        int compteur = requetesParMinute.getOrDefault(clientId, 0);
        compteur = (maintenant - ancien < 2000) ? compteur + 1 : 1;
        requetesParMinute.put(clientId, compteur);

        if (compteur > 5) {
            System.err.println("[IA SÉCURITÉ] Fréquence critique pour : " + clientId);
            return true;
        }
        if ("RETRAIT".equals(operation) && montant > 10_000_000) {
            System.err.println("[IA SÉCURITÉ] Montant anormal : " + montant + " BIF.");
            return true;
        }

        // 2. Modèle Random Forest
        if (session == null) {
            System.err.println("[⚠️ IA] Modèle indisponible : contrôle ML IGNORÉ (fail-closed=" + failClosed + ").");
            return failClosed;
        }

        List<OnnxTensor> tensors = new ArrayList<>();
        try {
            float m = (float) montant;
            Map<String, Float> valeurs = new HashMap<>();
            valeurs.put("montant", m);
            valeurs.put("nouvel_appareil", num(ctx, "nouvel_appareil", 0f));
            valeurs.put("nouveau_beneficiaire", num(ctx, "nouveau_beneficiaire", 0f));
            valeurs.put("echecs_connexion_24h", num(ctx, "echecs_connexion_24h", 0f));
            valeurs.put("score_risque_ip", num(ctx, "score_risque_ip", Float.NaN));      // NaN -> imputation médiane dans l'ONNX
            valeurs.put("ip_etrangere", num(ctx, "ip_etrangere", 0f));
            valeurs.put("heure", num(ctx, "heure", 12f));
            valeurs.put("jour_semaine", num(ctx, "jour_semaine", 0f));
            valeurs.put("nb_operations_precedentes_24h", num(ctx, "nb_operations_precedentes_24h", 0f));
            valeurs.put("montant_precedent_moyen_30j", num(ctx, "montant_precedent_moyen_30j", Float.NaN));
            valeurs.put("ratio_montant_historique", num(ctx, "ratio_montant_historique", Float.NaN));

            Map<String, OnnxTensor> entrees = new HashMap<>();
            for (String nom : NUMERIQUES) {
                OnnxTensor t = OnnxTensor.createTensor(env, new float[][]{{valeurs.get(nom)}});
                tensors.add(t);
                entrees.put(nom, t);
            }
            String province = normaliser((String) ctx.get("province"), PROVINCES, "Bujumbura");
            String canal = normaliser((String) ctx.get("canal"), CANAUX, "MOBILE");
            for (Object[] cat : new Object[][]{
                    {"type_operation", typeOperationModele(operation)}, {"province", province}, {"canal", canal}}) {
                OnnxTensor t = OnnxTensor.createTensor(env, new String[][]{{(String) cat[1]}});
                tensors.add(t);
                entrees.put((String) cat[0], t);
            }

            try (OrtSession.Result res = session.run(entrees)) {
                // sorties : [0] label, [1] probabilities [N,2] (colonne 1 = P(fraude))
                float[][] proba = (float[][]) res.get(1).getValue();
                float p = proba[0][1];
                System.out.println("[IA] " + operation + " " + montant + " BIF -> P(fraude)=" + String.format("%.3f", p)
                        + " (seuil " + seuilFraude + ")");
                if (p >= seuilFraude) {
                    System.err.println("[⚠️ REJET ANTI-FRAUDE] Opération bloquée par le Random Forest.");
                    return true;
                }
            }
        } catch (OrtException | RuntimeException e) {
            System.err.println("[⚠️ ERREUR INFÉRENCE] " + e.getMessage() + " (fail-closed=" + failClosed + ")");
            return failClosed;
        } finally {
            for (OnnxTensor t : tensors) t.close();
        }
        return false;
    }

    @PreDestroy
    public void fermerRessources() throws OrtException {
        if (session != null) session.close();
        if (env != null) env.close();
    }
}