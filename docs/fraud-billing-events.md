# Fraud validation, billing and durable event delivery

## Measurement is not a charge

A signed impression moves reserved liability into the existing provisional `campaigns.spent` balance. That balance is
**not** a validated advertiser charge. Kafka processing opens a review case and never inserts a charge. Missing Kafka,
an undecided case or replay cannot silently invoice a client.

The implemented rule set v1 detects known synthetic traffic, repeated clicks on the same decision (five attempts),
repeated impressions (ten attempts), and publisher self-traffic where the campaign and page share the same authenticated
subject. No IP address, browser fingerprint or cross-site user identity is collected. Duplicate counters count attempts
while the unique event table counts each event type once. Rules are evidence for review, not proof that a human did or
did not attend. Unknown bots, publisher-wide velocity, click farms and independent measurement verification remain
limitations. Positive rules are conservative holds; there is no automatic punitive debit or hidden anomaly-score
billing.

The publisher demonstration marks decisions synthetic. V3 marks all pre-V3 decisions synthetic because the earlier
platform produced test measurements without fraud review. They cannot be validated into charges. The server trusts a
positive synthetic flag as a reason to hold traffic; absence of the flag is never taken as proof of a real human.

## Explicit review and append-only ledger

`POST /api/v1/admin/fraud/{decisionId}/review` accepts `{action,reason}` where action is VALIDATE, INVALIDATE or
REVERSE, and reason contains 12–2000 characters of review evidence. Backend ADMIN authorization and stable OIDC subject
identify the actor. A decision must contain a committed impression. Validation checks current rules under the decision
lock, even if Kafka is behind. Any positive rule blocks charging; a narrative cannot bypass that hold. There is no
rule-override appeal workflow yet; false positives require further investigation and may remain unbillable.

State transitions:

* PENDING/REVIEW -> VALID on evidence-based VALIDATE when no positive rules remain. One positive CHARGE entry is
  inserted.
* PENDING/REVIEW -> INVALID on INVALIDATE. No financial entry is inserted.
* VALID -> DISPUTED if later signals trigger rules. No automatic reversal occurs.
* VALID/DISPUTED -> REVERSED on confirmed REVERSE. A negative entry references the same
  decision/campaign/advertiser/currency and exactly negates its charge.
* Repeated requests for the same terminal result are idempotent. INVALID and REVERSED cannot be charged again. Charged
  events must use REVERSE rather than INVALIDATE.

Charge/reversal, review state, financial audit and a follow-up outbox event commit in one PostgreSQL transaction. A
unique (decision,kind) key and the decision row lock prevent concurrent double charging/reversing. A database trigger
rejects UPDATE or DELETE on `billing_entries` and `financial_audit`. Review history includes actor, evidence,
previous/new state and timestamp. Automatic case creation/dispute transitions append ASSESS entries under system:
fraud-v1 as well. Operators must protect database-owner/DDL privileges; append-only triggers do not withstand a
malicious database superuser or provide a cryptographic audit chain.

All ledger amounts are integer USD minor units; USD is enforced explicitly. Reporting distinguishes provisional
exposure, validated charges, reversed charges and net receivables. Invoiced and paid balances are zero because invoice
issuance, tax handling, collection and payments are **not implemented**. Administrative review is an accountable
assertion of independent evidence, not an automated certification of human attention.

A reversal lowers receivables but does not replenish campaign budget rights. The conservative admission invariant
remains `provisional spent + reserved <= budget` for lifetime and daily buckets. Consequently
`net validated charges + reserved <= budget` also holds. This avoids spending refunded rights twice under delayed
dispute/replay. Revenue sharing/publisher settlement remains outstanding.

## Kafka architecture

```mermaid
sequenceDiagram
    participant Browser
    participant API
    participant PG as PostgreSQL
    participant Relay as Outbox relay
    participant Kafka
    participant Consumer
    participant Admin
    Browser->>API: Signed measurement
    API->>PG: Decision lock, dedup, provisional accounting, outbox
    PG-->>API: Atomic commit
    Relay->>PG: Claim one event with 15s lease
    Relay->>Kafka: Publish stable event ID; key=decision ID
    Kafka-->>Relay: Broker acknowledgment
    Relay->>PG: Mark SENT only if lease still owned
    Kafka->>Consumer: At-least-once delivery
    Consumer->>PG: Canonical contract check, inbox, fraud case in one transaction
    Consumer-->>Kafka: Commit offset after SQL commit
    Admin->>API: Evidence-based validation/reversal
    API->>PG: Append ledger + review audit + outbox atomically
```

`ads.events.v1` uses three partitions and decision ID partition keys. Schema 1 carries eventId, sequence (global outbox
ID, not an aggregate business version), schemaVersion, eventType, decisionId and occurredAt. No token or creative
content is published. Consumers compare every envelope field against its immutable durable source metadata; forged or
incompatible events cannot create inbox or financial effects. Implemented types: AdDecisionMade, SpendCommitted,
ClickRecorded, ConversionRecorded, FraudSignalTriggered, ChargeValidated, ChargeReversed and TrafficInvalidated.
Control-plane campaign activation/pause events are not yet dispatched.

The relay claims using short transactions and SKIP LOCKED. Network work takes place outside SQL locks. Producer
idempotence plus `acks=all` reduces transport duplicates but does **not** establish exactly-once PostgreSQL/Kafka
effects. If the broker ACK is lost or the process dies before marking SENT, the stable event ID is published again.
Updates require the original lease token. A bounded poll processes at most ten events. Publish failures return to READY
after five seconds; after ten failed attempts the event is DEAD for operator replay. It remains durable in SQL, never
silently deleted. Consumer inbox `(consumer_name,event_id)` and the business mutation share a transaction. Reordering
uses current authoritative decision/rule state and cannot undo a later review.

Every minute, bounded reconciliation requeues up to twenty SENT events older than five minutes that lack the durable
inbox effect. This covers broker/offset loss or prolonged consumer absence. Replays keep the same ID and never clear
inbox records. This can deliberately redeliver messages while a consumer is unavailable. There are no cross-store
exactly-once claims.

## Durable SQL dead-letter queue

After the initial consumer attempt and two short retries, unrecoverable messages are stored in `consumer_failures`,
unique by topic/partition/offset. Only then can the Kafka offset be committed. A database failure during quarantine
causes recovery to fail and leaves the offset uncommitted. Payloads are bounded to 8192 characters. The DLQ is
PostgreSQL-backed, **not a Kafka DLT**. This is intentional so replay evidence and operational actions remain durable
with the business database.

Admin endpoints:

* GET `/api/v1/admin/events/status`: bounded event records, aggregate backlog, inbox count, missing-inbox count and dead
  letters.
* POST `/api/v1/admin/events/{eventId}/replay` with `{reason}`: retry READY/DEAD/SENT events using their canonical
  metadata. Active publisher leases cannot be overwritten.
* POST `/api/v1/admin/dead-letters/{id}/replay`: enqueue canonical source metadata after a schema/business defect is
  fixed. Unknown/malformed events without a durable source cannot be fabricated into valid events.
* POST `/api/v1/admin/dead-letters/{id}/discard`: retain the failure record, mark it discarded, and append actor/reason
  history.

The UI exposes review evidence/actions, advertiser ledger/balances, event status and replay/discard forms. Financial
reads and operations are backend-authorized; hidden frontend controls do not grant access.

## Operations and limitations

The local single-node KRaft Kafka broker uses a named volume and loopback external port 9096. It is for reproducibility,
not high availability. Production requires replicated topics, min-ISR policy, TLS/SASL, producer/consumer ACLs, separate
database roles, retention/backup policy and alerts. Kafka never owns campaign budget rights.

Watch `ads.outbox.published`, `ads.outbox.failures`, Kafka client metrics and the secured event-status API. Unprocessed
SENT events and DEAD records require investigation. Reservation expiry and relay polling run on a bounded three-thread
scheduler pool. No new high-cardinality metric labels are introduced. Queue/event/audit retention, full traces,
financial invoice exports and production alert dashboards remain incomplete.

For a broker outage, leave API/database running; reservations and provisional accounting remain authoritative. Restore
Kafka, check pending delivery, replay DEAD rows with evidence, then verify inbox and review counts. For a business-write
defect, repair the consumer, replay the canonical event from the SQL dead-letter row, and reconcile the ledger. Never
edit financial entries or delete inbox keys to force replay. `GET /admin/billing-reconciliation` compares each committed
decision's expected net amount (VALID/DISPUTED=price, otherwise zero) with immutable entries. A disputed charge remains
visible until explicitly reversed.

Reference: retry/offset recovery
follows [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html);
real broker tests use the [Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/).
