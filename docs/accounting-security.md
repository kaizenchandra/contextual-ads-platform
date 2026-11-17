> Historical initial baseline. OIDC, inventory verification and daily pacing supersede the corresponding limitations
> below. See [current design](identity-domains-pacing.md) and [acceptance status](requirements.md).

# Accounting, measurement and security

All amounts are integer minor units in a single implicit demo currency. A bid is a fixed per-qualified-impression price,
not CPM or CPC. Lifetime budget obeys spent + reserved <= budget through a row lock, conditional update, and database
CHECK. Reservations expire after five minutes; a scheduled worker releases at most 200 per 30 seconds. Late signals are
rejected and clicks cease redirecting when expired. This is a demo policy, not a recommended production click lifetime.

Each decision has one reservation and at most one COMMIT or RELEASE. Events have a (decision,kind) primary key. The
decision lock serializes duplicate events and expiry. Movements provide an immutable accounting history. Reconciliation
compares reservations against outstanding decisions and spending against commit movements. Reporting is exact for these
transactionally recorded signals.

`spent` is **provisional synthetic spend**, never validated billable money. No billing, invoicing, payments, publisher
settlement, fraud approval or real charges exist. A client viewability signal does not verify human attention. Do not
use these totals to invoice anyone. Estimated, validated, invoiced and paid balances must become separate ledgers before
commercial use.

The renderer waits for >=50% intersection for one second with an initially visible document. It does not fully handle
visibility changes during that interval. Events require HMAC tokens bound to decision ID and event kind; campaign, page,
price and expiry are resolved from immutable decision data. No personal browser identifier is stored. Replays cannot add
a second provisional charge. Token leakage can still forge a first signal.

Conversions are authorized server-to-server, idempotent per advertiser/external ID, and require an owned decision with a
click in the last seven days. This is explicit-reference click attribution, not cross-device attribution or proof of
causality. Event-time validation and out-of-order retry workflows remain incomplete.

## Threat model and blockers

Spoofing: Basic auth and demo role selection are local-only. Next.js proxy intentionally allows selecting any demo role;
never expose it publicly. Replace with OIDC issuer/audience validation, secure server sessions and resource membership
checks. No multi-user organizations exist.
Tampering: prepared SQL, signed tokens, budget checks and unique event keys constrain changes. No cryptographic audit
chain exists.
Repudiation: financial movements/outbox exist; comprehensive administrative audit history remains missing.
Information disclosure: API exposes own advertiser data; admin review requires backend role. Public signal tokens are
bearer capabilities. Do not log them.
Denial of service: connection pool and candidates are bounded; endpoint rate limits, request-byte filters, statement
deadlines and per-tenant quotas are required.
Privilege elevation: backend enforces creative approval role. Text is escaped by React; arbitrary HTML, scripts and
uploads are unsupported. External publisher SDK and cross-origin iframe isolation are not delivered.

HTTPS landing pages reject embedded credentials, query and fragment. Destinations are admin-reviewed campaign values,
never click-request parameters. Campaign edits are unsupported, so stored destinations cannot change after approval.
Actual landing-page safety checks and approved-domain management remain manual.

No scraper exists, eliminating fetch SSRF in this slice. Publisher content itself may lie; domain verification,
authenticated placements, policy exclusions and trusted context lifecycle must precede external integrations. No
retention/deletion automation exists. Do not collect personal data in demo article text.
