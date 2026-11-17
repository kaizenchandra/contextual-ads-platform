# Contextual advertising platform

Java 21, Spring Boot, Next.js App Router and PostgreSQL. Working capabilities include campaign review and activation,
HTML classification, contextual candidate selection, first-price ranking, lifetime and UTC daily budget reservations,
accelerated/even pacing, signed measurement, deterministic fraud holds, administrative validation, append-only
charges/reversals, Kafka outbox/inbox processing, reporting, OIDC browser login, and DNS-verified publisher
websites/placements.

## Run locally

```sh
docker compose up --build -d
# Open http://localhost:3006 and choose Sign in
```

The local Keycloak realm is imported automatically. Use `advertiser / advertiser-dev`, `publisher / publisher-dev`, or
`admin / admin-dev` at the Keycloak login page. These accounts and Compose secrets are local fixtures. Selecting a
console view never changes identity or permissions. Use separate browser profiles/private windows to work as different
accounts; application sign-out does not terminate the Keycloak SSO session. Local ports: Next.js 3006, backend 8086,
Keycloak 8186, PostgreSQL 5432, Kafka 9096. Other projects' services on 3000/8080/8180 are left alone.

Advertisers create campaigns with lifetime/daily budget, per-impression price and pacing. Admins approve creatives;
advertisers activate campaigns. Publishers register a domain they control, publish the returned TXT record at
`_contextads-verification.<domain>`, verify it, then create a placement. In the publisher demonstration load inventory,
select that website and placement and enter an HTTPS article URL on that exact domain. Submit topic-specific content and
request a Sponsored placement. A domain without real DNS proof cannot serve ads; there is no administrative verification
bypass.

For EVEN pacing, cumulative reserved plus provisional spend cannot exceed the fraction released through the scheduled
UTC day. A campaign starting now may initially no-fill until enough budget is released for one bid. ACCELERATED admits
against the full daily limit.

## Build and verify

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; use JDK 21 elsewhere
# Docker required: Testcontainers creates an isolated disposable PostgreSQL database.
mvn -f backend/pom.xml verify
cd frontend
npm ci
npm run build
npm run typecheck
npx playwright install chromium
npx playwright test --workers=1
cd ..
python3 tests/smoke.py
```

On OrbStack set `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` if necessary. The suite configures Docker API 1.44
for compatibility with Docker 29. Playwright and the OIDC smoke test require the running local platform. Browser tests
use real authorization-code login; the CLI smoke test uses a separate **local test-only** direct-grant Keycloak client.
Disable that client outside local testing.

To run applications outside Docker, start `docker compose up -d db keycloak kafka`, then:

```sh
SERVER_PORT=8086 mvn -f backend/pom.xml spring-boot:run
# separate shell:
cd frontend
export AUTH_URL=http://localhost:3006
export AUTH_SECRET='<random secret of at least 32 bytes>'
export OIDC_CLIENT_SECRET=local-web-secret-change-me
export OIDC_ISSUER=http://localhost:8186/realms/contextads
export BACKEND_URL=http://localhost:8086
npm run build
npm start -- --port 3006
```

The backend accepts Bearer JWTs only; Basic authentication and `x-demo-user` impersonation are removed. Access tokens
remain in encrypted HttpOnly Auth.js cookies and are forwarded server-side. The public session endpoint excludes
provider tokens. Access-token expiry requires signing in again; refresh rotation is not yet implemented.

Admin traffic/billing review and event-delivery controls are available in the console. Client signals never
automatically become charges; review independent evidence before validating a decision. The publisher demo and all
pre-V3 decisions are marked synthetic and cannot be billed.
See [fraud, billing and Kafka runbook](docs/fraud-billing-events.md).

See [OIDC, verification and daily accounting](docs/identity-domains-pacing.md), [acceptance status](docs/requirements.md)
and [verification evidence](docs/verification.md).

## Remaining platform work

This does not meet the full enterprise definition of done. Teams/onboarding, richer policy targeting and taxonomy,
uploaded creative processing, cross-origin publisher SDK, fraud rule appeals and broader detection, invoicing/payment
processing and publisher settlement, publisher revenue, retention/deletion, comprehensive OpenAPI, tracing/dashboards
and hardened Kubernetes deployment remain incomplete. `spent` remains provisional admission accounting; validated
charges and reversals live in a separate immutable ledger. No invoices or actual payments are issued. No 100k RPS, 50ms
p99, classification-quality or production-readiness claim is made.
