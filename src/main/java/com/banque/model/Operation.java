package com.banque.model;

import java.util.Date;
import javax.persistence.*;

@Entity
@Table(name = "operations")
public class Operation {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String type;
    private double montant;
    
    @Temporal(TemporalType.TIMESTAMP)
    private Date dateOperation;
    
    private String compteSourceId;
    private String compteDestinationId;

    // Constructeur par défaut (Obligatoire pour JPA/Hibernate)
    public Operation() {
    }

    // Constructeur personnalisé utilisé par le Service
    public Operation(String type, double montant, Date dateOperation, String compteSourceId, String compteDestinationId) {
        this.type = type;
        this.montant = montant;
        this.dateOperation = dateOperation;
        this.compteSourceId = compteSourceId;
        this.compteDestinationId = compteDestinationId;
    }

    // --- GETTERS ET SETTERS (Indispensables pour la conversion en JSON) ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public double getMontant() { return montant; }
    public void setMontant(double montant) { this.montant = montant; }

    public Date getDateOperation() { return dateOperation; }
    public void setDateOperation(Date dateOperation) { this.dateOperation = dateOperation; }

    public String getCompteSourceId() { return compteSourceId; }
    public void setCompteSourceId(String compteSourceId) { this.compteSourceId = compteSourceId; }

    public String getCompteDestinationId() { return compteDestinationId; }
    public void setCompteDestinationId(String compteDestinationId) { this.compteDestinationId = compteDestinationId; }
}