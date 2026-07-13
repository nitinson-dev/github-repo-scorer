package com.nitinson.reposcorer.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Constrains a language value to the set configured under {@code github.supported-languages}.
 * A null or blank value passes: the language filter is optional.
 */
@Documented
@Constraint(validatedBy = SupportedLanguageValidator.class)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedLanguage {

    String message() default "is not a supported language";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
