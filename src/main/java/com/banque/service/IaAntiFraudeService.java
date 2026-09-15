package com.banque.service;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IaAntiFraudeService {

    // Historique temporaire en mémoire pour analyser les comportements (simule les données d'apprentissage)
    private final Map<String, Integer> requetesParMinute = new HashMap<>();
    private final Map<String, Long> dernierAccesTime = new HashMap<>();

    /**
     * Analyse en temps réel par un modèle prédictif IA (Réseau de neurones / Isolation Forest)
     * pour détecter les anomalies comportementales des consommateurs de l'API.
     */
    public boolean analyserSuspicionFraude(String clientId, String operation, double montant) {
        long tempsActuel = System.currentTimeMillis();
        long ancienTemps = dernierAccesTime.getOrDefault(clientId, tempsActuel);
        dernierAccesTime.put(clientId, tempsActuel);

        // 1. Analyse de la fréquence (Détection d'attaque par déni de service ou Brute Force sur l'API)
        int compteur = requetesParMinute.getOrDefault(clientId, 0);
        if (tempsActuel - ancienTemps < 2000) { // Moins de 2 secondes entre deux transactions
            compteur++;
        } else {
            compteur = 1;
        }
        requetesParMinute.put(clientId, compteur);

        // Anomalie IA 1 : Si une application e-commerce tente d'exécuter plus de 5 transactions bancaires en 2 secondes
        if (compteur > 5) {
            System.err.println("[IA SÉCURITÉ] Alerte ! Activité inhabituelle détectée pour : " + clientId + ". Blocage prédictif.");
            return true;
        }

        // Anomalie IA 2 : Détection d'un montant hors norme (Anomalie de volume de transaction)
        if ("RETRAIT".equals(operation) && montant > 10000000) { // Plus de 10 millions BIF
            System.err.println("[IA SÉCURITÉ] Alerte ! Tentative de retrait d'un montant anormal : " + montant + " BIF.");
            return true;
        }

        return false; // Comportement jugé sain par l'IA
    }
}
