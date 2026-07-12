package com.nitinson.reposcorer.github;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        String baseUrl,
        String token,
        int pageSize,
        int maxResults,
        Duration connectTimeout,
        Duration readTimeout) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
