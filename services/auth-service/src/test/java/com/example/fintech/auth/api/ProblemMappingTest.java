package com.example.fintech.auth.api;

import com.example.fintech.auth.domain.exception.DomainException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflective gate: every {@link DomainException} subtype must have an
 * {@code @ExceptionHandler} in {@link ProblemExceptionHandler}.
 *
 * <p>Same pattern as transaction-service's ProblemMappingTest. Fails the build the moment a new
 * sealed-hierarchy subtype is added without a corresponding handler.
 */
class ProblemMappingTest {

    @TestFactory
    Stream<DynamicTest> everyDomainExceptionSubtypeIsMapped() {
        Set<Class<?>> handled = exceptionHandlerSignatures();
        Class<?>[] subtypes = DomainException.class.getPermittedSubclasses();
        assertThat(subtypes).isNotEmpty();

        return Arrays.stream(subtypes)
                .map(sub -> DynamicTest.dynamicTest(sub.getSimpleName() + " has a handler", () -> {
                    boolean covered = handled.stream().anyMatch(t -> t.isAssignableFrom(sub));
                    assertThat(covered)
                            .as("%s has no @ExceptionHandler in ProblemExceptionHandler — add one", sub.getName())
                            .isTrue();
                }));
    }

    private static Set<Class<?>> exceptionHandlerSignatures() {
        Method[] methods = ProblemExceptionHandler.class.getDeclaredMethods();
        Set<Class<?>> handled = new HashSet<>();
        for (Method m : methods) {
            var ann = m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            if (ann == null) continue;
            Class<? extends Throwable>[] declared = ann.value();
            if (declared.length > 0) handled.addAll(List.of(declared));
            else if (m.getParameterCount() > 0 && Throwable.class.isAssignableFrom(m.getParameterTypes()[0])) {
                handled.add(m.getParameterTypes()[0]);
            }
        }
        return handled;
    }
}
