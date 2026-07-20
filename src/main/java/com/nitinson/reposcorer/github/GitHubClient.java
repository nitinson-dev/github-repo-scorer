package com.nitinson.reposcorer.github;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.nitinson.reposcorer.exception.GitHubApiException;
import com.nitinson.reposcorer.exception.GitHubRateLimitException;
import com.nitinson.reposcorer.exception.GitHubUnavailableException;

@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final RestClient restClient;
    private final GitHubProperties properties;

    public GitHubClient(RestClient.Builder builder, GitHubProperties properties) {
        this.properties = properties;

        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        RestClient.Builder configured = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

        if (properties.hasToken()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
        }

        this.restClient = configured.build();
    }

    /**
     * Fetches repositories matching the given filters, paging through the search
     * results until {@code limit} repositories are collected or GitHub runs out.
     */
    public List<GitHubRepository> searchRepositories(LocalDate createdAfter, String language, int limit) {
        String query = buildQuery(createdAfter, language);

        List<GitHubRepository> repositories = new ArrayList<>();
        int page = 1;
        while (repositories.size() < limit) {
            GitHubSearchResponse response = fetchPage(query, page);
            if (response != null && response.incompleteResults()) {
                // GitHub timed out server-side and returned partial data with a
                // 200; flag it so a truncated result isn't mistaken for complete.
                log.warn("GitHub reported incomplete search results for query [{}] on page {}; "
                        + "returned data may be partial", query, page);
            }
            if (response == null || response.items() == null || response.items().isEmpty()) {
                break;
            }
            repositories.addAll(response.items());
            boolean lastPage = response.items().size() < properties.pageSize();
            if (lastPage) {
                break;
            }
            page++;
        }

        return repositories.size() > limit ? new ArrayList<>(repositories.subList(0, limit)) : repositories;
    }

    private GitHubSearchResponse fetchPage(String query, int page) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/repositories")
                            .queryParam("q", query)
                            .queryParam("per_page", properties.pageSize())
                            .queryParam("page", page)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GitHubSearchResponse.class);
        } catch (RestClientResponseException e) {
            if (isRateLimited(e)) {
                throw new GitHubRateLimitException(retryAfter(e.getResponseHeaders()),
                        "GitHub search rate limit exhausted", e);
            }
            throw new GitHubApiException(e.getStatusCode(),
                    "GitHub search returned " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new GitHubUnavailableException("GitHub search could not be completed", e);
        } catch (RestClientException e) {
            // A 2xx whose body we cannot deserialize (malformed or unexpected
            // payload) is an upstream contract failure, not our bug: report it as a
            // bad gateway instead of letting it fall through to a generic 500.
            throw new GitHubApiException(HttpStatus.BAD_GATEWAY, "GitHub returned an unreadable response", e);
        }
    }

    private static boolean isRateLimited(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return true;
        }
        // A primary-rate-limit breach comes back as a 403 with the remaining quota
        // at zero, which is what distinguishes it from an ordinary 403.
        HttpHeaders headers = e.getResponseHeaders();
        return status == HttpStatus.FORBIDDEN.value()
                && headers != null
                && "0".equals(headers.getFirst("X-RateLimit-Remaining"));
    }

    /**
     * The wait GitHub advises before retrying: {@code Retry-After} (delta seconds,
     * used for secondary limits) takes precedence over {@code X-RateLimit-Reset}
     * (an epoch second, used for primary limits). Null when neither is usable.
     */
    private static Duration retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            return positiveSeconds(retryAfter);
        }
        String reset = headers.getFirst("X-RateLimit-Reset");
        return reset == null ? null : secondsUntilEpoch(reset);
    }

    private static Duration positiveSeconds(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            // Retry-After can also be an HTTP-date; GitHub sends seconds, so we
            // don't parse the date form and simply advise no specific wait.
            return null;
        }
    }

    private static Duration secondsUntilEpoch(String epochSecond) {
        try {
            long delta = Long.parseLong(epochSecond.trim()) - Instant.now().getEpochSecond();
            return delta > 0 ? Duration.ofSeconds(delta) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String buildQuery(LocalDate createdAfter, String language) {
        StringBuilder query = new StringBuilder("created:>=").append(createdAfter);
        if (language != null && !language.isBlank()) {
            query.append(" language:").append(language);
        }
        return query.toString();
    }
}
