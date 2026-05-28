package com.example.fintech.accounts.api;

import com.example.fintech.accounts.domain.exception.DomainException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemMappingTest {

    @TestFactory
    Stream<DynamicTest> everyDomainExceptionSubtypeIsMapped() {
        Set<Class<?>> handled = exceptionHandlerSignatures();
        Class<?>[] subtypes = DomainException.class.getPermittedSubclasses();
        assertThat(subtypes).isNotEmpty();

        return Arrays.stream(subtypes)
                .map(sub -> DynamicTest.dynamicTest(sub.getSimpleName() + " is handled", () -> {
                    boolean covered = handled.stream().anyMatch(handlerType -> handlerType.isAssignableFrom(sub));
                    assertThat(covered).as("%s has no @ExceptionHandler mapping", sub.getName()).isTrue();
                }));
    }

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
