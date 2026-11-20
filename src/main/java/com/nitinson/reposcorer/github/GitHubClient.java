package com.nitinson.reposcorer.github;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.nitinson.reposcorer.exception.GitHubCircuitOpenException;
import com.nitinson.reposcorer.exception.GitHubRateLimitException;
import com.nitinson.reposcorer.exception.GitHubUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final RestClient restClient;
    private final GitHubProperties properties;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Duration openStateWait;
    private final Duration interPageDelay;
    private final Sleeper sleeper;

    @Autowired
    public GitHubClient(RestClient.Builder builder, GitHubProperties properties, ResilienceProperties resilience) {
        this(builder, properties, resilience, duration -> Thread.sleep(duration.toMillis()));
    }

    // Package-private: lets tests substitute the pacing sleep so it can be asserted
    // without the suite actually waiting.
    GitHubClient(RestClient.Builder builder, GitHubProperties properties, ResilienceProperties resilience,
                 Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;

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
        this.retry = buildRetry(resilience);
        this.circuitBreaker = buildCircuitBreaker(resilience);
        this.openStateWait = resilience.waitInOpenState();
        this.interPageDelay = resilience.interPageDelay();
    }

    /** Pause before fetching a subsequent page; abstracted so tests needn't wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static Retry buildRetry(ResilienceProperties resilience) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(resilience.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        resilience.initialBackoff(), resilience.backoffMultiplier()))
                .retryOnException(GitHubClient::isRetryable)
                .build();
        return Retry.of("github-search", config);
    }

    private static CircuitBreaker buildCircuitBreaker(ResilienceProperties resilience) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(resilience.slidingWindowSize())
                .minimumNumberOfCalls(resilience.minimumNumberOfCalls())
                .failureRateThreshold(resilience.failureRateThreshold())
                .waitDurationInOpenState(resilience.waitInOpenState())
                // Only transient availability failures should trip the circuit; a
                // 4xx or a rate limit says nothing about whether GitHub is down.
                .recordException(GitHubClient::isRetryable)
                .build();
        return CircuitBreaker.of("github-search", config);
    }

    /**
     * Only transient failures are worth retrying: connection/read failures and
     * upstream 5xx. A 4xx is a permanent rejection, and a rate limit is handled by
     * failing fast with Retry-After rather than blocking inside the request.
     */
    private static boolean isRetryable(Throwable t) {
        if (t instanceof GitHubUnavailableException) {
            return true;
        }
        return t instanceof GitHubApiException api && api.status().is5xxServerError();
    }

    /**
     * Fetches repositories matching the given filters, paging through the search
     * results until {@code limit} repositories are collected or GitHub runs out.
     */
    public GitHubSearchResult searchRepositories(LocalDate createdAfter, String language, int limit) {
        String query = buildQuery(createdAfter, language);

        List<GitHubRepository> repositories = new ArrayList<>();
        boolean incomplete = false;
        int page = 1;
        while (repositories.size() < limit) {
            if (page > 1) {
                pace();
            }
            GitHubSearchResponse response;
            try {
                response = fetchPage(query, page);
            } catch (RuntimeException e) {
                if (repositories.isEmpty()) {
                    // Nothing collected yet: this is a real failure, not a partial
                    // result, so let it propagate to the proper error mapping.
                    throw e;
                }
                // We already have earlier pages; return them flagged incomplete
                // rather than discarding good data because a later page failed.
                log.warn("GitHub page {} for query [{}] failed after {} repositories; returning partial results",
                        page, query, repositories.size(), e);
                incomplete = true;
                break;
            }
            if (response != null && response.incompleteResults()) {
                // GitHub timed out server-side and returned partial data with a
                // 200; flag it so a truncated result isn't mistaken for complete.
                log.warn("GitHub reported incomplete search results for query [{}] on page {}; "
                        + "returned data may be partial", query, page);
                incomplete = true;
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

        List<GitHubRepository> collected = repositories.size() > limit
                ? new ArrayList<>(repositories.subList(0, limit))
                : repositories;
        return new GitHubSearchResult(collected, incomplete);
    }

    private void pace() {
        if (interPageDelay.isZero() || interPageDelay.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(interPageDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubUnavailableException("Interrupted while paging GitHub results", e);
        }
    }

    private GitHubSearchResponse fetchPage(String query, int page) {
        // Circuit breaker wraps retry: it records one outcome per fetch (after
        // retries) and, once open, short-circuits before any attempt is made.
        Supplier<GitHubSearchResponse> guarded = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, () -> fetchPageOnce(query, page)));
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new GitHubCircuitOpenException(openStateWait, "GitHub circuit is open", e);
        }
    }

    private GitHubSearchResponse fetchPageOnce(String query, int page) {
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
