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

## Invoicing, payment reconciliation and publisher settlement continuation

Executed final financial verification:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DOCKER_HOST=unix:///Users/MWGBH/.orbstack/run/docker.sock \
mvn -f backend/pom.xml verify
```

**51 backend tests passed**, zero failures/errors/skips: DomainTest 5, JwtSignatureTest 1, MigrationHistoryTest 1, PacingAndIdentityTest 6, PlatformIntegrationTest 21, TreasuryIntegrationTest 16, KafkaRecoveryTest 1. The Kafka test is a multi-step real-broker recovery sequence, not counted as numerous independent tests. PostgreSQL locks, unique constraints, migrations, immutability triggers and rollback assertions use actual disposable databases.

New exercised cases:

* Sixteen concurrent invoice-close retries create one batch/document/line; later closes do not reassign existing ledger entries.
* Injected invoice-line failure rolls back batch/header/audit/outbox; retrying the same request after repair succeeds.
* Credit notes preserve the original invoice and line references; reversals before issuance produce linked gross/credit documents with zero net obligation. Publisher share rounding at 101 minor units is exactly 70, and reversal is exactly -70.
* Partial receipt matching, identical/conflicting source references, microsecond timestamp normalization, retained overpayments, source returns and exact allocation idempotency are checked.
* Full external returns of unmatched overpayments reconcile as a zero-net non-invoice pair.
* Sixteen concurrent settlement attempts cannot reserve the same collected balance twice. Submitted unknown outcomes cannot be cancelled or expire into new spending rights. Confirmation retries use one instruction and one bank-evidence row.
* Injected payout-state failure rolls back evidence, state/audit and event writes; the original bank reference succeeds once after repair. Explicit FAILED evidence releases the reservation, while cancelled keys never generate new instructions.
* Funding returns before submission block the instruction without deleting the reservation. Reversals after payout retain history, expose recoupment/funding risk and offset future eligible earnings.
* Cross-tenant invoice access and role-based mutation restrictions are tested. Receivables subtract reconciled cash, expose post-payment credits as negative receivables and return to zero after an observed refund is reconciled.
* A separate schema is migrated to V3, seeded with a historical 101-unit charge and its reversal, then upgraded to V4. Both original billing entries survive, publisher revenue backfills to +70/-70, subjects are preserved and no cash is invented.
* The real Kafka broker is paused for finance publication, then resumed. A trigger rejects the treasury-notification write after inbox insertion: both effects roll back, the failed record enters the durable SQL queue, and canonical replay succeeds after repair. Duplicate notifications create one inbox/projection; invoice/payment/settlement effects remain synchronous and are not repeated by consumers.

Frontend/build verification: both Docker images built. Next.js production build and `npm --prefix frontend run typecheck` passed after adding DOM iterable types. `PLAYWRIGHT_BROWSERS_PATH=/tmp/contextads-browsers npx playwright test --workers=1 --reporter=line` passed **8 tests in 6.2 seconds** against the final Compose stack: previous OIDC/security workflows plus owned invoice/payment reads, publisher revenue/settlements and an idempotent administrative close through the UI. `python3 tests/smoke.py` passed against the upgraded backend. Accounting document printing is provided through browser print CSS; no PDF render verification is claimed.

### Upgrade/recovery defects found and fixed

The first live upgrade failed because historical V1–V3 SQL had been reformatted after deployment. Flyway correctly rejected their changed checksums; browser tests then reported backend-unavailable errors. The exact original files were recovered and CRC-checked against existing history. No schema-history repair, checksum rewrite, financial-row deletion or volume reset was performed. The successful live history is V1=160300381, V2=-1690407760, V3=370452549, V4=-692542901. MigrationHistoryTest now pins all four, and its final focused rerun passed. The database reports all four migrations successful.

Other fixes: the frontend lacked `dom.iterable` types; imported bank times needed normalization to database precision for replay; accrued net charges had to be separated from outstanding receivables after collection; reserved funding shortfalls had to be distinguished from already-paid exposure. Backend HTTP readiness now gates new frontend startup.

Local identity recovery was also verified. Keycloak's existing H2 store was backed up while stopped, copied into the new keycloak-data named volume and its container recreated. All five existing account subjects were compared before/after and remained unchanged. The stopped-store backup remains in an OS temporary directory; no credentials or tokens were printed or committed. This fixes local container-recreation ownership loss; it is not a production IdP backup/HA design.

No real external money was collected, refunded or transferred. Bank facts and confirmations in integration tests are synthetic isolated fixtures. The running console uses explicit manual-bank evidence, not an automated provider. No tax/legal invoice compliance, cash liquidity forecast, production performance or enterprise readiness claim is made.

## Versioned creative uploads and isolated publisher SDK continuation

Final backend command (after building the source-pinned asset image):

```sh
docker compose build assets
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DOCKER_HOST=unix:///Users/MWGBH/.orbstack/run/docker.sock \
mvn -f backend/pom.xml verify
```

**66 backend tests passed**, zero failures/errors/skips: CreativeIntegrationTest 10, ImageSafetyTest 5, DomainTest 5, JwtSignatureTest 1, KafkaRecoveryTest 1, MigrationHistoryTest 1, PacingAndIdentityTest 6, PlatformIntegrationTest 21 and TreasuryIntegrationTest 16. The existing financial, reconciliation, migration and Kafka failure-recovery tests remain in the executed suite.

New coverage uses real disposable PostgreSQL and source-built MinIO:

* PNG/JPEG normalization, rejection of executable/unsupported formats, truncated images, oversized input and dimension bombs; an appended script payload is absent from the normalized result. JPEG becomes a fresh decodable PNG.
* PENDING images remain private; exact-version approval enables image decisions. Asset/campaign ownership and administrator-only review are enforced. Sixteen concurrent version creations produce distinct versions without inheriting approval or reserving spend.
* Current-version rejection prevents new decisions, new frames and old impression acceptance. Snapshot destinations cannot be rewritten. Reviewing an older version cannot activate a new version; the legacy campaign alias cannot approve later versions.
* HTML escaping, strict nonce CSP, opaque sandbox policy, scoped ancestors, token-purpose separation and public-only non-credentialed measurement CORS are checked.
* A real object-store pause causes approval to fail without changing PENDING state; unpause/retry succeeds. Tampered and deleted S3 bytes fail integrity/availability checks. No object-store mock substitutes for these assertions.

Build/deployment: the pinned source asset service and both application Docker images built successfully. Next.js production build and `npm --prefix frontend run typecheck` passed. Compose runs PostgreSQL, persistent Keycloak, Kafka, private asset storage, backend and frontend; the backend/assets/database/broker health checks passed. Live migration history remains V1=160300381, V2=-1690407760, V3=370452549, V4=-692542901 and V5=-2000512581, all successful. No history repair or data reset was performed. The V5 checksum is now pinned in the regression test.

Portal browser command:

```sh
cd frontend
PLAYWRIGHT_BROWSERS_PATH=/tmp/contextads-browsers \
npx playwright test tests/workflow.spec.ts --workers=1 --reporter=line
```

**9 tests passed in 21.2 seconds** against the deployed Compose stack. The added workflow authenticates through real Keycloak, creates a campaign, verifies both the 2 MiB file and 3 MiB proxy limits, rejects disguised SVG, uploads a real PNG through the multipart proxy, creates version 2, opens the administrator's private preview, approves that exact version and verifies the advertiser sees APPROVED. Existing OIDC/tenant, domain, invoice and publisher revenue views remain covered. `python3 tests/smoke.py` also passed, including legacy initial-text approval compatibility.

Independent external integration command:

```sh
PLAYWRIGHT_BROWSERS_PATH=/tmp/contextads-browsers python3 tests/isolated_renderer.py
```

**5 tests passed in 17.4 seconds.** The runner creates disposable PostgreSQL/S3/backend containers and a separate synthetic publisher HTTP origin, uses real Keycloak-issued identities, seeds a reserved example.com ownership fixture only into that disposable DB, and exercises actual context, serving, asset, render, SDK and measurement HTTP paths. Its credentials are not placed in publisher markup; temporary token metadata is private and removed. All three fixture containers/storage are removed on completion.

Chromium verified literal creative text and real image pixels, denied parent DOM/localStorage access, and recorded one signed viewability signal through the actual serving API. Offscreen placement did not measure until scrolled into view. A real popup click used the stored approved destination with no opener; its reserved example.org destination was intercepted to avoid external advertising traffic. A manipulated redirect query did not change the destination. Rejection stopped rendering/new wins/old impressions. A wrong-purpose capability reached the eight-second unavailable fallback, removed its frame and recorded no impression. All fixture decisions were synthetic; no advertiser charges or external money transfers occurred.

Defects found and fixed: the published MinIO image was unavailable (replaced by the reproducible upstream source build); Java test imports and a React unknown-value expression initially failed compilation; the Spring CORS source was not selected until explicitly named; UTF-8 was made explicit for frame HTML; servlet `/error` authentication masked public 410 as 401 (replaced by direct Problem Details handling); initial browser fixture coupling to a restarting portal was removed with a dedicated publisher origin. The proxy now preserves binary data/multipart boundaries and bounds reads, while initial campaign approval compatibility is limited to version 1 to avoid stale approval shortcuts.

No high-load throughput/latency measurement, malware-scanner result, legal advertising-compliance certification or production-readiness claim was added. Image normalization is not semantic brand review. Quotas, orphan/retention cleanup, managed storage hardening, richer targeting/taxonomy, OIDC refresh/teams and complete deployment/observability remain outstanding as documented.
