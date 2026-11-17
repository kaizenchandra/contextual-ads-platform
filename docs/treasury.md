# Invoices, payment reconciliation and publisher settlement

## Implementation boundary

The platform now issues immutable accounting invoices and linked credit notes, reconciles administrator-imported bank receipts/returns, accrues publisher revenue and manages reserved/manual/confirmed settlements. All examples and automated tests use synthetic local data. **No real bank transfer, card charge, automated refund, tax calculation, regulatory invoice certification or provider API connection is implemented.** An administrator records observed external bank facts with source references and evidence; this is a manual-bank integration, not automatic bank verification.

The Next.js treasury section provides invoice/payment views for advertisers, owned revenue/settlement views for publishers, and issue/import/match/reserve/submit/confirm controls for administrators. Printable accounting documents show immutable descriptions, totals, dates and original credit references. Tax/billing-address/company-registration requirements remain a production blocker for legal tax invoices. No email is sent automatically.

## Accounts and lock order

Stable OIDC subjects identify advertiser/publisher financial accounts. No financial identifier grants authorization by itself. Backend roles protect all mutations; advertisers can read only their documents/cash and publishers only their own revenue/settlements. Unknown/foreign invoice IDs return 404. Monetary values are integer USD minor units.

Financial transactions use database account locks. Billing validation/reversal obtains decision -> advertiser -> publisher. Invoice issuance locks the advertiser. Receipt allocation/return locks the advertiser then impacted publishers in sorted order. Settlement commands lock one publisher. Fraud holds serialize on that publisher as well. Settlement code never waits for an advertiser lock, avoiding a publisher/advertiser lock inversion. Accounting writes, immutable audit entries and financial outbox events commit together. No Kafka observer grants money or settlement rights.

## Invoice issuance and credits

POST `/api/v1/admin/invoices/issue`:

```json
{"advertiser":"OIDC-subject","requestKey":"close-2026-11-001","reason":"Validated ledger reviewed for this accounting close"}
```

The command reserves a durable `(advertiser,requestKey)` batch and includes up to 500 previously unassigned CHARGE entries in an INVOICE. It then includes up to 500 eligible REVERSAL entries in a separate CREDIT_NOTE linked to their original invoice lines. A ledger entry has at most one invoice line. Totals are exact sums, descriptions are snapshots, and issuance is atomic. Concurrent retries of a request return the same batch; a new key closes new entries only. An empty batch is still an idempotent completed close, not permission to reuse its key for later charges. Larger closes require additional keys/batches.

Reversals never edit an issued invoice. Credit-note lines preserve original-line references, so the original invoice balance is `original total + linked credits`. A reversal before issuance produces both the gross invoice and its linked credit note, with zero net due for that reversed charge. Document numbers are unique sequence values; they can have gaps and do not establish jurisdiction-specific legal invoice compliance. Due dates default to 30 days. Invoice headers, lines and batches reject UPDATE/DELETE in the database.

GET `/api/v1/billing/invoices` returns up to 100 owned documents with net_due, paid, outstanding and overpaid balances. GET `/api/v1/billing/invoices/{id}` includes immutable lines. Credit-note totals are negative; receipt matching targets an original INVOICE, never the credit-note document. Admins list invoices through `/api/v1/admin/invoices`.

## Observed cash facts and reconciliation

POST `/api/v1/admin/cash/import`:

```json
{"advertiser":"OIDC-subject","source":"bank:operating-account-alias","reference":"statement-row-123","direction":"RECEIPT","amount":101,"currency":"USD","originalReceiptId":null,"occurredAt":"2026-11-17T10:00:00Z","reason":"Bank statement row independently reconciled"}
```

Imported records are immutable. `(source,reference)` is globally unique; exact retries return the same record and conflicting facts fail with 409. Amounts must be positive, currency USD, and event time cannot be materially in the future. Timestamps normalize to microseconds to match PostgreSQL precision; identical nanosecond-resolution requests remain idempotent after storage.

A receipt is not recognized as invoice payment until POST `/api/v1/admin/cash/{id}/match` with `{invoiceId,reason}` commits its immutable allocation. Partial payments are supported using separate bank receipt facts. A receipt cannot exceed current outstanding balance, be reassigned, cross advertisers or be allocated twice. An overpayment remains a durable unmatched exception; it is not silently dropped or treated as revenue.

A RETURN references an original receipt, uses a distinct bank reference, and represents observed returned money—not a request to execute a refund. Imported returns cannot cumulatively exceed the original receipt, even before matching. Returns against an allocated receipt hold that invoice's publisher funding immediately, then allocate a negative amount to the same invoice. Matching cannot increase or erase the original cash fact.

For a full return of an entirely unmatched receipt, match the RETURN with `invoiceId:null`. The command atomically records the original receipt and its exact compensating return as reconciled, non-invoice allocations with net zero. Partial returns of unmatched receipts remain exceptions until an operational resolution workflow is added. No on-account credit spending or automatic refund execution is implied.

GET `/api/v1/admin/cash` shows matched/unmatched facts. Advertisers read their own records at `/api/v1/billing/payments`. `/api/v1/billing/summary` now calculates invoiced and paid amounts from immutable documents and allocations rather than hardcoded zeros. `net_charges` is the signed validated billing balance; `net_receivable` subtracts reconciled cash and can be negative when a customer credit awaits an external refund. Credits after payment expose overpaid balances until an external refund/return is imported and reconciled. Bank evidence is manually attested by an authorized administrator; source/API signatures and automatic bank feeds remain outstanding.

## Publisher revenue policy

The baseline publisher share is 7000 basis points (70%). Both publisher subject and share are snapshotted on the decision. Charge validation inserts an immutable revenue entry in the same transaction: `floor(price_minor * share_bps / 10000)`. The platform retains the integer remainder. Reversal inserts the exact negative of the original share, avoiding independent rounding drift. V4 backfills historical validated/reversed ledger entries without inventing collection.

`validatedRevenue` is the signed revenue ledger balance. Settlement eligibility is deliberately more conservative: a charge must remain VALID, have no positive fraud signal, appear on an issued invoice, and that invoice must be fully funded after linked credits. Unallocated returns hold funding. Partial invoice payments release no publisher funds until the full adjusted invoice is funded; this policy avoids arbitrary per-impression payment allocation and can delay payout for otherwise valid lines.

GET `/api/v1/publishers/revenue` reports:

* `validatedRevenue`: signed accrued revenue.
* `collectedEligible`: funded, undisputed revenue currently eligible for payout.
* `reservedForSettlement`: RESERVED/SUBMITTED obligations.
* `settled`: immutable PAID bank-confirmation evidence.
* `available = collectedEligible - reservedForSettlement - settled`.
* `fundingShortfall = max(0, settled + reserved - collectedEligible)`.
* `recoveryDebt = max(0, settled - collectedEligible)`: paid exposure requiring reconciliation/recoupment under the baseline conditional-funding policy, not a newly issued legal debt invoice.

Available balance may become negative after a return, reversal or dispute. New settlements are blocked; future funded earnings offset the paid exposure. A reversed charge after payout is retained in history, never erased or automatically debited from a bank account. Publisher contracts, dispute appeals, KYC/tax onboarding and legally enforceable recoupment terms need production review. `/publishers/revenue/entries` and `/publishers/settlements` are owned, bounded reads.

## Settlement state machine and unknown outcomes

1. POST `/admin/settlements` with `{publisher,requestKey,amount,reason}` reserves only available collected funds. Concurrent commands cannot reserve the same balance twice. A repeated key returns the same settlement and conflicts if its amount differs.
2. POST `/admin/settlements/{id}/submit` creates one immutable manual payout instruction with `idempotency_key = settlement UUID`, then marks SUBMITTED. It checks funding again. This produces an instruction for an external bank workflow; it does **not** transfer money.
3. Retries return the same instruction. Unknown external outcomes remain SUBMITTED and reserved indefinitely. They never expire automatically. Cancellation is allowed only while RESERVED; a submitted instruction cannot be released because of a timeout or restart.
4. POST `/admin/settlements/{id}/confirm` accepts `{outcome:"PAID"|"FAILED",reference,amount,currency:"USD",reason}` from verified external bank evidence. The amount must exactly match the instruction; the bank-qualified reference is globally unique. PAID evidence records settlement; FAILED evidence releases the reservation. Exact repeats are idempotent, conflicting confirmations fail.
5. A database failure during confirmation rolls back the evidence, state change, audit and outbox event together. A retry with the original reference is safe. Real external transfers that complete after funding changes must still be recorded truthfully; any resulting exposure remains visible and blocks new payouts.

Actual bank/provider deduplication must honor the stable instruction key outside this application. A human or an unintegrated bank could still send money twice; the platform cannot prevent an external transfer it does not execute. Provider adapters and automated payout-status polling remain future work. Never manufacture a second instruction to resolve an unknown first outcome.

## Finance events and recovery

`ads.finance.v1` is separate from decision events. Envelopes contain stable eventId, global outbox sequence, schemaVersion=1, eventType, aggregateType (INVOICE/CASH/SETTLEMENT), aggregateId and occurredAt. The existing leased relay publishes this stream using the aggregate ID as partition key. The treasury observer checks canonical source metadata, writes its durable inbox and notification atomically, then acknowledges Kafka. It never mutates cash, invoice totals, revenue or payout state. Replay and SQL dead letters use the existing secured operational controls. Missing-inbox reconciliation selects the correct consumer for each stream.

Financial commands remain valid when Kafka is down: durable outbox records wait for recovery. Consumers can be replayed after a failed projection write without reissuing documents or repeating payments. `/admin/treasury/reconciliation` compares document totals to lines, payout states to immutable evidence, lists unmatched cash count and publisher balances. `/admin/treasury/audit/{entityId}` provides ordered actor/reason history. Financial table immutability assumes protected database/DDL privileges; it is not a cryptographic audit chain or a full general ledger.

## Operational acceptance and remaining work

The local Compose stack remains PostgreSQL + Keycloak + Kafka + backend + Next.js. All bank facts and payout confirmations in tests are synthetic and isolated in Testcontainers. There are no real-world ad clicks or financial transfers in the test suite. Production still needs provider integration/signatures, segregated finance roles and dual approval, legal/tax invoice templates, payer/payee onboarding, automated statements, FX, fees/taxes, bank cash-position reconciliation, refunds/adjustment exception workflows, retention/backup policy and multi-AZ hardening. No performance or production-readiness claim is made.

The immutable-document/credit distinction is consistent with [Stripe's invoice lifecycle documentation](https://docs.stripe.com/api/invoices/create); this platform does not use or claim integration with Stripe.

Migration V1–V4 checksums are pinned; historical formatting is part of the deployed contract. The local IdP identity volume is persistent and was migrated without changing subjects. See verification.md for exact live history and recovery evidence.
