package com.nitinson.reposcorer.api;

import java.time.LocalDate;

import com.nitinson.reposcorer.api.validation.SupportedLanguage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RepositorySearchRequest(
        @NotNull LocalDate createdAfter,
        @SupportedLanguage String language,
        @Min(1) @Max(100) Integer limit) {
}
