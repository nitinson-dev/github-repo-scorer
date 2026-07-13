package com.nitinson.reposcorer;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nitinson.reposcorer.scoring.ScoredRepository;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RepositoryScoringEndToEndTest {

    private static final WireMockServer github = new WireMockServer(options().dynamicPort());

    static {
        github.start();
    }

    @AfterAll
    static void stopGitHub() {
        github.stop();
    }

    @DynamicPropertySource
    static void gitHubBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("github.base-url", github::baseUrl);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void fetchesScoresAndRanksThroughTheWholeStack() {
        github.stubFor(get(urlPathEqualTo("/search/repositories")).willReturn(okJson("""
                {
                  "total_count": 2,
                  "items": [
                    {
                      "full_name": "owner/quiet",
                      "html_url": "https://github.com/owner/quiet",
                      "language": "Java",
                      "stargazers_count": 10,
                      "forks_count": 1,
                      "pushed_at": "2016-01-01T00:00:00Z"
                    },
                    {
                      "full_name": "owner/popular",
                      "html_url": "https://github.com/owner/popular",
                      "language": "Java",
                      "stargazers_count": 50000,
                      "forks_count": 8000,
                      "pushed_at": "2026-07-01T00:00:00Z"
                    }
                  ]
                }
                """)));

        ResponseEntity<ScoredRepository[]> response = rest.getForEntity(
                "/api/repositories?createdAfter=2015-01-01&language=Java&limit=10", ScoredRepository[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).extracting(ScoredRepository::fullName)
                .containsExactly("owner/popular", "owner/quiet");
        assertThat(response.getBody()[0].score()).isGreaterThan(response.getBody()[1].score());
        assertThat(response.getBody()[0].score()).isBetween(0.0, 100.0);

        github.verify(getRequestedFor(urlPathEqualTo("/search/repositories"))
                .withQueryParam("q", equalTo("created:>=2015-01-01 language:Java")));
    }

    @Test
    void exposesHealthEndpointForLoadBalancerProbes() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
