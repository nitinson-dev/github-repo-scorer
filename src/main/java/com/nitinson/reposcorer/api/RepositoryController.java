package com.nitinson.reposcorer.api;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nitinson.reposcorer.github.GitHubClient;
import com.nitinson.reposcorer.github.GitHubProperties;
import com.nitinson.reposcorer.github.GitHubRepository;
import com.nitinson.reposcorer.scoring.ScoredRepository;
import com.nitinson.reposcorer.scoring.ScoringService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final GitHubClient gitHubClient;
    private final ScoringService scoringService;
    private final GitHubProperties gitHubProperties;

    public RepositoryController(GitHubClient gitHubClient, ScoringService scoringService,
                                GitHubProperties gitHubProperties) {
        this.gitHubClient = gitHubClient;
        this.scoringService = scoringService;
        this.gitHubProperties = gitHubProperties;
    }

    @GetMapping
    public List<ScoredRepository> search(@Valid RepositorySearchRequest request) {
        int limit = request.limit() != null ? request.limit() : gitHubProperties.maxResults();
        List<GitHubRepository> repositories =
                gitHubClient.searchRepositories(request.createdAfter(), request.language(), limit);
        return scoringService.scoreAndRank(repositories, Instant.now());
    }
}
