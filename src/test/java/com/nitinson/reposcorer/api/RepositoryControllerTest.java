package com.nitinson.reposcorer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nitinson.reposcorer.exception.GitHubApiException;
import com.nitinson.reposcorer.exception.GitHubUnavailableException;
import com.nitinson.reposcorer.github.GitHubClient;
import com.nitinson.reposcorer.github.GitHubProperties;
import com.nitinson.reposcorer.scoring.ScoredRepository;
import com.nitinson.reposcorer.scoring.ScoringService;

@WebMvcTest(RepositoryController.class)
@Import(RepositoryControllerTest.TestProperties.class)
class RepositoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GitHubClient gitHubClient;

    @MockitoBean
    private ScoringService scoringService;

    @Test
    void returnsRankedRepositories() throws Exception {
        when(gitHubClient.searchRepositories(eq(LocalDate.parse("2023-01-01")), eq("Java"), anyInt()))
                .thenReturn(List.of());
        when(scoringService.scoreAndRank(anyList(), any(Instant.class)))
                .thenReturn(List.of(new ScoredRepository("owner/one", "https://github.com/owner/one",
                        "Java", 1200, 340, Instant.parse("2026-07-01T12:00:00Z"), 91.4)));

        mvc.perform(get("/api/repositories")
                        .param("createdAfter", "2023-01-01")
                        .param("language", "Java")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("owner/one"))
                .andExpect(jsonPath("$[0].score").value(91.4));
    }

    @Test
    void rejectsMissingCreatedAfter() throws Exception {
        mvc.perform(get("/api/repositories").param("language", "Java"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/repositories"));
    }

    @Test
    void rejectsMalformedCreatedAfter() throws Exception {
        mvc.perform(get("/api/repositories").param("createdAfter", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsLimitAboveMaximum() throws Exception {
        mvc.perform(get("/api/repositories")
                        .param("createdAfter", "2023-01-01")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsGitHubValidationErrorToUnprocessableEntity() throws Exception {
        when(gitHubClient.searchRepositories(any(), any(), anyInt()))
                .thenThrow(new GitHubApiException(HttpStatus.UNPROCESSABLE_ENTITY, "rejected query", null));

        mvc.perform(get("/api/repositories").param("createdAfter", "2023-01-01"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void mapsGitHubServerErrorToBadGateway() throws Exception {
        when(gitHubClient.searchRepositories(any(), any(), anyInt()))
                .thenThrow(new GitHubApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null));

        mvc.perform(get("/api/repositories").param("createdAfter", "2023-01-01"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void mapsTimeoutToGatewayTimeout() throws Exception {
        when(gitHubClient.searchRepositories(any(), any(), anyInt()))
                .thenThrow(new GitHubUnavailableException("timed out", null));

        mvc.perform(get("/api/repositories").param("createdAfter", "2023-01-01"))
                .andExpect(status().isGatewayTimeout());
    }

    @TestConfiguration
    static class TestProperties {
        @Bean
        GitHubProperties gitHubProperties() {
            return new GitHubProperties("https://api.github.com", "", 100, 100,
                    Duration.ofSeconds(5), Duration.ofSeconds(10));
        }
    }
}
