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

## Scoring

Each repository receives a weighted popularity score on a scale of 0–100.

Stars and forks use logarithmic normalization with an upper saturation
threshold, `min(1, log1p(v) / log1p(SAT))`. This keeps a handful of mega-repos
from dominating, and since it's an absolute curve rather than a batch-relative
one, a repo's score doesn't change depending on what else came back in the same
search.

Recency is an exponential decay on `pushed_at`: `exp(-ageDays / halfLife)`.

The final score is `100 * (wStars*starsN + wForks*forksN + wRecency*recencyN)`.
The weights sum to 1 so that, with each normalized factor in `[0, 1]`, the score
stays bounded within `[0, 100]` regardless of the inputs. Weights, saturation
ceilings, and the recency half-life all live in `application.yml`.

## Scaling considerations

The service holds no server-side state, so it scales out horizontally behind a
load balancer. GitHub is the bottleneck rather than the app itself: search is
capped at 30 req/min with a token (10 without), so for heavier traffic the
obvious next step is caching responses (e.g. Caffeine) keyed on the query
params. Rate-limit responses from GitHub are surfaced to the caller as `429`.
