package com.example.fintech.accounts.integration;

import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;
import com.example.fintech.accounts.persistence.document.PendingApprovalDocument;
import com.example.fintech.accounts.persistence.repository.PendingApprovalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

class AccountsApiIntegrationTest extends IntegrationTestBase {

    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private PendingApprovalRepository pendingApprovalRepository;
    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void resetCollections() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        mongoTemplate.dropCollection(AccountDocument.class);
        mongoTemplate.dropCollection(OutboxRecordDocument.class);
        mongoTemplate.dropCollection(PendingApprovalDocument.class);
    }

    @Test
    void postWithoutIdempotencyKey_returnsProblem400() throws Exception {
        mockMvc.perform(post("/v1/accounts")
                        .with(userJwt("U-ALICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"USD","type":"CHECKING","label":"Main"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void openGetListAndBalance_workEndToEnd() throws Exception {
        String accountId = openAccount("U-ALICE");

        mockMvc.perform(get("/v1/accounts/{id}", accountId).with(userJwt("U-ALICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId));

        mockMvc.perform(get("/v1/accounts").with(userJwt("U-ALICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(accountId));

        mockMvc.perform(get("/v1/accounts/{id}/balance", accountId).with(userJwt("U-ALICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void patchWithWrongIfMatch_returnsVersionConflict() throws Exception {
        String accountId = openAccount("U-ALICE");

        mockMvc.perform(patch("/v1/accounts/{id}", accountId)
                        .with(userJwt("U-ALICE"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Renamed"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void sensitiveUnfreeze_requiresAndConsumesApproval() throws Exception {
        String accountId = openAccount("U-ALICE");

        mockMvc.perform(patch("/v1/accounts/{id}", accountId)
                        .with(userJwt("U-ALICE"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"FROZEN","reason":"FRAUD_SUSPECTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(patch("/v1/accounts/{id}", accountId)
                        .with(operatorJwt("U-OP-1"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE","reason":"FRAUD_SUSPECTED"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OPERATOR_APPROVAL_REQUIRED"));

        PendingApprovalDocument approval = new PendingApprovalDocument();
        approval.setAccountId(accountId);
        approval.setApproverId("U-OP-2");
        approval.setReason(StatusReason.FRAUD_SUSPECTED);
        approval.setStatus("PENDING");
        approval.setCreatedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(600));
        pendingApprovalRepository.save(approval);

        mockMvc.perform(patch("/v1/accounts/{id}", accountId)
                        .with(operatorJwt("U-OP-1"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE","reason":"FRAUD_SUSPECTED","approverId":"U-OP-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        PendingApprovalDocument used = pendingApprovalRepository.findById(approval.getId()).orElseThrow();
        assertThat(used.getStatus()).isEqualTo("USED");
        assertThat(used.getUsedAt()).isNotNull();
    }

    private String openAccount(String sub) throws Exception {
        MvcResult open = mockMvc.perform(post("/v1/accounts")
                        .with(userJwt(sub))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"USD","type":"CHECKING","label":"Main"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();

        JsonNode node = objectMapper.readTree(open.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt(String sub) {
        return jwt().jwt(j -> j.subject(sub));
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor operatorJwt(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("realm_access", Map.of("roles", java.util.List.of("operator"))));
    }
}
