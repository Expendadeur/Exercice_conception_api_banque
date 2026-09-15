package com.banque.model;

import java.util.Date;
import javax.persistence.*;

@Entity
@Table(name = "edge_sync_queue")
public class EdgeSyncQueue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String compteId;
    private String typeOperation; // DEPOT, RETRAIT, VIREMENT
    private double montant;
    private String compteDestinationId; // Requis uniquement pour les virements
    
    @Temporal(TemporalType.TIMESTAMP)
    private Date dateCreation = new Date();
    private String statutSynchronisation = "PENDING";

    public EdgeSyncQueue() {}
    
    // Constructeur pour Dépôt et Retrait
    public EdgeSyncQueue(String compteId, String typeOperation, double montant) {
        this.compteId = compteId;
        this.typeOperation = typeOperation;
        this.montant = montant;
    }

    // Constructeur spécifique pour le Virement
    public EdgeSyncQueue(String compteId, String typeOperation, double montant, String compteDestinationId) {
        this.compteId = compteId;
        this.typeOperation = typeOperation;
        this.montant = montant;
        this.compteDestinationId = compteDestinationId;
    }

    // Getters et Setters indispensables pour Hibernate
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCompteId() { return compteId; }
    public void setCompteId(String compteId) { this.compteId = compteId; }
    public String getTypeOperation() { return typeOperation; }
    public void setTypeOperation(String typeOperation) { this.typeOperation = typeOperation; }
    public double getMontant() { return montant; }
    public void setMontant(double montant) { this.montant = montant; }
    public String getCompteDestinationId() { return compteDestinationId; }
    public void setCompteDestinationId(String compteDestinationId) { this.compteDestinationId = compteDestinationId; }
    public Date getDateCreation() { return dateCreation; }
    public void setDateCreation(Date dateCreation) { this.dateCreation = dateCreation; }
    public String getStatutSynchronisation() { return statutSynchronisation; }
    public void setStatutSynchronisation(String statutSynchronisation) { this.statutSynchronisation = statutSynchronisation; }
}