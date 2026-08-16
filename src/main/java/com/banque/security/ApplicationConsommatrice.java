package com.banque.security;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "applications_consommatrices")
public class ApplicationConsommatrice {
    @Id
    private String clientId;
    private String clientSecret;
    private String nomApplication;
    private String role;

    public ApplicationConsommatrice() {}

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getNomApplication() { return nomApplication; }
    public void setNomApplication(String nomApplication) { this.nomApplication = nomApplication; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}