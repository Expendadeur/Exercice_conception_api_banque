package com.banque.service;

import com.banque.repository.EchecConnexionRepository;
import com.banque.repository.OperationRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Calcule les variables du modèle selon les définitions VÉRIFIÉES sur transactions_burundi_realiste.csv :
 *  - jour_semaine : convention pandas, 0 = lundi ... 6 = dimanche (vérifié 100 % sur le CSV)
 *  - heure : heure de date_operation
 *  - nb_operations_precedentes_24h : opérations précédentes du compte SOURCE sur 24 h, hors opération courante
 *  - montant_precedent_moyen_30j : moyenne des opérations précédentes du compte source sur 30 j ; ABSENTE si aucune
 *  - ratio_montant_historique : montant / montant_precedent_moyen_30j ; ABSENT si pas d'historique
 *  - valeur absente = NaN : le pipeline ONNX contient une imputation par la médiane (comme à l'entraînement)
 */
@Service
public class FraudContextService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Bujumbura"); // à confirmer : fuseau des dates du CSV

    @Autowired private OperationRepository operationRepository;
    @Autowired private EchecConnexionRepository echecConnexionRepository;
    @Autowired private IaAntiFraudeService iaAntiFraudeService;

    public Map<String, Object> construire(String compteId, String destId, double montant,
                                          String ip, String canalHeader) {
        Map<String, Object> ctx = new HashMap<>();
        long maintenant = System.currentTimeMillis();
        ZonedDateTime t = ZonedDateTime.now(FUSEAU);

        ctx.put("heure", t.getHour());
        ctx.put("jour_semaine", t.getDayOfWeek().getValue() - 1);

        Date il24h = new Date(maintenant - 24L * 3600_000L);
        Date il30j = new Date(maintenant - 30L * 24L * 3600_000L);
        ctx.put("nb_operations_precedentes_24h", (double) operationRepository.compterOperationsDepuis(compteId, il24h));

        Double moyenne = operationRepository.moyenneMontantDepuis(compteId, il30j);
        if (moyenne == null || moyenne <= 0) {
            ctx.put("montant_precedent_moyen_30j", Double.NaN);   // imputé par le modèle (médiane)
            ctx.put("ratio_montant_historique", Double.NaN);
        } else {
            ctx.put("montant_precedent_moyen_30j", moyenne);
            ctx.put("ratio_montant_historique", montant / moyenne);
        }

        // Dans le CSV, nouveau_beneficiaire vaut toujours 0 hors virement.
        // ⚠️ Définition à valider : ici "jamais de virement vers ce destinataire" (voir mise en garde dans la réponse).
        boolean nouveauBeneficiaire = destId != null && operationRepository.compterVirementsVers(compteId, destId) == 0;
        ctx.put("nouveau_beneficiaire", nouveauBeneficiaire ? 1 : 0);

        // Échecs de PIN des dernières 24 h (journal horodaté, non remis à zéro après un succès)
        ctx.put("echecs_connexion_24h", (double) echecConnexionRepository.compterDepuis(compteId, il24h));

        boolean etrangere = !"EDGE_NODE_BUJUMBURA_LOCAL".equals(iaAntiFraudeService.geolocaliserIpClient(ip));
        ctx.put("ip_etrangere", etrangere ? 1 : 0);

        ctx.put("province", "Bujumbura"); // à remplacer par la province réelle quand elle existera
        ctx.put("canal", canalHeader);

        // Sources inexistantes pour l'instant : on n'envoie rien de faux.
        ctx.put("nouvel_appareil", 0);               // nécessite un registre d'appareils par compte
        ctx.put("score_risque_ip", Double.NaN);      // absent dans 32 % du CSV d'entraînement -> médiane (0,10)
        return ctx;
    }
}
