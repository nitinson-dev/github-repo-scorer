package com.nitinson.reposcorer.api;

import java.time.Instant;
import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nitinson.reposcorer.github.GitHubClient;
import com.nitinson.reposcorer.github.GitHubRepository;
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
    public List<ScoredRepository> search(@Valid @ParameterObject RepositorySearchRequest request) {
        List<GitHubRepository> repositories = gitHubClient.searchRepositories(
                request.createdAfter(), request.language(), request.limit());
        return scoringService.scoreAndRank(repositories, Instant.now());
    }
}
