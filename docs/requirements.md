# Acceptance status

The full supplied product specification remains the target; this table distinguishes implemented behavior from outstanding work.

| Capability | Current status |
|---|---|
| Java 21 / Spring Boot, Next.js App Router, Flyway | Builds and integration tests verified; historical migration checksums pinned |
| OIDC | Real authorization-code login and signed JWT validation, roles and resource ownership; refresh rotation/teams missing |
| Publisher verification | Expiring DNS TXT ownership, exclusive claims, renewal/revoke and owned placements |
| Context analysis | Jsoup plus interpretable six-topic single-label baseline; richer taxonomy, multi-label quality corpus and policy management missing |
| Campaigns | Creation/review/activation/pause, scheduling, daily/lifetime budgets and deterministic pacing |
| Creatives | Immutable text/image versions, private bounded PNG/JPEG uploads, review previews, approval/rejection history and current-version checks |
| Asset storage | Private source-built S3-compatible service, normalized pixels, exact integrity verification; malware scanner/quota/GC/CDN missing |
| Rendering | Sandboxed opaque iframe, strict nonce CSP, scoped ancestors, Sponsored labels, bounded SDK loading/fallback and server-to-server publisher integration |
| Measurement | Purpose-bound tokens, viewability signals, click redirects, idempotency and explicit click-reference conversions; no proof of human attention |
| Fraud/billing | Deterministic holds, evidence-based manual validation, immutable charges/reversals, audit and reconciliation |
| Invoices/payments | Immutable accounting invoices/credit notes and manual-bank receipt/return matching; provider automation and legal/tax templates missing |
| Publisher settlement | Accrued/eligible/reserved/confirmed balances and durable manual instructions; no automatic bank execution |
| Kafka | Versioned decision/finance topics, leased outbox, durable inbox, retry/DLQ/replay and recovery tests |
| Reporting | Authoritative advertiser/publisher financial reads; richer time-series/product dashboards remain incomplete |
| Docker | PostgreSQL, Keycloak, Kafka, private assets, backend and frontend; persistent data/identity volumes |
| Kubernetes / observability | Reference deployment and basic metrics; complete hardened release, traces, dashboards/alerts remain incomplete |
| Privacy/security operations | Context-only serving and isolated creatives; membership, retention/deletion, rate-limit policy and comprehensive compliance operations remain incomplete |
| Scale | High-load/availability/relevance targets remain unverified; no 100k RPS, sub-50ms p99 or production-readiness claim |

Current designs: [identity/domains/pacing](identity-domains-pacing.md), [fraud/billing/events](fraud-billing-events.md), [treasury](treasury.md), [creative security/SDK](creative-security-sdk.md). See [verification.md](verification.md) for exact executed checks and remaining limits.
