# Acceptance status

The complete product specification remains the target. Current verified foundations:

| Capability                                         | Status                                                                                                                                             |
|----------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Java 21 backend, Next.js and PostgreSQL migrations | Builds/tests verified                                                                                                                              |
| OIDC                                               | Signed JWT resource server and real authorization-code browser login; role and subject ownership checks                                            |
| Publisher websites                                 | Expiring DNS TXT proof, exclusive active claim, renewal/revoke, owner-scoped placements                                                            |
| Page/placement authorization                       | Verified exact-host HTTPS page and owned active placement required                                                                                 |
| Campaigns                                          | Create/list/approve/activate/pause, schedules and daily/lifetime budgets                                                                           |
| Pacing                                             | Deterministic UTC EVEN and ACCELERATED admission                                                                                                   |
| Accounting                                         | Transactional reservations, deduplication, expiry, lifetime/day reconciliation                                                                     |
| Classification                                     | Six-topic deterministic single-label baseline; no taxonomy admin or measured quality corpus                                                        |
| Rendering                                          | Escaped Sponsored text; external iframe SDK not delivered                                                                                          |
| Measurement/attribution                            | Signed, deduplicated provisional signals and explicit owned click references                                                                       |
| Reporting                                          | Advertiser provisional counters and daily ledgers; publisher settlement absent                                                                     |
| Docker                                             | Local Compose with dedicated Keycloak and PostgreSQL; both application images built, complete Compose stack exercised with real OIDC browser tests |
| Kubernetes                                         | Backend reference only; hardened full deployment remains outstanding                                                                               |
| Full security/privacy operations                   | Teams, OIDC refresh rotation, rate limits, auditing, retention and comprehensive policies remain incomplete                                        |
| Fraud validation/billing                           | Deterministic holds, explicit evidence review, immutable charges/reversals, audit and reconciliation; invoice/payment/settlement missing           |
| Kafka                                              | Stable-ID outbox relay, durable inbox consumer, retries, SQL dead letters, replay and recovery tests; single-node local broker                     |
| Redis/OLAP                                         | Deferred; PostgreSQL remains authoritative                                                                                                         |
| Scale                                              | No 100k RPS / 50ms p99 / 99.99% claim verified                                                                                                     |

See identity-domains-pacing.md for precise consistency and propagation guarantees. Automated Testcontainers tests
exercise actual PostgreSQL locks and accounting rather than mocked financial storage.

The latest fraud/billing and event consistency model is in [fraud-billing-events.md](fraud-billing-events.md); it
supersedes the earlier provisional-only billing and undelivered-outbox limitations.
