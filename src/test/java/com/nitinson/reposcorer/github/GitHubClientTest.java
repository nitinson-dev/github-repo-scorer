package com.nitinson.reposcorer.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nitinson.reposcorer.exception.GitHubApiException;
import com.nitinson.reposcorer.exception.GitHubUnavailableException;

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
        List<GitHubRepository> repositories = client.searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10);

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
        List<GitHubRepository> repositories = client.searchRepositories(LocalDate.parse("2023-01-01"), null, 3);

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

        GitHubProperties shortReadTimeout = new GitHubProperties(server.baseUrl(), "", 100, 100,
                Duration.ofSeconds(5), Duration.ofMillis(100));

        assertThatThrownBy(() -> clientWith(shortReadTimeout)
                .searchRepositories(LocalDate.parse("2023-01-01"), "Java", 10))
                .isInstanceOf(GitHubUnavailableException.class);
    }

    private GitHubClient clientWith(GitHubProperties properties) {
        return new GitHubClient(RestClient.builder(), properties);
    }

    private GitHubProperties properties(String token, int pageSize) {
        return new GitHubProperties(server.baseUrl(), token, pageSize, 100,
                Duration.ofSeconds(5), Duration.ofSeconds(10));
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
