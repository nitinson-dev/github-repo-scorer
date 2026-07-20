package com.nitinson.reposcorer.github;

import java.util.List;

/**
 * The outcome of a repository search: the repositories collected, and whether
 * the result is known to be incomplete — either because GitHub flagged partial
 * data, or because a later page failed after earlier ones had been fetched.
 */
public record GitHubSearchResult(List<GitHubRepository> repositories, boolean incomplete) {
}
