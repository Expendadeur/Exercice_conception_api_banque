package com.banque.security;

import java.security.Principal;
import java.util.Date;

/** Identité du titulaire authentifié (jeton utilisateur). */
public class ClientPrincipal implements Principal {
    private final String compteId;
    private final String appId;
    private final String jti;
    private final Date expiration;

    public ClientPrincipal(String compteId, String appId, String jti, Date expiration) {
        this.compteId = compteId;
        this.appId = appId;
        this.jti = jti;
        this.expiration = expiration;
    }

    public String getCompteId() { return compteId; }
    public String getAppId() { return appId; }
    public String getJti() { return jti; }
    public Date getExpiration() { return expiration; }
    @Override public String getName() { return compteId; }
}
