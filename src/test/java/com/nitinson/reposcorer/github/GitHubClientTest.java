package com.nitinson.reposcorer.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nitinson.reposcorer.exception.GitHubApiException;
import com.nitinson.reposcorer.exception.GitHubCircuitOpenException;
import com.nitinson.reposcorer.exception.GitHubRateLimitException;
import com.nitinson.reposcorer.exception.GitHubUnavailableException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class GitHubClientTest {

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void mapsSearchResultsAndSendsFilters() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(okJson("""
                        {
                          "total_count": 2,
                          "items": [
                            {
                              "full_name": "owner/one",
                              "html_url": "https://github.com/owner/one",
                              "language": "Java",
                              "stargazers_count": 1200,
                              "forks_count": 340,
                              "pushed_at": "2026-07-01T12:00:00Z"
                            },
                            {
                              "full_name": "owner/two",
                              "html_url": "https://github.com/owner/two",
                              "language": "Java",
                              "stargazers_count": 90,
                              "forks_count": 12,
                              "pushed_at": "2026-06-15T08:30:00Z"
                            }
                          ]
                        }
                        """)));

        GitHubClient client = clientWith(properties("secret-token", 100));
        List<GitHubRepository> repositories =
                client.searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10).repositories();

        assertThat(repositories).hasSize(2);
        assertThat(repositories.getFirst())
                .isEqualTo(new GitHubRepository("owner/one", "https://github.com/owner/one", "Java",
                        1200, 340, Instant.parse("2026-07-01T12:00:00Z")));

        server.verify(getRequestedFor(urlPathEqualTo("/search/repositories"))
                .withQueryParam("q", equalTo("created:>=2023-01-01 language:Java"))
                .withQueryParam("per_page", equalTo("100"))
                .withQueryParam("page", equalTo("1"))
                .withHeader("Authorization", equalTo("Bearer secret-token")));
    }

    @Test
    void omitsLanguageFilterWhenNotGiven() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(okJson("{ \"total_count\": 0, \"items\": [] }")));

        clientWith(properties("", 100)).searchRepositories(LocalDate.parse("2024-05-01"), null, 10);

        server.verify(getRequestedFor(urlPathEqualTo("/search/repositories"))
                .withQueryParam("q", equalTo("created:>=2024-05-01")));
    }

    @Test
    void pagesUntilLimitIsReached() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson(pageOf("a/1", "a/2"))));
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(okJson(pageOf("b/1", "b/2"))));

        GitHubClient client = clientWith(properties("", 2));
        List<GitHubRepository> repositories =
                client.searchRepositories(LocalDate.parse("2023-01-01"), null, 3).repositories();

        assertThat(repositories).extracting(GitHubRepository::fullName)
                .containsExactly("a/1", "a/2", "b/1");
    }

    @Test
    void translatesValidationErrorToGitHubApiException() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Validation Failed\"}")));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOfSatisfying(GitHubApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(422));
    }

    @Test
    void translatesServiceUnavailableToGitHubApiException() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOfSatisfying(GitHubApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(503));
    }

    @Test
    void translatesReadTimeoutToGitHubUnavailableException() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(okJson("{ \"total_count\": 0, \"items\": [] }").withFixedDelay(300)));

        GitHubProperties shortReadTimeout = new GitHubProperties(server.baseUrl(), "", 100,
                Duration.ofSeconds(5), Duration.ofMillis(100), List.of("Java"));

        assertThatThrownBy(() -> clientWith(shortReadTimeout)
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOf(GitHubUnavailableException.class);
    }

    @Test
    void returnsPartialResultsWhenALaterPageFails() {
        // Page one succeeds, page two fails: we keep the repositories we already
        // have rather than discarding them, and flag the result incomplete.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson(pageOf("a/1", "a/2"))));
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(aResponse().withStatus(503)));

        GitHubSearchResult result = clientWith(properties("", 2))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 4);

        assertThat(result.repositories()).extracting(GitHubRepository::fullName).containsExactly("a/1", "a/2");
        assertThat(result.incomplete()).isTrue();
    }

    @Test
    void throwsWhenTheVeryFirstPageFails() {
        // Nothing collected yet, so a failure is a real failure, not a partial:
        // surface it rather than returning an empty "complete" result.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOf(GitHubApiException.class);
    }

    @Test
    void opensCircuitAfterRepeatedFailures() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(503)));

        GitHubClient client = clientWith(properties("", 100));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                    .isInstanceOf(GitHubApiException.class);
        }

        // The sliding window is now full of failures, so the circuit is open: the
        // next call fails fast without touching GitHub at all.
        assertThatThrownBy(() -> client.searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOf(GitHubCircuitOpenException.class);

        // Two calls x three attempts reached GitHub; the short-circuited call adds none.
        server.verify(6, getRequestedFor(urlPathEqualTo("/search/repositories")));
    }

    @Test
    void retriesTransientFailureThenSucceeds() {
        // First attempt fails with a 503, the second returns data: the retry turns
        // a transient upstream blip into a successful call.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .inScenario("retry").whenScenarioStateIs("recovered")
                .willReturn(okJson("{ \"total_count\": 0, \"items\": [] }")));

        List<GitHubRepository> repositories = clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10).repositories();

        assertThat(repositories).isEmpty();
        server.verify(2, getRequestedFor(urlPathEqualTo("/search/repositories")));
    }

    @Test
    void doesNotRetryClientErrors() {
        // A 4xx is a permanent rejection of our request; retrying it only wastes
        // quota, so it must be attempted exactly once.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Validation Failed\"}")));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOf(GitHubApiException.class);
        server.verify(1, getRequestedFor(urlPathEqualTo("/search/repositories")));
    }

    @Test
    void doesNotRetryRateLimits() {
        // A rate-limit window is measured in minutes; retrying inside one request
        // would just block the caller, so we fail fast and let them back off.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOf(GitHubRateLimitException.class);
        server.verify(1, getRequestedFor(urlPathEqualTo("/search/repositories")));
    }

    @Test
    void translatesTooManyRequestsToRateLimitException() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "42")));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOfSatisfying(GitHubRateLimitException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(42)));
    }

    @Test
    void treatsForbiddenWithNoRemainingQuotaAsRateLimit() {
        // GitHub reports a primary-rate-limit breach as a 403 with the remaining
        // quota at zero, not a 429; that must not be mistaken for a plain auth error.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(403).withHeader("X-RateLimit-Remaining", "0")));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void treatsPlainForbiddenAsApiError() {
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOfSatisfying(GitHubApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(403));
    }

    @Test
    void mapsUnreadableBodyToBadGateway() {
        // A 200 whose body we cannot parse is upstream's fault, not ours: it must
        // surface as a bad gateway, never fall through to a generic 500.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(okJson("{ this is not valid json")));

        assertThatThrownBy(() -> clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOfSatisfying(GitHubApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(502));
    }

    @Test
    void warnsWhenGitHubReportsIncompleteResults() {
        // GitHub sets incomplete_results when its search backend times out and
        // returns partial data with a 200; we must not present that as complete.
        server.stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(okJson("""
                        {
                          "total_count": 1,
                          "incomplete_results": true,
                          "items": [
                            {
                              "full_name": "owner/one",
                              "html_url": "https://github.com/owner/one",
                              "language": "Java",
                              "stargazers_count": 10,
                              "forks_count": 2,
                              "pushed_at": "2026-07-01T12:00:00Z"
                            }
                          ]
                        }
                        """)));

        ListAppender<ILoggingEvent> logs = attachAppender(GitHubClient.class);

        GitHubSearchResult result = clientWith(properties("", 100))
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10);

        assertThat(result.incomplete()).isTrue();
        assertThat(logs.list)
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("incomplete"));
    }

    // Fast retry with negligible backoff, and a tiny circuit-breaker window, so
    // failure-path tests exercise the behaviour without slowing the suite down.
    private static final ResilienceProperties RESILIENCE =
            new ResilienceProperties(3, Duration.ofMillis(1), 1.0,
                    50.0f, 2, 2, Duration.ofSeconds(30));

    private GitHubClient clientWith(GitHubProperties properties) {
        return new GitHubClient(RestClient.builder(), properties, RESILIENCE);
    }

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private GitHubProperties properties(String token, int pageSize) {
        return new GitHubProperties(server.baseUrl(), token, pageSize,
                Duration.ofSeconds(5), Duration.ofSeconds(10), List.of("Java"));
    }

    private static String pageOf(String firstName, String secondName) {
        return """
                {
                  "total_count": 4,
                  "items": [
                    %s,
                    %s
                  ]
                }
                """.formatted(item(firstName), item(secondName));
    }

    private static String item(String fullName) {
        return """
                {
                  "full_name": "%s",
                  "html_url": "https://github.com/%s",
                  "language": "Java",
                  "stargazers_count": 100,
                  "forks_count": 10,
                  "pushed_at": "2026-01-01T00:00:00Z"
                }
                """.formatted(fullName, fullName);
    }
}
