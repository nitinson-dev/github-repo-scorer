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
        return supportedLanguages != null && supportedLanguages.contains(language);
    }
}
