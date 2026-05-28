package com.example.fintech.transactions.api;

import com.example.fintech.transactions.domain.exception.DomainException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflective gate: every concrete subtype of the sealed {@link DomainException} hierarchy must
 * have a matching {@code @ExceptionHandler} method in {@link ProblemExceptionHandler}.
 *
 * <p>Failure mode this prevents: a developer adds a new {@code DomainException} subtype (and
 * extends the {@code permits} clause), forgets to add the handler, and the service ends up
 * returning {@code 500 INTERNAL} for what should be a typed business error. The reflective check
 * catches it at build time.
 *
 * <p>Implementation: walk the sealed hierarchy via {@link Class#getPermittedSubclasses()};
 * for each subtype, assert at least one {@code @org.springframework.web.bind.annotation.ExceptionHandler}
 * method on the handler class declares that subtype.
 */
class ProblemMappingTest {

    @TestFactory
    Stream<DynamicTest> everyDomainExceptionSubtypeIsMapped() {
        Set<Class<?>> handled = exceptionHandlerSignatures();
        Class<?>[] subtypes = DomainException.class.getPermittedSubclasses();
        assertThat(subtypes)
                .as("DomainException is sealed; permittedSubclasses must be non-empty")
                .isNotEmpty();

        return Arrays.stream(subtypes)
                .map(sub -> DynamicTest.dynamicTest(sub.getSimpleName() + " has a handler", () -> {
                    boolean covered = handled.stream().anyMatch(handlerType -> handlerType.isAssignableFrom(sub));
                    assertThat(covered)
                            .as("%s has no @ExceptionHandler in ProblemExceptionHandler — add one"
                                    + " and a corresponding code/HTTP-status mapping",
                                    sub.getName())
                            .isTrue();
                }));
    }

    /**
     * Collect every exception type declared by a {@code @ExceptionHandler} on
     * {@link ProblemExceptionHandler} — both via the annotation's {@code value()} attribute and
     * the method's parameter type.
     */
    private static Set<Class<?>> exceptionHandlerSignatures() {
        Method[] methods = ProblemExceptionHandler.class.getDeclaredMethods();
        Set<Class<?>> handled = new java.util.HashSet<>();
        for (Method m : methods) {
            org.springframework.web.bind.annotation.ExceptionHandler ann =
                    m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            if (ann == null) {
                continue;
            }
            Class<? extends Throwable>[] declared = ann.value();
            if (declared.length > 0) {
                handled.addAll(List.of(declared));
            } else if (m.getParameterCount() > 0 && Throwable.class.isAssignableFrom(m.getParameterTypes()[0])) {
                handled.add(m.getParameterTypes()[0]);
            }
        }
        return handled;
    }
}
