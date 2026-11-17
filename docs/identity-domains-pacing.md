> Fraud review, validated ledger entries and Kafka delivery have since been implemented;
> see [current financial/event design](fraud-billing-events.md). Budget and OIDC semantics below remain applicable.

# OIDC, publisher verification, daily budgets and pacing

## Identity and authorization

The backend is a Spring Security OAuth2 resource server. Nimbus verifies RS256 signatures from the configured JWKS.
Validation requires the configured issuer, `contextads-api` audience, nonempty `sub`, an expiry claim, and timestamp
validity (Spring's standard 60-second skew). Only roles in `resource_access.contextads-api.roles` are mapped to
ADVERTISER/PUBLISHER/ADMIN. Unrelated realm/client roles are ignored. The fixed issuer namespaces all stored subjects.
Never switch issuers without an explicit identity/data migration.

The API is stateless and accepts no authentication cookies or Basic credentials. Resource ownership uses the stable JWT
subject, never mutable usernames or browser headers. No wildcard CORS is enabled. Browser login uses Auth.js + Keycloak
authorization code with PKCE, state and nonce. The Next.js BFF decrypts the HttpOnly session cookie, checks access-token
expiry and forwards the token server-side. Provider tokens are omitted from `/api/auth/session`. Every BFF mutation
checks exact Origin against AUTH_URL, including when a session exists. Invalid API auth returns Problem Details.
Sign-out clears the application session; it does not terminate the upstream SSO session or revoke already-issued access
tokens. Short access-token expiry bounds that window. Refresh/revocation orchestration remains future work.

Configuration: backend OIDC_ISSUER and OIDC_JWKS; frontend OIDC_ISSUER, OIDC_CLIENT_SECRET, AUTH_URL and AUTH_SECRET. In
Compose the issuer is canonical localhost:8186; backend JWKS and the frontend's explicitly configured internal IdP
transport use the Keycloak container DNS address. The issuer itself is still checked unchanged. This split supports
local Docker networking, not arbitrary runtime issuer discovery. Use HTTPS and managed secrets outside local
development. Never deploy the imported local realm/users or test password-grant client to production.

## Publisher verification

POST `/api/v1/publishers/websites` accepts `{domain}` and returns website ID, exact TXT record name/value and challenge
expiration. Domain input is IDNA-normalized with DNS label limits; IP literals, URLs, private-name suffixes,
trailing-dot aliases and custom ports are rejected. No HTTP content fetching is performed. A 256-bit SecureRandom
challenge expires in 24 hours. Re-registering your domain rotates the challenge. Existing verification survives rotation
until its verification expiry.

POST `/api/v1/publishers/websites/{id}/verify` looks up the exact TXT value through the server DNS resolver. DNS
retries/timeouts and returned record count are bounded. Network work occurs outside the database transaction. The exact
challenge and expiry are rechecked under a website lock. A domain-ownership row serializes conflicting publishers.
Merely registering a domain does not reserve it; a second publisher cannot replace a live verified claim. After 30 days
the owner must renew proof. A previous owner with expired/replaced ownership cannot use existing pages or placements.
DNS cache delays can cause retryable verification failure. DNSSEC validation depends on the configured recursive
resolver; DNS control is proof of domain control, not editorial or legal policy approval.

POST `/api/v1/publishers/placements` requires `{websiteId,name}` and active ownership verification. Page analysis now
requires `{websiteId,url,title,html}`; the URL must be HTTPS on that exact verified hostname with no credentials, query,
fragment or nonstandard port. This constrains registration but does not prove submitted article text is truthful.
Serving requires `{pageId,placementId}` and joins page, website, ownership and active placement with the authenticated
subject. Unknown/unsafe content still no-fills. Website revoke and placement pause prevent subsequent eligibility
checks; a transaction already past that check can finish. This is a short transaction propagation window, not immediate
revocation of rendered ads.

GET websites/placements lists only owned inventory. Domain verification has no public/admin bypass. Automated positive
verification tests mock only the DNS adapter, using real PostgreSQL transactions and the actual verification service.
Live smoke checks exercise real DNS absence. No claim is made that a real external publisher domain was verified during
development.

## Daily accounting

V2 adds daily_budget, pacing, starts_at/ends_at, per-campaign UTC-date budget buckets and decision budget_day. Each day
snapshots its limit on first admission. Budget edits are not exposed, preventing mid-day redefinition. Historical
existing decisions are backfilled without deleting financial history. Existing username-owned campaign data is not
automatically reassigned to OIDC subjects; migrate explicitly with audited identity mapping. Existing pages lack website
ownership and cannot pass the new serving joins.

Each admission locks its campaign, samples PostgreSQL wall time **after acquiring the lock**, checks scheduling,
creates/locks its daily bucket, checks daily allowance and lifetime availability, then reserves both balances and
inserts the decision, movement and outbox event in one transaction. Each financial day obeys
`spent + reserved <= daily_limit`; the lifetime balance independently obeys `spent + reserved <= lifetime_budget`.
Candidate selection remains indexed/bounded to 100. The database CHECK constraints are additional invariant guards.

A qualified client impression serializes on the decision, rechecks expiry after obtaining that lock, deduplicates and
moves liability from reserved to provisional spent in both ledgers atomically. Its budget_day is the admission date even
when the event arrives after UTC midnight. This assigns liability consistently; dashboards must not reinterpret it as
event-date spend. Expiry releases both ledgers once, using one transaction per expired decision. Multiple workers can
race safely through the decision lock/SKIP LOCKED and state predicate. Failed transactions roll back all effects; a
later sweep retries. Workers process at most 200 reservations per 30 seconds per pod. Delayed cleanup reduces fill,
never creates spending rights.

## Deterministic pacing

ACCELERATED exposes the daily limit immediately inside the campaign schedule. EVEN releases
`floor(daily_limit * elapsed_ms / active_day_window_ms)`, using BigInteger arithmetic and the intersection of the
scheduled window with the UTC day. Spent plus reservations must fit this allowance. Unrendered reservations therefore
consume pacing capacity until released. There are no per-pod Redis counters or independent clocks granting money. A
popular campaign serializes on a PostgreSQL row; sharded escrow leases remain necessary before claiming global-scale
budget admission.

GET `/api/v1/campaigns/{id}/daily-budgets` reports up to 90 owned UTC buckets. Admin daily reconciliation independently
sums RESERVED and COMMITTED decisions for each bucket (bounded to 1000 rows per request). Financial amounts remain
provisional synthetic spend; fraud validation, settlement and invoices are not implemented by these changes.

## Reference documentation

The issuer/audience/signature validation
follows [Spring Security's resource-server JWT documentation](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).
The browser integration follows the [Auth.js Next.js API](https://authjs.dev/reference/nextjs); provider tokens remain
excluded from the public session callback. Local versions are pinned by Maven dependency management and the frontend
package lock.
