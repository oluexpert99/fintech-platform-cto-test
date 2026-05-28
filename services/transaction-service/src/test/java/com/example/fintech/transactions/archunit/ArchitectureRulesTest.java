package com.example.fintech.transactions.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Negative constraints from {@code transaction-service.spec} §8 — executable rules.
 */
class ArchitectureRulesTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.example.fintech.transactions");

    private static final String KAFKA_TEMPLATE_FQCN = "org.springframework.kafka.core.KafkaTemplate";
    private static final String OUTBOX_PUBLISHER_FQCN = "com.example.fintech.transactions.messaging.OutboxPublisher";
    private static final String KAFKA_CONFIG_FQCN = "com.example.fintech.transactions.config.KafkaConfig";

    /**
     * Stronger than the previous rule: bans the {@code send(...)} <em>method call</em>, not just
     * the type dependency. Adding a new class under {@code messaging.*} that calls {@code .send()}
     * is now caught — only {@link OutboxPublisher} may publish, full stop.
     */
    @Test
    void onlyOutboxPublisherMayCallKafkaTemplateSend() {
        ArchRule rule = classes()
                .that().areNotAssignableTo(KAFKA_TEMPLATE_FQCN)   // exclude KafkaTemplate itself
                .should(new ArchCondition<JavaClass>("not call KafkaTemplate.send(...) outside OutboxPublisher") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        if (OUTBOX_PUBLISHER_FQCN.equals(clazz.getName())) return;
                        for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
                            String target = call.getTargetOwner().getName();
                            String method = call.getName();
                            if (KAFKA_TEMPLATE_FQCN.equals(target) && "send".equals(method)) {
                                events.add(SimpleConditionEvent.violated(call,
                                        clazz.getName() + " calls KafkaTemplate.send(...) — only "
                                                + OUTBOX_PUBLISHER_FQCN + " may publish to Kafka. "
                                                + "See ADR-0002 / transaction-service.spec §4.4."));
                            }
                        }
                    }
                });
        rule.check(CLASSES);
    }

    /**
     * Defence-in-depth: nothing outside the two named classes should even depend on
     * {@code KafkaTemplate}. {@link OutboxPublisher} uses it (publishes); {@link KafkaConfig}
     * constructs the bean. Everything else is forbidden — closes the loophole that the per-method
     * rule alone doesn't (e.g. holding a {@code KafkaTemplate} reference without calling send).
     */
    @Test
    void onlyOutboxPublisherAndKafkaConfigDependOnKafkaTemplate() {
        ArchRule rule = noClasses()
                .that().haveNameNotMatching(OUTBOX_PUBLISHER_FQCN)
                .and().haveNameNotMatching(KAFKA_CONFIG_FQCN)
                .should().dependOnClassesThat().haveFullyQualifiedName(KAFKA_TEMPLATE_FQCN)
                .because("Holding a KafkaTemplate reference is forbidden outside OutboxPublisher + KafkaConfig — ADR-0002.");
        rule.check(CLASSES);
    }

    @Test
    void noBigDecimalOrBigIntegerInProductionCode() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("java.math.BigDecimal")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.math.BigInteger")
                .because("Money is `long` minor units; no arbitrary-precision types in the money path.");
        rule.check(CLASSES);
    }
}
