package com.nitinson.reposcorer.github;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        String baseUrl,
        String token,
        int pageSize,
        Duration connectTimeout,
        Duration readTimeout,
        List<String> supportedLanguages) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public boolean isSupportedLanguage(String language) {
        // GitHub matches the language qualifier case-insensitively, so we do too;
        // a case-only difference isn't a reason to reject an otherwise valid filter.
        return supportedLanguages != null
                && supportedLanguages.stream().anyMatch(supported -> supported.equalsIgnoreCase(language));
    }
}
