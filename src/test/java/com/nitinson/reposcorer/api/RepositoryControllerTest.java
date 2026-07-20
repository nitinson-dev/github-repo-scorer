package com.nitinson.reposcorer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    void defaultsLimitTo100WhenOmitted() throws Exception {
        when(scoringService.scoreAndRank(anyList(), any(Instant.class))).thenReturn(List.of());

        mvc.perform(get("/api/repositories")
                        .param("createdAfter", "2023-01-01")
                        .param("language", "Java"))
                .andExpect(status().isOk());

        verify(gitHubClient).searchRepositories(eq(LocalDate.parse("2023-01-01")), eq("Java"), eq(100));
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
                        .param("limit", "501"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsupportedLanguage() throws Exception {
        // GitHub would silently ignore an unknown language and return unfiltered
        // results; we reject it up front instead of passing that quirk through.
        mvc.perform(get("/api/repositories")
                        .param("createdAfter", "2023-01-01")
                        .param("language", "Java222"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/repositories"));
    }

    @Test
    void rejectsFutureCreatedAfter() throws Exception {
        // A future creation date can only ever match zero repositories; reject it
        // up front rather than making a pointless upstream call.
        mvc.perform(get("/api/repositories")
                        .param("createdAfter", "2999-01-01")
                        .param("language", "Java"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void acceptsLanguageRegardlessOfCase() throws Exception {
        // GitHub matches the language qualifier case-insensitively, so "java" is a
        // valid filter; rejecting it would be an inconsistency we impose ourselves.
        when(scoringService.scoreAndRank(anyList(), any(Instant.class))).thenReturn(List.of());

        mvc.perform(get("/api/repositories")
                        .param("createdAfter", "2023-01-01")
                        .param("language", "java"))
                .andExpect(status().isOk());
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
            return new GitHubProperties("https://api.github.com", "", 100,
                    Duration.ofSeconds(5), Duration.ofSeconds(10), List.of("Java"));
        }
    }
}
