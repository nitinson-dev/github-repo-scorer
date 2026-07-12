package com.nitinson.reposcorer.github;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A repository as returned by the GitHub search endpoint. Only the fields the
 * scorer needs are mapped; everything else in the payload is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepository(
        @JsonProperty("full_name") String fullName,
        @JsonProperty("html_url") String url,
        String language,
        @JsonProperty("stargazers_count") int stars,
        @JsonProperty("forks_count") int forks,
        @JsonProperty("pushed_at") Instant lastPushed) {
}
