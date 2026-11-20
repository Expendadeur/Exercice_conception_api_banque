package com.banque.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javax.persistence.Column;
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

    /** Hash BCrypt du PIN (jamais le PIN en clair). Jamais sérialisé en JSON. */
    @JsonIgnore
    @Column(name = "code_secret", nullable = false, length = 255)
    private String codeSecret;

    /** Anti brute-force : compteur d'échecs et date de fin de verrouillage (epoch ms). */
    @JsonIgnore
    private int tentativesPinEchouees = 0;
    @JsonIgnore
    private long verrouilleJusqua = 0L;

    public Compte() {}
    public Compte(String id, String titulaire, double solde, double plafondCredit) {
        this.id = id;
        this.titulaire = titulaire;
        this.solde = solde;
        this.plafondCredit = plafondCredit;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitulaire() { return titulaire; }
    public void setTitulaire(String titulaire) { this.titulaire = titulaire; }
    public double getSolde() { return solde; }
    public void setSolde(double solde) { this.solde = solde; }
    public double getPlafondCredit() { return plafondCredit; }
    public void setPlafondCredit(double plafondCredit) { this.plafondCredit = plafondCredit; }
    public String getCodeSecret() { return codeSecret; }
    public void setCodeSecret(String codeSecret) { this.codeSecret = codeSecret; }
    public int getTentativesPinEchouees() { return tentativesPinEchouees; }
    public void setTentativesPinEchouees(int t) { this.tentativesPinEchouees = t; }
    public long getVerrouilleJusqua() { return verrouilleJusqua; }
    public void setVerrouilleJusqua(long v) { this.verrouilleJusqua = v; }
}
