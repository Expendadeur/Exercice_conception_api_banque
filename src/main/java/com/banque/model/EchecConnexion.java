package com.banque.model;

import java.util.Date;
import javax.persistence.*;

/** Journal des échecs de PIN, pour la variable echecs_connexion_24h du modèle anti-fraude. */
@Entity
@Table(name = "echecs_connexion", indexes = @Index(name = "idx_echec_compte_date", columnList = "compteId,dateEchec"))
public class EchecConnexion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String compteId;
    @Temporal(TemporalType.TIMESTAMP)
    private Date dateEchec;

    public EchecConnexion() {}
    public EchecConnexion(String compteId, Date dateEchec) { this.compteId = compteId; this.dateEchec = dateEchec; }

    public Long getId() { return id; }
    public String getCompteId() { return compteId; }
    public Date getDateEchec() { return dateEchec; }
}
