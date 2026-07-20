package com.nitinson.reposcorer.api;

import java.time.Instant;
import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nitinson.reposcorer.github.GitHubClient;
import com.nitinson.reposcorer.github.GitHubSearchResult;
import com.nitinson.reposcorer.scoring.ScoredRepository;
import com.nitinson.reposcorer.scoring.ScoringService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final GitHubClient gitHubClient;
    private final ScoringService scoringService;

    public RepositoryController(GitHubClient gitHubClient, ScoringService scoringService) {
        this.gitHubClient = gitHubClient;
        this.scoringService = scoringService;
    }

    @GetMapping
    public ResponseEntity<List<ScoredRepository>> search(@Valid @ParameterObject RepositorySearchRequest request) {
        GitHubSearchResult result = gitHubClient.searchRepositories(
                request.createdAfter(), request.language(), request.limit());
        List<ScoredRepository> ranked = scoringService.scoreAndRank(result.repositories(), Instant.now());

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.incomplete()) {
            // Let the caller distinguish a truncated fetch from a genuinely small
            // result set; the body still carries whatever we did collect.
            response.header("X-Incomplete-Results", "true");
        }
        return response.body(ranked);
    }
}
