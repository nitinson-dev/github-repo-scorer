# GitHub Repository Popularity Scorer

A Spring Boot backend that fetches repositories from the GitHub
[search API](https://docs.github.com/en/rest/search/search#search-repositories)
and assigns each a **popularity score** (0–100) derived from stars, forks, and
update recency.

## Requirements

- Java 21+
- Maven 3.9+
- (Optional) A GitHub personal access token to raise the search rate limit
  from 10 to 30 requests/min. Export it as `GITHUB_TOKEN`.

## Run

```bash
mvn spring-boot:run
```

The service starts on `http://localhost:8080`.

## API

```
GET /api/repositories?createdAfter=YYYY-MM-DD&language=Java&limit=N
```

| Param          | Required | Description                                            |
|----------------|----------|--------------------------------------------------------|
| `createdAfter` | yes      | Earliest repository creation date (ISO `YYYY-MM-DD`).  |
| `language`     | no       | Filter by primary language (e.g. `Java`).              |
| `limit`        | no       | Max repositories to return (default from config).      |

Example:

```bash
curl "http://localhost:8080/api/repositories?createdAfter=2023-01-01&language=Java&limit=20"
```

Returns repositories ranked by descending score:

```json
[
  {
    "fullName": "owner/repo",
    "url": "https://github.com/owner/repo",
    "language": "Java",
    "stars": 42000,
    "forks": 8000,
    "lastPushed": "2026-07-01T12:00:00Z",
    "score": 91.4
  }
]
```

Errors come back with a consistent body (`timestamp`, `status`, `error`,
`message`, `path`) and the following status mapping:

| Status | When                                                              |
|--------|-------------------------------------------------------------------|
| `400`  | The request is invalid (missing/malformed `createdAfter`, `limit` out of range). |
| `422`  | GitHub rejected the search query (echoed from GitHub's own `422`). |
| `502`  | GitHub returned an error (e.g. `503`) or was otherwise unreachable. |
| `504`  | The GitHub request timed out.                                     |

## Scoring

Each repository receives a weighted popularity score on a scale of 0–100.

Stars and forks use logarithmic normalization, `log1p(v) / log1p(reference)`,
where `reference` is roughly the largest star/fork count on GitHub today. The log
gives diminishing returns so a handful of mega-repos don't dominate, and because
it's an absolute curve rather than a batch-relative one, a repo's score doesn't
change depending on what else came back in the same search. There's no hard
plateau: a repo near the reference lands close to 1.0 without every large repo
collapsing onto the same value. The factor is clamped at 1 for the rare repo that
exceeds the reference.

Recency is a half-life decay on `pushed_at`: `0.5 ^ (ageDays / halfLife)`, so a
repo last pushed one half-life ago scores 0.5 on the recency factor.

The final score is `100 * (wStars*starsN + wForks*forksN + wRecency*recencyN)`.
The weights sum to 1 so that, with each normalized factor in `[0, 1]`, the score
stays bounded within `[0, 100]` regardless of the inputs. Weights, reference
counts, and the recency half-life all live in `application.yml`.

The score is computed as a `double` and only rounded for display, so repos that
look tied on the rounded value usually aren't when ranked. Where scores are
genuinely equal, ranking falls back to stars, then forks, then most recent push
for a stable ordering.

The weights and normalization parameters are hand-picked heuristics, not learned
from data — they aim for rankings that are intuitive and easy to tune. A
production system would calibrate them against real usage or feedback, or move to
a learned ranking model (learning-to-rank) if the extra machinery earned its keep.

## Scaling considerations

The service holds no server-side state, so it scales out horizontally behind a
load balancer. GitHub is the bottleneck rather than the app itself: search is
capped at 30 req/min with a token (10 without), so for heavier traffic the
obvious next step is caching responses (e.g. Caffeine) keyed on the query
params. Caching would also soften GitHub's rate limit; surfacing GitHub's own
rate-limit signal (`Retry-After`) back to callers as a `429` would be the follow-up.
