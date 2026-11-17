# Verification record — 2026-11-17

Executed on macOS arm64 with JDK 21.0.13, Maven 3.10 and local Docker PostgreSQL 17.6.

* `mvn -f backend/pom.xml test`: five JUnit tests, zero failures. Covers topic change, script stripping, basic unsafe
  term, token event binding and destination validation.
* `mvn -f backend/pom.xml package`: passed after live-test fixes for encoder registration and transaction-proxy field
  access.
* Flyway V1 migration: applied successfully to an empty PostgreSQL 17.6 database; validated on restart.
* `npm run build`: Next.js 16.4.6 / React 19.3.1 production build passed.
* `npm run typecheck`: passed.
* npm install audit after update: zero reported vulnerabilities at installation time; this is not a security assessment.
* `python3 tests/smoke.py`: passed against live PostgreSQL. 32 concurrent decisions compete for five available prices;
  exactly five fill. Twenty duplicate impression submissions commit once. Reconciliation agrees, unrelated content
  produces no fill and advertiser creative approval is forbidden.
* `python3 benchmarks/local.py`: 100 no-fill decisions, eight closed-loop clients, 1.209 seconds, 82.74 requests/sec;
  p50 92.85 ms, p95 103.15 ms, p99 112.65 ms; zero request errors. BCrypt authentication is included. This is not a
  throughput capacity benchmark, filled-decision benchmark or 50 ms SLO verification.

The smoke test is an actual HTTP/database concurrency check, not a Testcontainers suite. Multi-pod failure, daily
budgets, expiry races, replay recovery and fault injection remain unverified. The k6 script is provided but was not
executed at 1k/10k/50k/100k RPS. No measured quality corpus, tracing verification, Kubernetes deployment, Docker
application image build, or production readiness certification is claimed.

`PLAYWRIGHT_BROWSERS_PATH=/tmp/contextads-browsers npx playwright test --workers=1 --reporter=line`: one browser
interaction test passed in 2.9 seconds. It creates a campaign through the UI, approves it in the admin view, activates
it, renders the topic-relevant Sponsored creative, waits for the measurement response, and verifies unrelated article
no-fill. Chromium was installed into temporary storage. Initial test failed on an overly strict article locator;
corrected locator and explicit article accessible name were added. No screenshots substitute for these interactions.

## OIDC, verified inventory and daily pacing — continuation

The preceding initial-slice record is historical. Its Basic-auth instructions and benchmark do not apply to the updated
implementation.

Executed against the updated code:

*
`DOCKER_HOST=unix:///Users/MWGBH/.orbstack/run/docker.sock JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml verify`:
**24 tests passed**, zero failures/errors/skips. Twelve are Spring/PostgreSQL Testcontainers integration tests;
financial writes/locks are real. DNS and JWT decoder are mocked in integration tests to isolate domain logic, while a
separate RSA/Nimbus test verifies signatures and token validators without mocks.
* Security cases include Basic/header impersonation rejection, API-client role checks, cross-tenant campaign access,
  invalid signature, modified payload, wrong issuer/audience, expired and missing-expiry tokens.
* Domain cases include correct DNS proof, wrong/missing/expired challenges, exclusive active claims, takeover only after
  expiry, invalid URLs, exact-host checks, expired verification and foreign placement access.
* Accounting cases include 40 concurrent decisions competing for five daily reservations, 20 duplicate impression
  signals committing once, lifetime headroom across daily buckets, previous-day commitment, reservation expiry, parallel
  expiry-worker/event races and proportional pacing bounds/overflow.
* `npm run build` and production TypeScript compilation passed after the auth and daily-ledger UI changes.
* `PLAYWRIGHT_BROWSERS_PATH=/tmp/contextads-browsers npx playwright test --workers=1 --reporter=line`: **3 tests passed
  in 2.0 seconds**, using actual Keycloak authorization-code browser login. The advertiser creates a daily-paced
  campaign, tries an unauthorized admin view, and fails header spoofing. Publisher login registers a domain and sees
  pending verification. Anonymous and cross-origin proxy writes fail. Public session JSON does not disclose accessToken.
* `python3 tests/smoke.py`: passed with real Keycloak signed tokens and the actual backend/JWKS verification. Verifies
  forged-token rejection, advertiser ownership/role restrictions, persisted daily budget/pacing, unverified
  placement/page rejection, and actual DNS NXDOMAIN challenge rejection.
* V2 migration executed both against empty Testcontainers databases and the pre-existing local V1 database, retaining
  old decisions and movements.

Defects found and fixed during this continuation: Java GrantedAuthority generic type mismatch; old docker-java API
version rejected by Docker 29 (explicit API 1.44 used); inaccessible pacing label; empty security-error JSON causing
frontend parsing failure; repeat-run ambiguous browser selector; missing-expiry JWT acceptance; pre-lock impression
expiry check. A signature test's conflicting RSAKey import was corrected before final verification. Temporary
backend-unavailable browser failures during restarts were rerun after readiness.

No performance target was rebenchmarked. No real external publisher domain was claimed/verified. Positive DNS tests
exercise the verification service with a controlled resolver response; no DNS bypass was added to the deployed API. Full
production security and enterprise acceptance remain open as listed in requirements.md.

Final container verification: both `docker compose build backend frontend` images built from the final application
sources. `docker compose up -d backend frontend` started them alongside this project's PostgreSQL/Keycloak, using
loopback ports 3006/8086/8186. `python3 tests/smoke.py` passed against this Dockerized backend; all three Playwright
tests passed again in 2.1 seconds against the Dockerized Next.js BFF and Keycloak internal transport.
`npm --prefix frontend run typecheck` passed. Existing unrelated services were not stopped or modified. No CI-hosted run
or Kubernetes deployment is claimed.

## Fraud validation, auditable billing and Kafka continuation

Final executed backend command:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DOCKER_HOST=unix:///Users/MWGBH/.orbstack/run/docker.sock \
mvn -f backend/pom.xml verify
```

**34 tests passed**, zero failures/errors/skips: DomainTest 5, PacingAndIdentityTest 6, JwtSignatureTest 1,
PlatformIntegrationTest 21 and KafkaRecoveryTest 1. The Kafka test contains a multi-step failure/recovery sequence
rather than being counted as many independent tests. Its PostgreSQL and Kafka containers are real, disposable
infrastructure; the producer is not mocked.

Executed coverage includes:

* Concurrent daily/lifetime reservations and duplicate impression commits remain correct.
* Sixteen concurrent validation requests create one CHARGE and one VALIDATE audit. Invalid/repeated synthetic traffic is
  never automatically charged.
* Repeated clicks are held for review and remain one counted click. New rules move an existing validated charge to
  DISPUTED without an automatic financial penalty.
* Append-only charge and audit triggers reject SQL UPDATE/DELETE; reversal retries append one compensating entry;
  reversed traffic cannot be recharged. Invalid review operations roll back their case/audit/ledger/outbox changes.
* Role and tenant isolation cover billing reads and administrative review. Forged Kafka envelopes cannot create inbox
  entries or charges. Conversion IDs now reject conflicting attribution references, and accepted conversions enter the
  outbox transactionally.
* A real Kafka broker is paused and resumed: publisher failure retains a READY SQL record and no billing entry. Expired
  lease state simulates broker-ACK/process-death ambiguity; stable-ID publication and consumer inbox prevent duplicate
  business effects.
* The consumer is stopped and restarted, duplicates replay, unsupported schemas and malformed messages enter the durable
  SQL dead-letter queue, and subsequent good records still process.
* A PostgreSQL trigger deliberately rejects the fraud-case write after inbox insertion. Both inbox and business
  mutations roll back. After dropping the injected fault, canonical replay creates one durable inbox effect and no
  charge.
* Missing-inbox reconciliation requeues an old SENT event without inventing a new event identity. Malformed records
  without a source cannot be fabricated into replayable events; evidence-based discard retains audit history.

Frontend verification: `npm --prefix frontend run build` and `npm --prefix frontend run typecheck` passed. Five actual
OIDC/Playwright interaction tests passed in **3.4 seconds** against the full Compose stack, including advertiser billing
data and administrator fraud/delivery views. No screenshot-only assertions were substituted for interactions.

Deployment verification: both application Docker images built successfully. Compose runs PostgreSQL, Keycloak, a
persistent single-node Kafka broker, backend and frontend on this project's loopback ports. V3 was applied to the
existing V2 database, retaining prior decisions and accounting. Prior measurements were marked synthetic and cannot be
charged. `python3 tests/smoke.py` passed against the updated stack. A secured operational query observed ten SENT outbox
records, ten durable inbox effects, zero outstanding SENT effects and zero dead letters at that check; these counts are
a local delivery check, not a throughput benchmark.

The final backend verification/image build also includes system ASSESS audit entries for automatic fraud state
transitions, not just manual review history. No production availability, classifier quality, external fraud-detection
coverage, invoice/tax/payment processing, multi-AZ Kafka durability or high-load throughput claim is made. Single-node
Compose and scripted lease-expiry simulation are not evidence of complete infrastructure failover.

The final deployed backend and the five browser tests were checked again after adding automatic ASSESS audit history.
The last backend run also explicitly verifies conversion replay and conflicting-attribution rejection in
MockMvc/PostgreSQL. The test-only addition does not change the deployed application binary.
