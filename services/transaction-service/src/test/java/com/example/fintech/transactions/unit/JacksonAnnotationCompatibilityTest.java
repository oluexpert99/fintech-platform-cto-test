package com.example.fintech.transactions.unit;

import com.example.fintech.transactions.messaging.event.TransactionCompletedEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the architect's "Jackson 3 ignores Jackson 2 annotations" concern is not actually a
 * problem here — Jackson 3 databind (under {@code tools.jackson.*}) keeps the annotation package
 * at {@code com.fasterxml.jackson.annotation} as a stable contract, so existing
 * {@code @JsonInclude(NON_NULL)} from Jackson 2 is honoured.
 *
 * <p>This test fails if Spring Boot 4 ever swaps to a Jackson 3 annotation package, in which case
 * we'd need to migrate the annotations across the codebase. Until then, the existing imports are
 * correct.
 */
@DisplayName("Jackson 3 honours com.fasterxml.jackson.annotation annotations")
class JacksonAnnotationCompatibilityTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void jsonInclude_NON_NULL_isHonouredByJackson3() {
        // TransactionCompletedEvent is annotated with @JsonInclude(NON_NULL) from
        // com.fasterxml.jackson.annotation. The `description` field is null in this fixture
        // — it must be OMITTED from the serialised output, not rendered as "description": null.
        var event = new TransactionCompletedEvent(
                "TX-01HZ8K1234567890ABCDEFGH",
                "TRANSFER",
                "ACC100001",
                "ACC100002",
                100L,
                "USD",
                null,                      // description: null on purpose
                Instant.parse("2026-05-28T10:00:00Z"));

        String json = mapper.writeValueAsString(event);

        assertThat(json)
                .as("Jackson 3 must omit null-valued fields when @JsonInclude(NON_NULL) is present")
                .doesNotContain("description");
        assertThat(json).contains("\"transactionId\"", "\"amount\":100");
    }

    @Test
    void plainJsonInclude_doesNotAccidentallyOmitNonNullValues() {
        // Defence: confirm NON_NULL doesn't accidentally drop required fields.
        var event = new TransactionCompletedEvent(
                "TX-01HZ8K1234567890ABCDEFGH",
                "TRANSFER",
                "ACC100001",
                "ACC100002",
                100L,
                "USD",
                "Coffee",
                Instant.parse("2026-05-28T10:00:00Z"));

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"description\":\"Coffee\"");
    }

    /** Verifies the JsonInclude annotation is sourced from the package we think it is. */
    @Test
    void annotation_isFromComFasterxmlPackage() {
        // The annotation on TransactionCompletedEvent should be a com.fasterxml.jackson.annotation
        // type. If Jackson 3 ever introduces tools.jackson.annotation.JsonInclude, this test fails
        // and we'd need to migrate.
        var ann = TransactionCompletedEvent.class.getAnnotation(JsonInclude.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo(JsonInclude.Include.NON_NULL);
        assertThat(ann.annotationType().getPackage().getName())
                .isEqualTo("com.fasterxml.jackson.annotation");
    }
}
