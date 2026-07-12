package com.nitinson.reposcorer.github;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.nitinson.reposcorer.exception.GitHubApiException;
import com.nitinson.reposcorer.exception.GitHubUnavailableException;

@Component
public class GitHubClient {

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
        int target = Math.min(limit, properties.maxResults());
        String query = buildQuery(createdAfter, language);

        List<GitHubRepository> repositories = new ArrayList<>();
        int page = 1;
        while (repositories.size() < target) {
            GitHubSearchResponse response = fetchPage(query, page);
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

        return repositories.size() > target ? new ArrayList<>(repositories.subList(0, target)) : repositories;
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
            throw new GitHubApiException(e.getStatusCode(),
                    "GitHub search returned " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new GitHubUnavailableException("GitHub search could not be completed", e);
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
