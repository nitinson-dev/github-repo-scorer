package com.nitinson.reposcorer.api;

import java.time.LocalDate;

import com.nitinson.reposcorer.api.validation.SupportedLanguage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

public record RepositorySearchRequest(
        // A future date matches nothing on GitHub, so reject it rather than making
        // a guaranteed-empty upstream call.
        @NotNull @PastOrPresent LocalDate createdAfter,
        @SupportedLanguage String language,
        // Capped at 500 — the largest request that reliably completes in testing.
        // GitHub's own search ceiling is 1000, but fetching that needs ten
        // sequential page calls, which trips GitHub's secondary rate limit even
        // while under the primary quota; 500 (five pages) stays clear of it. The
        // default stays modest since each 100 costs one page call (see below).
        @Min(1) @Max(500) Integer limit) {

    private static final int DEFAULT_LIMIT = 100;

    public RepositorySearchRequest {
        // Default before validation runs, so the default is bound by the same
        // @Min/@Max as an explicit value — the range and its default live here,
        // in one place, with no second cap elsewhere to drift out of sync.
        if (limit == null) {
            limit = DEFAULT_LIMIT;
        }
    }
}
