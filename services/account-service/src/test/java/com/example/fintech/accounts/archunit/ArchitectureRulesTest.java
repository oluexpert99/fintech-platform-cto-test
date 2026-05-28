package com.example.fintech.accounts.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.example.fintech.accounts");

    @Test
    void onlyOutboxPublisherAndKafkaConfigDependOnKafkaTemplate() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(
                        "com.example.fintech.accounts.messaging..",
                        "com.example.fintech.accounts.config..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate");
        rule.check(CLASSES);
    }
}
