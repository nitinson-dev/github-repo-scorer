package com.nitinson.reposcorer.api.validation;

import com.nitinson.reposcorer.github.GitHubProperties;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Spring-managed validator so the supported-language set can come from
 * {@link GitHubProperties} rather than being hard-coded in the annotation.
 */
public class SupportedLanguageValidator implements ConstraintValidator<SupportedLanguage, String> {

    private final GitHubProperties properties;

    public SupportedLanguageValidator(GitHubProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return properties.isSupportedLanguage(value);
    }
}
