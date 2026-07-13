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

## Run with Docker

A multi-stage build compiles the jar and ships it on a slim JRE image, so you
don't need Java or Maven installed locally.

```bash
docker build -t github-repo-scorer .
docker run -p 8080:8080 github-repo-scorer
```

To raise the GitHub rate limit, pass a token through:

```bash
docker run -p 8080:8080 -e GITHUB_TOKEN=your_token github-repo-scorer
```

## API

```
GET /api/repositories?createdAfter=YYYY-MM-DD&language=Java&limit=N
```

| Param          | Required | Description                                            |
|----------------|----------|--------------------------------------------------------|
| `createdAfter` | yes      | Earliest repository creation date (ISO `YYYY-MM-DD`).  |
| `language`     | no       | Filter by primary language (e.g. `Java`). Validated — see below. |
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

When GitHub's search backend times out it returns *partial* results with a `200`
and `"incomplete_results": true`. The client logs a warning in that case so a
truncated result set isn't silently mistaken for a complete one.

### Language filtering

GitHub's search API silently ignores an unrecognized `language` qualifier and
returns results across *all* languages rather than erroring, so a typo like
`language=Java222` would come back looking filtered when it isn't. To keep that
upstream quirk from leaking into our contract, the `language` value is validated
against a configured set (`github.supported-languages` in `application.yml`) and
an unknown value returns `400`. The list is a pragmatic subset of common
languages, not GitHub's full Linguist catalog — extending it to the full set is
a deliberate follow-up, not an oversight. Matching is exact, so `Java` is
accepted and `java` is not.

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
stays bounded within `[0, 100]` regardless of the inputs. That invariant is
enforced: the service refuses to start if `scoring.weights` don't sum to 1.
Weights, reference counts, and the recency half-life all live in
`application.yml`.

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
load balancer, which probes `GET /actuator/health` (exposed via Spring Boot
Actuator) to route only to healthy instances. GitHub is the bottleneck rather
than the app itself: search is
capped at 30 req/min with a token (10 without), so for heavier traffic the
obvious next step is caching responses (e.g. Caffeine) keyed on the query
params. Caching would also soften GitHub's rate limit; surfacing GitHub's own
rate-limit signal (`Retry-After`) back to callers as a `429` would be the follow-up.

## Known trade-offs

Edge cases I identified but deliberately left as-is, to keep the scope
proportionate to the exercise:

- **`limit` validation ceiling vs. actual cap.** `limit` is validated against a
  fixed `@Max(100)`, while the effective cap is `github.max-results`. If
  `max-results` is lowered below 100, a caller asking for more is silently
  truncated with no signal. These two bounds should be the same number, and
  truncation should probably be surfaced (e.g. a header) rather than silent.
- **Pagination stability.** With the default config the client only ever fetches
  a single page, so this is currently moot. If `github.page-size` were set below
  `limit`, the client would page through GitHub's best-match ordering, which
  isn't guaranteed stable across requests — so results could contain duplicates
  or gaps. There's no cross-page de-duplication.
- **Future / far-past `createdAfter`.** A future date is accepted and simply
  yields an empty result; there's no `@PastOrPresent` guard. This seems
  defensible rather than worth rejecting.
- **`github.page-size > 100`.** Not validated; GitHub caps `per_page` at 100 and
  would reject a larger value at runtime with a `422`.
