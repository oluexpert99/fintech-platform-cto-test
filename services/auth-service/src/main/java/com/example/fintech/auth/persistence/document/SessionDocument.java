package com.example.fintech.auth.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Display-only session record; the authoritative session lives in Keycloak.
 * TTL on {@code expiresAt} auto-removes stale rows.
 */
@Document(collection = "sessions")
public class SessionDocument {
    @Id private String id;

    @Indexed
    @Field("userId")
    private String userId;

    @Field("keycloakSession") private String keycloakSession;
    @Field("deviceLabel") private String deviceLabel;
    @Field("ipApprox") private String ipApprox;
    @Field("createdAt") private Instant createdAt;
    @Field("lastSeenAt") private Instant lastSeenAt;
    @Field("expiresAt") private Instant expiresAt;

    public SessionDocument() {}

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; } public void setUserId(String s) { this.userId = s; }
    public String getKeycloakSession() { return keycloakSession; } public void setKeycloakSession(String s) { this.keycloakSession = s; }
    public String getDeviceLabel() { return deviceLabel; } public void setDeviceLabel(String s) { this.deviceLabel = s; }
    public String getIpApprox() { return ipApprox; } public void setIpApprox(String s) { this.ipApprox = s; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant t) { this.createdAt = t; }
    public Instant getLastSeenAt() { return lastSeenAt; } public void setLastSeenAt(Instant t) { this.lastSeenAt = t; }
    public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant t) { this.expiresAt = t; }
}
