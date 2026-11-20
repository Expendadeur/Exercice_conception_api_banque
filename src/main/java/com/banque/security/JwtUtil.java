package com.banque.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

    public static final String TYPE_APP = "APP";
    public static final String TYPE_CLIENT = "CLIENT";

    private static final long APP_EXPIRATION_MS = 36_000_000L;  // 10 h (applications B2B)
    private static final long CLIENT_EXPIRATION_MS = 900_000L;  // 15 min (titulaires)

    private final Key secretKey;

    /** jti -> date d'expiration. En mémoire : à remplacer par Redis/BDD si plusieurs instances. */
    private final Map<String, Long> jetonsRevoques = new ConcurrentHashMap<>();

    public JwtUtil(@Value("${jwt.secret:}") String secretBase64) {
        if (secretBase64 == null || secretBase64.trim().isEmpty()) {
            System.err.println("[SÉCURITÉ] jwt.secret absent : clé aléatoire générée, les jetons seront invalides après redémarrage.");
            this.secretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        } else {
            this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretBase64.trim())); // >= 256 bits
        }
    }

    public long getClientExpirationSeconds() { return CLIENT_EXPIRATION_MS / 1000; }

    // ---------- Génération ----------

    public String generateToken(String clientId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("typ", TYPE_APP);
        return build(claims, clientId, APP_EXPIRATION_MS);
    }

    /** Jeton titulaire : sujet = numéro de compte, "app" = application ayant relayé le login. */
    public String generateClientToken(String compteId, String appId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("typ", TYPE_CLIENT);
        claims.put("app", appId);
        return build(claims, compteId, CLIENT_EXPIRATION_MS);
    }

    private String build(Map<String, Object> claims, String subject, long dureeMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + dureeMs))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    // ---------- Lecture / validation ----------

    /** Vérifie signature + expiration, lève JwtException sinon. */
    public Claims parseClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(secretKey).build().parseClaimsJws(token).getBody();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(parseClaims(token));
    }

    public Boolean validateToken(String token, String clientId) {
        Claims c = parseClaims(token);
        return c.getSubject().equals(clientId) && !c.getExpiration().before(new Date());
    }

    // ---------- Révocation (logout) ----------

    public void revoquer(String jti, Date expiration) {
        if (jti == null) return;
        long now = System.currentTimeMillis();
        jetonsRevoques.values().removeIf(exp -> exp < now); // purge des entrées expirées
        jetonsRevoques.put(jti, expiration == null ? now + CLIENT_EXPIRATION_MS : expiration.getTime());
    }

    public boolean estRevoque(String jti) {
        return jti != null && jetonsRevoques.containsKey(jti);
    }
}
