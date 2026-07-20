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

Interactive docs (Swagger UI) are at `/swagger-ui.html`, and the OpenAPI 3 spec
at `/v3/api-docs`.

| Param          | Required | Description                                            |
|----------------|----------|--------------------------------------------------------|
| `createdAfter` | yes      | Earliest repository creation date (ISO `YYYY-MM-DD`).  |
| `language`     | no       | Filter by primary language (e.g. `Java`). Validated — see below. |
| `limit`        | no       | Max repositories to return, 1–500 (default 100).       |

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
| `502`  | GitHub returned an unexpected error, or a `200` with an unreadable body. |
| `503`  | GitHub's rate limit is exhausted, or the circuit breaker is open. The response carries a `Retry-After` telling you when to try again. |
| `504`  | The GitHub request timed out.                                     |

### Resilience

Calls to GitHub are wrapped in retry and circuit-breaker policies (resilience4j,
configured under `github.resilience`):

- **Retry.** Transient failures — upstream `5xx` and timeouts — are retried with
  exponential backoff and jitter. A `4xx` (a permanent rejection) and a rate limit
  are *not* retried.
- **Circuit breaker.** Once the recent failure rate crosses a threshold, the
  service stops calling GitHub for a cool-off window and fails fast with `503`,
  rather than piling requests onto a struggling upstream. Only transient failures
  count toward the rate — a `4xx` or a rate limit says nothing about GitHub being
  down.
- **Rate limits.** GitHub signals these with a `429`, or a `403` with
  `X-RateLimit-Remaining: 0`; both are surfaced as `503` with GitHub's advised wait
  passed through as `Retry-After`.

### Partial results

When GitHub's search backend times out it returns *partial* data with a `200` and
`"incomplete_results": true`; likewise, when paging for a larger `limit`, a later
page can fail after earlier ones have succeeded. In both cases the service returns
what it did collect and sets an `X-Incomplete-Results: true` response header, so a
truncated result set is distinguishable from a genuinely small one. (A failure on
the *first* page, with nothing collected, surfaces as an error instead.)

### Language filtering

GitHub's search API silently ignores an unrecognized `language` qualifier and
returns results across *all* languages instead of erroring — so a typo like
`language=Java222` looks filtered when it isn't. To keep that quirk out of our
contract, `language` is validated against a configured set
(`github.supported-languages` in `application.yml`); an unknown value returns
`400`. The set is a pragmatic subset of common languages, not GitHub's full
Linguist catalog — extending it is a deliberate follow-up. Matching is
case-insensitive, mirroring GitHub's own behaviour: `Java` and `java` are both
accepted.

## Scoring

Each repository receives a weighted popularity score on a scale of 0–100.

Stars and forks use logarithmic normalization, `log1p(v) / log1p(reference)`,
where `reference` is roughly the largest star/fork count on GitHub today. The log
gives diminishing returns so a handful of mega-repos don't dominate. The curve is
absolute, not batch-relative, so a repo's score doesn't depend on what else came
back in the same search. There's no hard plateau — a repo near the reference
approaches 1.0 without large repos collapsing onto one value — and the factor is
clamped at 1 above the reference.

Recency is a half-life decay on `pushed_at`: `0.5 ^ (ageDays / halfLife)`, so a
repo last pushed one half-life ago scores 0.5 on the recency factor.

The final score is `100 * (wStars*starsN + wForks*forksN + wRecency*recencyN)`.
The weights sum to 1 so that, with each factor in `[0, 1]`, the score stays
within `[0, 100]`. That invariant is enforced — the service refuses to start if
`scoring.weights` don't sum to 1. Weights, reference counts, and the half-life
all live in `application.yml`.

The score is computed as a `double` and only rounded for display, so repos that
look tied on the rounded value usually aren't when ranked. Where scores are
genuinely equal, ranking falls back to stars, then forks, then most recent push
for a stable ordering.

The weights and normalization parameters are hand-picked heuristics, not learned
from data — chosen for rankings that are intuitive and easy to tune. A production
system would calibrate them against real usage, or move to a learned ranking
model if the extra machinery earned its keep.

## Scaling considerations

The service holds no server-side state, so it scales out horizontally behind a
load balancer, which probes `GET /actuator/health` (via Spring Boot Actuator) to
route only to healthy instances. GitHub, not the app, is the bottleneck: search
is capped at 30 req/min with a token (10 without). For heavier traffic the next
step is caching responses (e.g. Caffeine) keyed on the query params, which also
softens the rate limit. (GitHub's own `Retry-After` is already surfaced to callers
on a `503` — see [Resilience](#resilience) above.)

## Known trade-offs

Edge cases identified but deliberately left as-is, to keep scope proportionate to
the exercise:

- **`limit` capped at 500, no inter-page pacing.** GitHub's own search ceiling is
  1000, but `limit` is capped at **500** here — the largest that reliably completes
  in testing. At `page-size: 100`, `limit=1000` needs ten sequential page calls, and
  that burst trips GitHub's *secondary* rate limit (its anti-abuse guard) **even
  while the primary quota still has requests left**. 500 (five pages) stays clear.
  Transient failures are now retried with backoff, and rate limits are surfaced as
  `503` + `Retry-After` (see [Resilience](#resilience)), but the client does not yet
  *pace* its inter-page calls to avoid provoking the secondary limit in the first
  place; request pacing, response caching keyed on the query params, and
  client-facing pagination are the natural next steps. Within 500, the client scores
  the full set it fetches, so ranking is over every match GitHub surfaces up to that
  cap, not just the first page.
- **Pagination stability.** Moot under the default `limit` (one page). Larger
  limits use offset-based paging (`page=1,2,…`) over GitHub's best-match ordering,
  and since the pages are separate requests seconds apart, a shift in that ordering
  could in principle duplicate a boundary repo or skip one — and the client doesn't
  de-duplicate across pages. Low risk for a `created:>=` query, where churn lands at
  the low-relevance tail rather than the page boundaries we read; a guaranteed fix
  needs cursor pagination, which the REST search API doesn't offer (GraphQL does),
  so it rides along with the pagination work above.
- **`github.page-size > 100`.** Not validated; GitHub caps `per_page` at 100 and
  rejects a larger value at runtime with `422`.
