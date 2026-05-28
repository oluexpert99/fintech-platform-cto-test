package com.example.fintech.auth.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "users")
public class UserDocument {
    @Id private String id;

    @Indexed(unique = true)
    @Field("email")
    private String email;

    @Field("phone") private String phone;
    @Field("fullName") private String fullName;

    @Indexed(unique = true)
    @Field("keycloakSub")
    private String keycloakSub;

    @Field("status") private String status;
    @Field("kycLevel") private String kycLevel;
    @Field("mfaEnabled") private boolean mfaEnabled;

    @Field("createdAt") private Instant createdAt;
    @Field("updatedAt") private Instant updatedAt;

    @Version
    private Long version;

    public UserDocument() {}

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getEmail() { return email; } public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; } public void setPhone(String phone) { this.phone = phone; }
    public String getFullName() { return fullName; } public void setFullName(String fullName) { this.fullName = fullName; }
    public String getKeycloakSub() { return keycloakSub; } public void setKeycloakSub(String s) { this.keycloakSub = s; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public String getKycLevel() { return kycLevel; } public void setKycLevel(String s) { this.kycLevel = s; }
    public boolean isMfaEnabled() { return mfaEnabled; } public void setMfaEnabled(boolean b) { this.mfaEnabled = b; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant t) { this.createdAt = t; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant t) { this.updatedAt = t; }
    public Long getVersion() { return version; } public void setVersion(Long v) { this.version = v; }
}
