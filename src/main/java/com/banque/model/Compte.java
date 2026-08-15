package com.banque.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "comptes")
public class Compte {
    @Id
    private String id;
    private String titulaire;
    private double solde;
    private double plafondCredit;

    // Constructeurs
    public Compte() {}
    public Compte(String id, String titulaire, double solde, double plafondCredit) {
        this.id = id;
        this.titulaire = titulaire;
        this.solde = solde;
        this.plafondCredit = plafondCredit;
    }

    // Getters et Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitulaire() { return titulaire; }
    public void setTitulaire(String titulaire) { this.titulaire = titulaire; }
    public double getSolde() { return solde; }
    public void setSolde(double solde) { this.solde = solde; }
    public double getPlafondCredit() { return plafondCredit; }
    public void setPlafondCredit(double plafondCredit) { this.plafondCredit = plafondCredit; }
}