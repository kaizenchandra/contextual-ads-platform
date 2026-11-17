````
# MASTER PROMPT: Design and Develop an Enterprise-Grade Contextual Advertising Platform Using Java 21, Spring Boot, and Next.js

## 1. ROLE AND MISSION

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, AdTech Platform Architect, Java 21 and Spring Boot Expert, Next.js Architect, Data Engineer, Search and Recommendation Systems Engineer, Database Architect, Security Engineer, Cloud Architect, and Site Reliability Engineer** with 15+ years of experience designing high-scale enterprise advertising and content-matching systems.

Your task is to **design, implement, test, benchmark, secure, document, and deploy a complete Contextual Advertising Platform that places relevant advertisements based on the content of a webpage**, using:

- Java 21
- Spring Boot
- Next.js with TypeScript
- PostgreSQL
- Redis
- Apache Kafka
- OpenSearch where justified
- Docker
- Kubernetes
- OpenTelemetry
- Prometheus and Grafana

Treat this as a real enterprise engineering project, not merely a system design exercise or a collection of mock frontend screens.

Deliver:

1. Functional and non-functional requirements
2. High-Level Design
3. Low-Level Design
4. Contextual targeting architecture
5. Webpage content ingestion
6. Content extraction and normalization
7. Page topic classification
8. Keyword extraction
9. Taxonomy management
10. Semantic similarity and embeddings where justified
11. Advertiser management
12. Campaign management
13. Creative management
14. Publisher management
15. Ad placement and inventory
16. Campaign eligibility matching
17. Real-time ad selection
18. Auction and ranking algorithms
19. Budget management
20. Budget pacing
21. Frequency caps where justified and privacy-permitted
22. Impression measurement
23. Click measurement
24. Conversion attribution
25. Fraud and invalid-traffic detection
26. Brand-safety controls
27. Privacy and consent management
28. Advertiser and publisher reporting
29. Administrative management
30. Next.js dashboards
31. Next.js publisher demonstration website
32. Secure embeddable ad-rendering integration
33. Observability and auditability
34. Automated testing
35. Performance benchmarks
36. Docker/Kubernetes infrastructure
37. Complete working source code and documentation

**Do not stop at HLD, LLD, architecture diagrams, pseudocode, backend API definitions, or static UI mockups. Deliver a runnable end-to-end advertising platform with real campaign configuration, contextual content analysis, ad selection, budget accounting, rendering, event tracking, dashboards, automated tests, and deployment infrastructure.**

### Mandatory engineering principles

- Use Java 21 and verified compatible versions of Spring Boot, Spring Security, Spring Data, and supporting libraries.
- Use a verified compatible Next.js release with TypeScript and the App Router.
- Follow repository instructions, existing architecture, dependencies, and coding conventions.
- Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
- Prefer clearly owned business capabilities over unnecessary microservices.
- Separate the low-latency ad-serving data plane from the administrative control plane.
- Separate contextual analysis, candidate retrieval, campaign eligibility, ranking, and delivery.
- Use deterministic baseline algorithms before introducing machine learning.
- Avoid relying on personal browsing history or sensitive user attributes for contextual targeting.
- Treat webpage content, ad creatives, tracking events, and publisher-provided metadata as untrusted input.
- Never allow arbitrary creative JavaScript to execute with publisher-page privileges.
- Enforce campaign budget and accounting invariants through durable transaction boundaries.
- Use bounded concurrency, queues, retries, deadlines, memory, and connection pools.
- Address idempotency, atomicity, consistency, distributed ownership, duplicate events, and failure recovery.
- Distinguish estimated ad spend, reserved spend, billable spend, invoiced spend, and actual payments.
- Never silently bill advertisers for unverified or invalid traffic.
- Clearly label sponsored placements and preserve an honest distinction between advertising and editorial content.
- Apply applicable privacy, consumer-protection, and advertising-transparency requirements.
- Never fabricate tests, benchmarks, revenue results, or production readiness.

---

# PART A — PRODUCT OBJECTIVE

## 2. BUILD A CONTEXTUAL ADVERTISING PLATFORM

Build a platform that analyzes webpage content and serves relevant ads without relying primarily on cross-site tracking or detailed individual user profiles.

### Example

A user visits an article:

**"Top 10 Electric Cars in 2026"**

The platform detects relevant contextual categories:

- Automotive
- Electric Vehicles
- EV Charging
- Batteries
- Sustainable Mobility

Eligible advertisements might include:

- EV charging equipment
- Electric-car accessories
- Car insurance
- Automotive services

A different article:

**"Best Java 21 and Spring Boot Development Practices"**

might match categories such as:

- Software Development
- Java
- Cloud Computing
- Developer Tools
- Technical Education

The system should match appropriate campaigns based on actual content relevance and campaign eligibility.

Do not select ads merely because an advertiser has the highest bid when the campaign is unrelated to the webpage or disallowed by publisher policy.

---

## 3. CORE USER ROLES

Support:

### Advertiser

- Create campaigns
- Upload creatives
- Define targeting
- Set budgets
- Configure bids
- View campaign analytics
- Pause campaigns

### Publisher

- Register websites
- Define ad placements
- Set allowed categories
- Block competitors or sensitive categories
- Integrate ad rendering
- View ad revenue and performance

### Platform Administrator

- Approve advertisers
- Review creatives
- Manage taxonomy
- Enforce brand safety
- Investigate suspicious activity
- Manage tenant permissions
- Review financial reconciliation

### End User

- View clearly labeled ads
- Open advertiser landing pages
- Exercise supported privacy and consent controls
- Avoid being shown prohibited or unsafe creatives

Implement appropriate authorization boundaries for each role.

---

# PART B — END-TO-END AD-SERVING SCENARIO

## 4. CONTEXTUAL AD REQUEST

Consider an article about electric vehicles.

### End-to-end flow

1. Publisher creates a webpage.
2. Publisher registers the page or integrates the ad SDK.
3. Content analysis retrieves authorized page content or uses authenticated publisher-provided content.
4. HTML is normalized.
5. Main article text is extracted.
6. Keywords and topics are identified.
7. Brand-safety classification is performed.
8. Contextual categories and confidence scores are generated.
9. The page context is cached and versioned.
10. A visitor loads the publisher webpage.
11. The browser requests an advertisement for an eligible placement.
12. The Ad Serving API verifies the publisher, placement, and supplied context.
13. Candidate campaigns are retrieved.
14. Ineligible campaigns are removed.
15. Publisher restrictions are applied.
16. Advertiser targeting requirements are evaluated.
17. Budget and pacing availability are checked.
18. Eligible campaigns are ranked or auctioned.
19. A winning creative is selected.
20. Budget exposure is reserved or accounted for according to the pricing model.
21. A signed, short-lived ad decision is returned.
22. The Next.js publisher website displays the ad with a visible sponsored label.
23. A validated impression event is recorded when the defined viewability criteria are met.
24. Clicks are tracked through a secured redirect or equivalent controlled measurement mechanism.
25. Authorized conversion events are processed.
26. Billing and reporting pipelines update asynchronously.
27. Fraud filtering and reconciliation correct invalid or ambiguous measurements.

### Mandatory invariants

- No campaign exceeds its enforced hard budget under the documented consistency model.
- Paused, expired, rejected, or exhausted campaigns cannot win new eligible decisions beyond an explicitly documented propagation window.
- Unauthorized publishers cannot request or impersonate another publisher's placements.
- A campaign is never shown on pages excluded by its targeting or brand-safety rules.
- Advertiser charges follow explicitly defined billable-event policies.
- Duplicate events cannot produce duplicate financial charges.
- Serving decisions can be audited.
- Invalid traffic is distinguished from valid billable interactions.
- User privacy is not bypassed through hidden identifiers or fingerprinting.

---

# PART C — NON-FUNCTIONAL REQUIREMENTS

## 5. SCALE AND PERFORMANCE TARGETS

Treat the following as architectural targets requiring benchmark evidence.

| Dimension | Target |
|---|---|
| Registered publishers | 100,000+ |
| Advertisers | 50,000+ |
| Campaigns | 1 million+ |
| Indexed webpages | 100 million+ |
| Daily ad decisions | 1 billion+ |
| Peak ad-serving load | 100,000 RPS |
| Event ingestion | Millions of events/minute |
| Ad decision latency | p99 below 50 ms for a defined warm-path workload |
| Availability | 99.99% design target |
| Budget correctness | Enforced according to documented reservation/accounting guarantees |
| Contextual relevance | Measurably evaluated |
| Deployment | Multi-AZ Kubernetes |
| Multi-region | Optional scale-out design |

Distinguish:

- Incoming ad requests
- Eligible ad decisions
- Filled ad requests
- Rendered creatives
- Viewable impressions
- Clicks
- Conversions
- Billable events
- Settled advertiser charges

Do not confuse decision throughput with billable impression throughput.

---

# PART D — HIGH-LEVEL ARCHITECTURE

## 6. ARCHITECTURE OPTIONS

Compare:

### Option A — Modular Monolith

Spring Boot implements campaign management, matching, and event ingestion.

### Option B — Event-Driven Advertising Platform

Separate high-volume event processing from API operations.

### Option C — Control Plane + Data Plane

Control Plane:

- Advertisers
- Publishers
- Campaigns
- Creatives
- Targeting
- Taxonomy
- Policy management
- Reporting

Data Plane:

- Context lookup
- Campaign candidate retrieval
- Eligibility
- Auction/ranking
- Budget admission
- Ad decision
- Serving response

### Option D — Global Distributed Ad Platform

Regional ad-serving clusters with controlled campaign replication and budget allocation.

Evaluate latency, cost, scaling, complexity, and consistency.

**Recommend a control-plane/data-plane architecture with independently scalable event processing, while keeping the initial implementation small enough to run locally.**

---

## 7. REFERENCE COMPONENTS

Design logical components including:

1. Advertiser Management
2. Publisher Management
3. Campaign Management
4. Creative Management
5. Taxonomy Service
6. Content Ingestion
7. Context Classification
8. Context Index
9. Candidate Retrieval
10. Eligibility Engine
11. Auction Engine
12. Ranking Engine
13. Budget Manager
14. Pacing Engine
15. Ad Decision API
16. Creative Rendering
17. Impression Tracker
18. Click Tracker
19. Conversion Tracker
20. Fraud Detection
21. Reporting Pipeline
22. Billing/Reconciliation
23. Redis
24. PostgreSQL
25. Kafka
26. OpenSearch
27. Object Storage
28. Observability

Provide Mermaid diagrams for:

- Overall architecture
- Content classification pipeline
- Campaign registration
- Ad selection
- Auction
- Budget reservation
- Impression tracking
- Click tracking
- Conversion attribution
- Event processing
- Budget reconciliation
- Fraud detection
- Multi-region serving

---

# PART E — WEBPAGE CONTENT ANALYSIS

## 8. CONTENT INGESTION

Support two modes.

### Publisher-Provided Content

An authorized publisher submits:

- Page URL
- Page title
- Main content
- Category hints
- Keywords
- Language
- Content version

### Authorized Content Retrieval

The platform fetches public publisher-authorized pages using safe HTTP retrieval.

Requirements:

- Validate publisher ownership.
- Respect robots.txt and applicable website policies.
- Enforce URL allowlists.
- Prevent SSRF.
- Bound redirects.
- Apply response-size limits.
- Apply fetch timeouts.
- Avoid internal/private network access.
- Avoid executing untrusted scripts.

Use the publisher-provided mode as the simplest reliable baseline.

Do not scrape arbitrary sites without authorization.

---

## 9. HTML CONTENT EXTRACTION

Use Jsoup or another maintained parser.

Extract:

- Title
- Meta description
- Main article text
- Headings
- Categories
- Structured metadata
- Language
- Relevant keywords

Remove or downweight:

- Navigation
- Footer
- Repeated boilerplate
- Unrelated advertising content
- Tracking scripts
- Large unrelated link lists

Define input validation and maximum content length.

---

# PART F — CONTEXTUAL CLASSIFICATION

## 10. ADVERTISING TAXONOMY

Create a hierarchical taxonomy.

Example:

```text
Technology
├── Software Development
│   ├── Java
│   ├── Spring Boot
│   └── Cloud Computing
└── Consumer Electronics
    ├── Smartphones
    └── Laptops

Automotive
├── Electric Vehicles
├── Car Insurance
├── Vehicle Accessories
└── Charging Infrastructure

Lifestyle
├── Travel
├── Fitness
└── Home Improvement
```

Support:

- Category IDs
- Parent categories
- Category aliases
- Synonyms
- Exclusions
- Taxonomy versions
- Classification confidence

Avoid category strings scattered throughout business logic.

---

## 11. KEYWORD EXTRACTION

Compare:

1. TF-IDF
2. BM25
3. N-grams
4. Rule-based keyword extraction
5. Named entity extraction
6. Embedding-based semantic matching

Implement TF-IDF/BM25 or another justified interpretable baseline.

Normalize:

- Text encoding
- Tokenization
- Stop words
- Language-specific processing
- Keyword frequency
- Document length

Do not assume keyword frequency alone measures advertising relevance.

---

## 12. TOPIC CLASSIFICATION

Implement a deterministic baseline classifier.

Support:

- Multi-label categories
- Confidence scores
- Classification version
- Unknown category
- Ambiguous content
- Sensitive-content exclusion
- Manual override by authorized publishers/admins

Compare:

- Rule-based classification
- Keyword scoring
- Naive Bayes
- Linear classifiers
- Transformer embeddings

Use ML only when measurable quality improvement justifies it.

---

## 13. SEMANTIC EMBEDDINGS

Evaluate semantic matching between:

- Webpage content
- Campaign targeting description
- Ad creative description
- Product/service metadata

Compare:

- Exact keyword match
- BM25
- Cosine similarity
- Vector embeddings
- Hybrid lexical/vector retrieval

Use pgvector or OpenSearch vector capabilities if justified.

Keep the Java 21 serving path compatible with a versioned embedding model.

Do not interpret arbitrary vector similarity as proof that an ad is safe or relevant.

---

# PART G — BRAND SAFETY AND CONTENT POLICY

## 14. BRAND-SAFETY ENGINE

Classify content for categories such as:

- Violence
- Adult content
- Hate or harassment
- Illegal activity
- Misleading or deceptive claims
- Sensitive news
- Other advertiser-defined exclusions

Use configurable brand-safety policies.

Explain false positives, false negatives, and confidence thresholds.

Do not assume automated classification is infallible.

Provide manual review and appeal mechanisms.

---

## 15. CONTEXTUAL AD ELIGIBILITY

Before auction, evaluate:

1. Advertiser active
2. Campaign active
3. Creative approved
4. Publisher approved
5. Placement compatible
6. Category targeting matched
7. Brand-safety policy satisfied
8. Region/language permitted
9. Budget available
10. Pacing permits participation
11. Start/end time valid
12. Frequency cap or other lawful eligibility rule satisfied

**An ad must not participate in auction unless all required eligibility conditions pass.**

---

# PART H — ADVERTISER AND CAMPAIGN MANAGEMENT

## 16. ADVERTISER MANAGEMENT

Implement:

- Advertiser registration
- Organization profile
- Campaign ownership
- User roles
- Account status
- Spending limits
- Billing reference
- Audit history

Support multi-user advertiser organizations.

---

## 17. CAMPAIGN MANAGEMENT

Implement:

- Campaign creation
- Campaign editing
- Campaign activation
- Campaign pause
- Campaign completion
- Campaign scheduling
- Bid configuration
- Daily budget
- Lifetime budget
- Target categories
- Excluded categories
- Language targeting
- Region targeting where appropriate
- Publisher targeting/exclusions
- Creative assignment

### Campaign states

- DRAFT
- PENDING_REVIEW
- APPROVED
- ACTIVE
- PAUSED
- COMPLETED
- EXHAUSTED
- REJECTED

Define legal transitions and policy enforcement.

---

# PART I — AD CREATIVES

## 18. CREATIVE MANAGEMENT

Support:

- Text ads
- Image ads
- Native ad cards
- Banner ads
- Video thumbnails where justified

Define:

- Creative ID
- Advertiser ID
- Campaign ID
- Headline
- Description
- Media asset
- Destination URL
- Category
- Placement formats
- Approval status
- Version

Use object storage for approved assets.

Do not serve arbitrary advertiser-uploaded HTML/JavaScript with publisher-page privileges.

---

## 19. CREATIVE SECURITY

Enforce:

- File type validation
- File size limits
- Malware scanning where available
- Content security policy
- Approved asset domains
- Safe image processing
- URL validation
- Landing-page validation
- Click destination verification
- Prohibited creative review

Prefer server-rendered/sanitized templates or sandboxed cross-origin iframes.

Never use unsanitized `dangerouslySetInnerHTML` with advertiser content.

---

# PART J — PUBLISHER MANAGEMENT

## 20. PUBLISHER REGISTRATION

Implement:

- Publisher account
- Verified website/domain ownership
- Website registration
- Ad placement definitions
- Placement dimensions
- Allowed formats
- Category exclusions
- Competitor exclusions
- Revenue reporting
- Publisher settings

Do not allow arbitrary users to register ads for websites they do not control.

---

## 21. AD INVENTORY

Model:

- Publisher
- Website
- Page
- Placement
- Format
- Device compatibility
- Placement status
- Allowed creative types

Example placements:

- Header banner
- Sidebar
- In-article
- Footer
- Native content card

Define responsive layouts.

Avoid ad placement patterns that deceptively imitate navigation or editorial recommendations.

---

# PART K — AD SELECTION ENGINE

## 22. CANDIDATE RETRIEVAL

Given a page context and placement, retrieve campaigns using:

- Category index
- Keyword index
- Targeting constraints
- Creative format
- Publisher policy
- Campaign status
- Language/region

Use OpenSearch or PostgreSQL indexing based on scale.

Return a bounded candidate set.

Do not scan all active campaigns for every ad request.

---

## 23. RELEVANCE SCORING

Implement an explainable baseline score.

Possible features:

- Topic match
- Keyword similarity
- Semantic similarity
- Creative quality
- Bid
- Historical contextual performance
- Freshness
- Publisher compatibility

Example:

`RelevanceScore = w1×TopicMatch + w2×KeywordSimilarity + w3×SemanticSimilarity`

Normalize features.

Avoid using a higher bid as a substitute for relevance.

---

# PART L — REAL-TIME AUCTION

## 24. COMPARE AD SELECTION MODELS

Evaluate:

1. Fixed-priority selection
2. Weighted rotation
3. Highest-bid selection
4. First-price auction
5. Second-price auction
6. Quality-adjusted ranking

Explain:

- Selection rules
- Pricing
- Incentives
- Revenue implications
- Fairness
- Budget constraints
- Computational complexity

Implement a fixed-priority or weighted baseline first, then a clearly specified auction model.

Do not claim to implement a standards-compliant real-time bidding exchange unless those protocols and integrations are explicitly implemented.

---

## 25. AUCTION ENGINE

Define:

- Auction ID
- Eligible candidates
- Bid amount
- Currency
- Quality/relevance score
- Ranking formula
- Winner
- Clearing price
- Decision timestamp
- Model version

### Example ranking

`AdRank = NormalizedBid × QualityScore`

If implementing second-price-like pricing, specify the exact rule, quality adjustments, reserve price, tie-breaking, and payment rounding.

Do not call an approximate algorithm a true second-price auction without verifying its semantics.

Use deterministic tie-breaking.

---

# PART M — BUDGET MANAGEMENT

## 26. CAMPAIGN BUDGETS

Support:

- Lifetime budget
- Daily budget
- Maximum bid
- Current reserved amount
- Billable spend
- Remaining budget
- Campaign currency
- Spend history

Use integer minor units or `BigDecimal`.

Never use floating-point arithmetic for currency.

Define budget state accounting consistently.

---

## 27. DISTRIBUTED BUDGET CORRECTNESS

Compare:

### PostgreSQL Authoritative Accounting

Atomic transactions and conditional updates.

### Redis Budget Counters

Fast admission with explicit durability and reconciliation limitations.

### Budget Token/Lease Allocation

Preallocate bounded spending rights to ad-serving partitions.

### Asynchronous Spend Aggregation

Efficient reporting but insufficient by itself for strict hard-budget enforcement.

### Recommended baseline

Use PostgreSQL for authoritative hard-budget enforcement and Redis for appropriate pacing/admission optimization.

Evaluate preallocated budget leases for large-scale serving only with explicit ownership, expiration, and recovery.

---

## 28. BUDGET RESERVATION

Design:

1. Evaluate campaign budget.
2. Atomically reserve an allowed exposure amount.
3. Issue ad decision.
4. Confirm billable usage upon qualified event.
5. Release unused/expired reservation.
6. Reconcile ambiguous states.

A CPC campaign and a CPM campaign require different reservation strategies.

Do not assume every impression consumes the maximum CPC bid permanently.

Define liabilities, expiry, late-click windows, and financial exposure bounds.

### Critical invariant

`Committed Billable Spend + Outstanding Reserved Liability <= Enforced Budget`

State exceptions, currency semantics, and reservation expiry policies explicitly.

---

# PART N — BUDGET PACING

## 29. PACING ENGINE

Implement:

- Even pacing
- Accelerated pacing
- Daily budget distribution
- Time-of-day pacing
- Remaining budget analysis
- Forecasted delivery
- Campaign lifetime pacing

Compare:

- Fixed interval budget release
- Token bucket
- Proportional pacing
- Feedback-based pacing

Start with deterministic simple pacing.

Do not introduce complex adaptive algorithms without performance evidence.

---

# PART O — IMPRESSION TRACKING

## 30. IMPRESSION MEASUREMENT

Define:

- Ad decision
- Creative loaded
- Rendered impression
- Viewable impression
- Billable impression

These are distinct.

Implement signed event tokens bound to:

- Ad decision ID
- Campaign ID
- Creative ID
- Placement
- Expiration
- Event type
- Appropriate request context

Validate event authenticity without invasive tracking.

### Important requirement

Client-side events are signals, not unquestionable proof of genuine human attention.

Implement deduplication and invalid-traffic checks.

---

# PART P — CLICK TRACKING

## 31. CLICK REDIRECTION

Implement a secured click endpoint.

Example:

`GET /api/v1/ads/click/{signedToken}`

Required behavior:

1. Validate token.
2. Validate destination.
3. Record click event idempotently.
4. Apply fraud checks.
5. Redirect to the approved advertiser destination.

Prevent:

- Open redirects
- Arbitrary URL injection
- Token forgery
- Duplicate billing
- Tracking-token abuse
- Sensitive query parameter leakage

Use a safe allowlisted landing-page destination.

---

# PART Q — CONVERSION TRACKING

## 32. CONVERSION EVENTS

Support:

- Purchase
- Signup
- Lead
- Subscription
- Other advertiser-defined allowed conversions

Use authorized server-to-server events for a reliable baseline.

Define:

- Conversion ID
- Advertiser
- Campaign attribution reference
- Event time
- Conversion type
- Value/currency where applicable

Implement idempotent conversion processing.

Avoid hidden cross-site tracking.

---

## 33. ATTRIBUTION

Compare:

- Last click
- First click
- View-through attribution
- Multi-touch attribution

Implement a simple, explicitly scoped baseline.

Define:

- Attribution window
- Consent/measurement basis
- Event correlation
- Duplicate conversion handling
- Late events
- Attribution confidence

Do not claim attribution establishes causality.

---

# PART R — FRAUD AND INVALID TRAFFIC

## 34. INVALID TRAFFIC DETECTION

Implement baseline detection for:

- Excessive repeated clicks
- Duplicate impressions
- Suspicious event velocity
- Known automated test traffic
- Invalid event tokens
- Malformed browser signals
- Impossible timestamps
- Publisher self-click abuse
- Replayed conversion events

Use a layered approach.

Do not assume IP address alone reliably identifies a human or unique user.

---

## 35. FRAUD REVIEW AND BILLING

Support:

- Provisional billable event
- Validated event
- Invalid event
- Reversed charge
- Investigation status
- Audit history

Implement rules and reports.

Do not automatically bill or financially penalize participants solely based on an opaque anomaly score.

Provide administrative review for significant disputed charges.

---

# PART S — PRIVACY AND CONSENT

## 36. PRIVACY-BY-DESIGN

Prioritize webpage-context signals rather than invasive user profiles.

Support:

- Context-only serving
- Data minimization
- Configurable event retention
- Consent-aware optional analytics
- Regional restrictions
- User data deletion where applicable
- Aggregate reporting
- Pseudonymous operation identities

Do not use browser fingerprinting, hidden cross-site identifiers, or sensitive inferred characteristics to circumvent consent.

Do not infer or target based on sensitive personal attributes without appropriate legal authorization and a justified product need.

---

## 37. AD TRANSPARENCY

All placements must clearly indicate advertising.

Support labels such as:

- Sponsored
- Advertisement

Distinguish advertising from editorial recommendations.

Provide advertiser identity information and appropriate disclosure where required.

Do not disguise sponsored content as independent organic results.

---

# PART T — KAFKA EVENT PIPELINE

## 38. EVENT-DRIVEN ARCHITECTURE

Define event types such as:

- PageAnalyzed
- CampaignActivated
- CampaignPaused
- AdDecisionMade
- ImpressionRecorded
- ClickRecorded
- ConversionRecorded
- SpendCommitted
- InvalidTrafficDetected
- BudgetExhausted

Design Kafka topics with justified partition keys.

Use versioned event contracts.

Handle duplicates, retries, DLQ, out-of-order events, and replay.

Do not use Kafka alone as the authoritative campaign budget store.

---

## 39. TRANSACTIONAL OUTBOX AND INBOX

Use Transactional Outbox wherever local database changes must reliably publish integration events.

Use durable inbox/idempotent consumers for business effects.

Implement:

- Event ID
- Aggregate ID
- Aggregate version
- Schema version
- Correlation ID
- At-least-once processing
- Idempotent state updates
- Retry
- DLQ
- Replay
- Reconciliation

Separate ad-serving latency from analytical event processing.

---

# PART U — REPORTING AND ANALYTICS

## 40. ADVERTISER DASHBOARD

Implement:

- Total spend
- Remaining budget
- Impressions
- Clicks
- Conversions
- CTR
- CPC
- CPM
- Conversion rate
- Cost per conversion
- Campaign performance
- Category performance
- Creative performance
- Time-series charts

Define metric formulas and billable-event semantics.

Do not compute financial totals solely from approximate frontend counters.

---

## 41. PUBLISHER DASHBOARD

Implement:

- Ad requests
- Fill rate
- Impressions
- Clicks
- Estimated revenue
- Validated revenue
- Revenue by placement
- Category performance
- Invalid traffic
- Policy exclusions

Define revenue share and settlement semantics.

Distinguish estimated versus settled revenue.

---

## 42. ANALYTICS PIPELINE

Compare:

- PostgreSQL aggregates
- Materialized views
- Kafka stream aggregation
- ClickHouse/OLAP database
- Object-storage analytics

Implement PostgreSQL-based aggregated reporting initially.

Introduce OLAP infrastructure when volume justifies it.

Support late-arriving events and corrections.

---

# PART V — JAVA 21 + SPRING BOOT BACKEND

## 43. TECHNOLOGY STACK

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot, compatible verified release |
| Build | Maven |
| REST APIs | Spring Web MVC |
| Security | Spring Security/OAuth2 Resource Server |
| Database | PostgreSQL |
| Persistence | Spring Data JDBC/JPA/jOOQ as justified |
| Migrations | Flyway |
| Cache | Redis |
| Events | Apache Kafka |
| Content parsing | Jsoup |
| Lexical matching | OpenSearch or PostgreSQL |
| Vector search | pgvector/OpenSearch where justified |
| Asset storage | S3-compatible |
| Resilience | Resilience4j |
| Metrics | Micrometer |
| Tracing | OpenTelemetry |
| Unit testing | JUnit 5/Mockito |
| Integration testing | Testcontainers |
| Load testing | k6/Gatling |
| Containers | Docker |
| Deployment | Kubernetes |

Verify exact dependency versions and interoperability against official documentation.

Do not add infrastructure solely because it is listed.

---

# PART W — NEXT.JS FRONTEND

## 44. FRONTEND REQUIREMENTS

Use:

- Next.js App Router
- React
- TypeScript
- Tailwind CSS or an established design system
- Accessible UI components
- Server Components where appropriate
- Client Components for interactive functionality
- TanStack Query or another justified data-fetching approach
- Zod or equivalent schema validation where useful

Verify framework compatibility.

Keep secret keys, advertiser billing credentials, administrative tokens, and private backend configuration on the server.

---

## 45. ADVERTISER PORTAL

Build functional pages for:

1. Advertiser onboarding
2. Campaign list
3. Campaign creation
4. Campaign editor
5. Targeting configuration
6. Budget and bid configuration
7. Creative uploads
8. Creative review status
9. Campaign activation/pause
10. Campaign analytics
11. Spend reporting
12. Account/team settings

Use actual backend APIs.

Implement loading, validation, error, empty, and authorization states.

---

## 46. PUBLISHER PORTAL

Build pages for:

1. Publisher onboarding
2. Website/domain verification
3. Website management
4. Ad placement creation
5. Allowed/blocklisted categories
6. Ad preview
7. Integration instructions
8. Placement analytics
9. Revenue reporting
10. Privacy settings

---

## 47. PLATFORM ADMIN PORTAL

Build pages for:

- Advertiser review
- Publisher review
- Creative approval
- Campaign inspection
- Taxonomy management
- Brand safety
- Fraud review
- Financial reconciliation
- Usage monitoring
- Audit logs

Enforce backend RBAC.

Do not rely on hidden frontend navigation for authorization.

---

# PART X — PUBLISHER DEMO WEBSITE

## 48. BUILD A REAL CONTEXTUAL ADS DEMO

Create a Next.js publisher demonstration website.

Include articles such as:

- Electric Vehicles and Charging
- Java 21 Development
- Cloud Infrastructure
- Travel Destinations
- Fitness Equipment
- Home Improvement

Each article should contain different topic signals.

Add ad placements such as:

- Header banner
- In-article ad
- Sidebar card

When an article loads:

1. Resolve its approved context classification.
2. Request an ad.
3. Receive a creative decision.
4. Display the creative.
5. Show a visible "Sponsored" label.
6. Record a qualified impression event.
7. Track authorized clicks.
8. Update campaign reporting.

### Mandatory demonstration

Changing the article topic must change the eligible advertisement pool when relevant campaigns are available.

Do not use hardcoded article-to-ad mapping as the only implementation.

---

# PART Y — EMBEDDABLE AD SDK

## 49. PUBLISHER INTEGRATION

Build a lightweight ad integration mechanism.

Possible options:

- React component
- Next.js component
- Cross-origin iframe
- Minimal JavaScript SDK

The baseline should support:

- Placement ID
- Approved publisher ID
- Responsive rendering
- Loading states
- Error fallback
- Ad labeling
- Impression measurement
- Click handling
- CSP compatibility
- Safe creative isolation

Never execute arbitrary advertiser code within the publisher's origin.

For external publisher integration, prefer sandboxed cross-origin rendering where appropriate.

Avoid unnecessary browser storage or user tracking.

---

# PART Z — DATABASE DESIGN

## 50. POSTGRESQL SCHEMA

Design tables such as:

- advertisers
- advertiser_members
- publishers
- publisher_websites
- publisher_placements
- website_verifications
- ad_categories
- taxonomy_versions
- webpage_contexts
- contextual_keywords
- campaigns
- campaign_targeting
- campaign_exclusions
- creatives
- creative_reviews
- campaign_budgets
- budget_reservations
- budget_movements
- ad_decisions
- impression_events
- click_events
- conversion_events
- fraud_flags
- billing_records
- publisher_revenue
- event_outbox
- processed_events
- audit_events

Create only schemas required by the implementation.

Define:

- Primary keys
- Unique constraints
- Foreign keys
- Check constraints
- Indexes
- Optimistic versions
- Retention
- Partitioning
- Financial audit history
- Tenant ownership

Use Flyway.

For billing and budgets, use immutable movements or an auditable accounting model rather than repeatedly overwriting unexplained financial totals.

---

# PART AA — REST API DESIGN

## 51. REQUIRED APIS

### Context analysis

- `POST /api/v1/context/analyze`
- `POST /api/v1/context/pages`
- `GET /api/v1/context/pages/{pageId}`

### Advertisers

- `POST /api/v1/advertisers`
- `GET /api/v1/advertisers/{id}`

### Campaigns

- `POST /api/v1/campaigns`
- `GET /api/v1/campaigns`
- `GET /api/v1/campaigns/{id}`
- `PATCH /api/v1/campaigns/{id}`
- `POST /api/v1/campaigns/{id}/activate`
- `POST /api/v1/campaigns/{id}/pause`

### Creatives

- `POST /api/v1/creatives`
- `GET /api/v1/creatives/{id}`
- `POST /api/v1/admin/creatives/{id}/approve`
- `POST /api/v1/admin/creatives/{id}/reject`

### Publishers

- `POST /api/v1/publishers`
- `POST /api/v1/publishers/websites`
- `POST /api/v1/publishers/placements`
- `GET /api/v1/publishers/placements`

### Ad serving

- `POST /api/v1/ads/decide`
- `POST /api/v1/ads/impressions`
- `GET /api/v1/ads/click/{token}`

### Conversions

- `POST /api/v1/conversions`

### Analytics

- `GET /api/v1/analytics/advertisers/{id}`
- `GET /api/v1/analytics/campaigns/{id}`
- `GET /api/v1/analytics/publishers/{id}`

### Administration

- `GET /api/v1/admin/fraud`
- `GET /api/v1/admin/budget-reconciliation`
- `POST /api/v1/admin/budget-reconciliation/run`

For every endpoint define:

- Authentication
- Authorization
- JSON contracts
- Validation
- Idempotency
- Pagination
- Status codes
- Error contracts
- Rate limits
- OpenAPI specifications

Use RFC 9457 Problem Details where applicable.

---

# PART AB — SECURITY AND THREAT MODELING

## 52. SECURITY REQUIREMENTS

Implement:

1. OAuth2/OIDC
2. Spring Security
3. Advertiser RBAC
4. Publisher RBAC
5. Admin RBAC
6. Tenant/resource isolation
7. CSRF defenses where relevant
8. CORS controls
9. Input validation
10. Secure creative uploads
11. Approved redirect destinations
12. SSRF prevention
13. Signed event tokens
14. Event replay controls
15. Rate limiting
16. Audit logging
17. TLS
18. Secret management
19. Secure billing data
20. Safe browser rendering
21. Protection against XSS
22. Protection against clickjacking
23. Advertising disclosure
24. Data minimization
25. Privacy and consent policies

Perform STRIDE threat modeling.

Include tests for:

- Cross-tenant campaign access
- Fraudulent event submission
- Open redirect
- Creative XSS
- Forged impressions
- Fake clicks
- Budget abuse
- Publisher impersonation
- SSRF through page analysis
- Unauthorized creative approval
- Malicious URL parameters

---

# PART AC — FAILURE SCENARIOS

## 53. ANALYZE THESE 40 SCENARIOS

1. Publisher submits malicious HTML.
2. Publisher forges webpage category metadata.
3. Webpage contains conflicting topics.
4. Content classifier returns low confidence.
5. Context index becomes stale.
6. Campaign is paused during ad selection.
7. Creative is rejected after being cached.
8. Redis becomes unavailable.
9. PostgreSQL primary fails.
10. Kafka becomes unavailable.
11. OpenSearch becomes unavailable.
12. Two ad-serving instances select the same final campaign budget.
13. Ad decision is generated but browser never renders the creative.
14. Budget is reserved but impression never arrives.
15. Impression arrives after reservation expiration.
16. Duplicate impression event is submitted.
17. Click tracking receives repeated requests.
18. Click redirect destination is manipulated.
19. Provider/browser blocks impression tracking.
20. Conversion event arrives before click-processing completes.
21. Same conversion is submitted multiple times.
22. Fraudulent traffic inflates click counts.
23. Suspicious traffic creates false conversions.
24. Advertiser exhausts daily budget.
25. Campaign lifetime budget expires during a burst.
26. Budget pacing worker crashes.
27. Redis counter diverges from PostgreSQL.
28. Kafka consumer processes financial events twice.
29. Billing reconciliation detects inconsistent charges.
30. Publisher claims another publisher's domain.
31. Tenant A accesses Tenant B campaigns.
32. Uploaded creative contains malicious script.
33. Campaign targets a prohibited content category.
34. Ad request arrives without an approved context.
35. Ad decision latency exceeds its deadline.
36. A popular webpage causes a traffic spike.
37. One advertiser dominates all auctions.
38. Next.js rendering fails to load an ad.
39. Mixed deployment versions use incompatible event schemas.
40. Multi-region data-plane replicas overspend a shared campaign budget.

For each provide:

- Root cause
- Business impact
- Financial correctness impact
- Security/privacy impact
- User-visible behavior
- Detection
- Prevention
- Recovery
- Automated test
- Residual risk

---

# PART AD — TEST STRATEGY

## 54. UNIT TESTS

Test:

- HTML extraction
- Keyword extraction
- Topic classification
- Taxonomy mapping
- Relevance scoring
- Eligibility rules
- Auction ranking
- Tie-breaking
- Budget calculations
- Pacing calculations
- Creative approval
- Event idempotency
- Attribution
- Fraud heuristics
- Financial rounding
- Tenant ownership

---

## 55. INTEGRATION TESTS

Use Testcontainers for:

- PostgreSQL
- Redis
- Kafka
- OpenSearch where implemented
- Object storage where implemented

Verify:

- Campaign creation
- Creative approval
- Publisher registration
- Context analysis
- Candidate retrieval
- Ad selection
- Budget reservation
- Impression ingestion
- Click tracking
- Conversion attribution
- Billing updates
- Kafka replay
- Financial reconciliation

---

## 56. CONCURRENCY TESTS

Verify:

- Multiple ad-serving pods consuming shared budget
- Concurrent campaign pause and ad selection
- Duplicate impression processing
- Duplicate conversion processing
- Concurrent budget expiry
- Redis admission races
- Budget lease reclamation
- Event replay under failover

Use actual PostgreSQL transactions for financial correctness assertions.

---

## 57. FRONTEND TESTS

Use:

- TypeScript type checks
- ESLint
- Component tests
- Playwright end-to-end tests
- Accessibility checks

Verify:

- Advertiser campaign management
- Publisher onboarding
- Creative upload
- Topic-dependent ad selection
- Sponsored labels
- Ad rendering
- Dashboard data
- RBAC
- Responsive layouts
- Error states
- Unauthorized operations

Do not substitute screenshots for interaction tests.

---

# PART AE — PERFORMANCE ENGINEERING

## 58. CAPACITY PLANNING

Calculate:

1. Ad requests/sec
2. Candidate lookup latency
3. Active campaign index size
4. Page-context storage
5. Auction CPU cost
6. Budget operations/sec
7. Redis memory
8. PostgreSQL transaction throughput
9. Kafka ingestion volume
10. Impression events/sec
11. Click events/sec
12. Conversion events/sec
13. Reporting storage growth
14. Creative CDN bandwidth
15. Cache hit ratios
16. Reconciliation volume
17. Infrastructure cost

Distinguish estimated throughput from measured throughput.

---

## 59. BENCHMARKS

Create reproducible tests for:

- 1,000 ad requests/sec
- 10,000 ad requests/sec
- 50,000 ad requests/sec
- 100,000 ad requests/sec as a scale target

Separately benchmark:

- Context lookup
- Keyword matching
- Candidate retrieval
- Eligibility filtering
- Auction
- Budget admission
- Impression ingestion
- Click processing
- Analytics aggregation

Measure:

- Offered/achieved RPS
- p50/p95/p99 latency
- Cache hit rate
- Candidate count
- Auction duration
- Budget contention
- Budget overspend violations
- Fill rate
- Event ingestion lag
- Kafka consumer lag
- PostgreSQL lock waits
- Redis latency
- JVM CPU/heap/GC
- Error rate

Never fabricate benchmarks.

Use synthetic campaigns and traffic.

Do not generate invalid real-world advertising traffic during load testing.

---

# PART AF — OBSERVABILITY

## 60. METRICS, LOGS, AND TRACES

Use:

- Micrometer
- OpenTelemetry
- Prometheus
- Grafana
- Structured JSON logging

Capture:

- Ad request rate
- Ad decision latency
- Campaign eligibility rejection count
- No-fill rate
- Auction latency
- Context lookup latency
- Classifier confidence distribution
- Budget reservation failures
- Budget exhaustion events
- Spend reconciliation mismatch
- Impression ingestion
- Click ingestion
- Conversion ingestion
- Invalid traffic
- Kafka consumer lag
- Database saturation
- Cache hit/miss
- Creative delivery errors

Do not use advertiser IDs, publisher IDs, user IDs, or event IDs as unrestricted metric labels.

Use traces and secured operational queries for individual investigations.

---

# PART AG — PROJECT STRUCTURE

## 61. SOURCE CODE ORGANIZATION

Create a maintainable monorepo.

```text
contextual-ads-platform/
├── README.md
├── docs/
│   ├── requirements.md
│   ├── architecture.md
│   ├── contextual-classification.md
│   ├── taxonomy.md
│   ├── ad-selection.md
│   ├── auction.md
│   ├── budget-accounting.md
│   ├── pacing.md
│   ├── measurement.md
│   ├── attribution.md
│   ├── fraud-prevention.md
│   ├── privacy-security.md
│   ├── capacity-planning.md
│   ├── runbook.md
│   └── decisions/
├── backend/
│   ├── pom.xml
│   ├── ads-domain/
│   ├── ads-control-plane/
│   ├── ads-context-engine/
│   ├── ads-serving/
│   ├── ads-budget/
│   ├── ads-measurement/
│   ├── ads-reporting/
│   └── ads-integration-tests/
├── frontend/
│   ├── package.json
│   ├── apps/
│   │   ├── advertiser-portal/
│   │   ├── publisher-portal/
│   │   ├── admin-portal/
│   │   └── publisher-demo/
│   └── packages/
│       ├── ui/
│       ├── api-client/
│       └── ad-renderer/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Adjust actual deployables and modules based on cohesion, independent scaling, and repository conventions.

Avoid speculative microservices and unused abstractions.

---

# PART AH — IMPLEMENTATION PLAN

## 62. DEVELOPMENT PHASES

Execute incrementally.

### Phase 1 — Requirements and Architecture

Define campaign types, publisher integration, context-only targeting, financial invariants, latency SLOs, and data ownership.

### Phase 2 — Project Foundation

Create Java 21 Spring Boot backend, Next.js frontend, PostgreSQL migrations, authentication, local infrastructure, and CI checks.

### Phase 3 — Advertising Domain

Implement advertisers, publishers, websites, placements, campaigns, creatives, and authorization.

### Phase 4 — Content Context Engine

Implement publisher-authorized content ingestion, HTML extraction, keyword processing, taxonomy, and topic classification.

### Phase 5 — Contextual Matching

Implement candidate retrieval, category/keyword matching, exclusion rules, and relevance scoring.

### Phase 6 — Auction Engine

Implement deterministic ranking, bid evaluation, winner selection, and auction audit.

### Phase 7 — Budget and Pacing

Implement authoritative budget constraints, reservations, expiry, spend recording, and pacing.

### Phase 8 — Ad Serving

Implement the low-latency ad decision API, cache behavior, creative response, and safe publisher integration.

### Phase 9 — Event Tracking

Implement authenticated/signed impression, click, and conversion processing with idempotency.

### Phase 10 — Fraud Prevention

Implement invalid-traffic checks, event classification, review workflows, and billing adjustments.

### Phase 11 — Analytics

Implement advertiser and publisher performance metrics, aggregates, and reconciliation.

### Phase 12 — Next.js Portals

Implement functional advertiser, publisher, and administrator interfaces.

### Phase 13 — Publisher Demo

Implement multiple topic-specific articles and contextual ad placements.

### Phase 14 — Privacy and Security

Implement consent-aware measurement, data retention, tenant isolation, creative safety, and threat mitigations.

### Phase 15 — Observability

Implement metrics, structured logs, tracing, dashboards, alerts, and runbooks.

### Phase 16 — Deployment

Implement Docker Compose, Kubernetes/Helm, secrets management, and CI/CD.

### Phase 17 — Verification

Run backend unit/integration/concurrency/security tests and frontend type/component/Playwright tests.

### Phase 18 — Performance

Benchmark candidate retrieval, auctions, budget admission, event ingestion, and ad-serving latency.

### Phase 19 — Final Architecture Review

Review contextual relevance, security, budget correctness, accounting, latency, scalability, privacy, and operational risks.

For each phase:

- Create actual source files.
- Keep the project buildable.
- Run relevant verification.
- Fix discovered defects.
- Preserve unrelated work.
- Document significant architecture decisions.
- Report exact checks executed.
- Identify unverified claims and remaining risks.

If development tools are available, implement directly in the repository and run applicable commands.

Otherwise, provide complete source files, paths, and reproducible commands without claiming execution.

---

# PART AI — REQUIRED DELIVERABLES

## 63. COMPLETE PROJECT OUTPUTS

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Ad-serving guarantees
- Financial invariants
- Privacy requirements
- Acceptance criteria

### Deliverable 2 — Architecture

- HLD diagrams
- LLD diagrams
- Context classification pipeline
- Ad decision sequence
- Auction sequence
- Budget accounting
- Event tracking
- Analytics pipeline
- Deployment architecture

### Deliverable 3 — Java Backend

- Advertiser management
- Publisher management
- Campaign management
- Creative management
- Content analysis
- Contextual matching
- Eligibility
- Auction
- Budget/pacing
- Ad serving
- Event tracking
- Attribution
- Fraud detection
- Reporting
- Security

### Deliverable 4 — Next.js Frontend

- Advertiser portal
- Publisher portal
- Admin portal
- Publisher demonstration website
- Secure ad-rendering integration
- Responsive dashboards
- Accessible interactions

### Deliverable 5 — Automated Tests

- Java unit tests
- Integration tests
- Concurrency tests
- Financial correctness tests
- Security tests
- Frontend tests
- Playwright end-to-end tests
- Failure-recovery tests

### Deliverable 6 — Infrastructure

- Docker
- Docker Compose
- PostgreSQL
- Redis
- Kafka
- OpenSearch where implemented
- Kubernetes/Helm
- CI/CD
- Observability

### Deliverable 7 — Performance

- Benchmark scripts
- Actual measured results
- Capacity estimates
- Classification-quality evaluation
- Ad selection latency
- Budget consistency results
- Bottleneck analysis

### Deliverable 8 — Documentation

- README
- Architecture Decision Records
- Contextual classification
- Taxonomy specification
- Campaign and auction rules
- Budget accounting
- Event measurement semantics
- Attribution rules
- Fraud prevention
- Privacy/security
- Deployment guide
- Operations runbooks
- Known limitations

---

# PART AJ — ARCHITECTURE REVIEW QUESTIONS

## 64. ANSWER THESE 50 QUESTIONS

1. How would you design a contextual advertising platform?
2. How does contextual advertising differ from behavioral targeting?
3. How do you analyze webpage content?
4. How do you extract keywords?
5. How does TF-IDF work?
6. How does BM25 work?
7. What is multi-label topic classification?
8. How do semantic embeddings improve matching?
9. What is hybrid lexical/vector retrieval?
10. How do you manage an advertising taxonomy?
11. How do you classify pages with multiple topics?
12. How do you handle low-confidence classifications?
13. What is brand safety?
14. How do you prevent irrelevant ads?
15. How do you store and retrieve active campaigns?
16. How do you build an eligibility engine?
17. How do you design an ad-serving request pipeline?
18. How do you achieve low-latency ad selection?
19. How do you rank ads using bid and quality?
20. How does a first-price auction work?
21. How does a second-price auction work?
22. How do you implement deterministic tie-breaking?
23. What is campaign pacing?
24. How do you enforce daily and lifetime budgets?
25. How do you prevent budget overspending across pods?
26. How do Redis budget counters differ from PostgreSQL accounting?
27. How do budget token leases work?
28. How do you recover unused budget reservations?
29. What is the difference between an ad decision and a viewable impression?
30. How do you measure impressions?
31. How do you prevent impression replay?
32. How do you implement secure click tracking?
33. How do you prevent open redirects?
34. What is conversion attribution?
35. Why does attribution not prove causality?
36. How do you detect invalid traffic?
37. How do you avoid duplicate advertiser charges?
38. How does Transactional Outbox help event processing?
39. How do you make Kafka event consumers idempotent?
40. How do you handle out-of-order impression and conversion events?
41. How do you isolate advertisers and publishers?
42. How do you secure uploaded ad creatives?
43. How do you prevent SSRF during webpage analysis?
44. How do you implement contextual ads in Next.js?
45. Why should ad creatives be isolated from publisher JavaScript?
46. How do you respect user privacy without behavioral tracking?
47. How do you evaluate contextual matching accuracy?
48. How do you scale ad serving to 100,000 RPS?
49. How do you benchmark ad selection and budget correctness?
50. What evidence is required before enterprise production deployment?

For every answer provide:

- Architecture reasoning
- Algorithmic explanation
- Data consistency
- Concurrency correctness
- Failure recovery
- Scalability
- Performance
- Security/privacy
- Operational trade-offs

---

# DEFINITION OF DONE

## 65. ACCEPTANCE CRITERIA

The implementation is complete only when:

- Java 21 Spring Boot backend builds.
- Next.js TypeScript frontend builds.
- PostgreSQL migrations execute.
- Authentication and role authorization work.
- Advertisers can manage campaigns.
- Publishers can register verified websites and placements.
- Creatives can be uploaded, validated, and approved.
- Webpage content analysis works.
- Keyword and topic classification work.
- Relevant campaigns are retrieved.
- Campaign eligibility is correctly enforced.
- Auction/ranking produces deterministic decisions.
- Campaign budgets are enforced under the documented model.
- Budget pacing works.
- Concurrent ad-serving requests do not violate supported hard-budget invariants.
- Ad decisions can be rendered in the publisher demo.
- Sponsored labels are visible.
- Impression events are validated and idempotent.
- Click tracking prevents open redirects.
- Conversion processing is idempotent.
- Invalid traffic is classified.
- Advertiser charges can be reconciled.
- Publisher reporting works.
- Next.js dashboards use actual backend APIs.
- Cross-tenant access is prevented.
- Creative rendering is isolated and secure.
- Contextual targeting works without invasive cross-site tracking.
- Docker Compose supports the complete end-to-end demonstration.
- Kubernetes configuration is provided.
- Automated tests cover important failure and concurrency scenarios.
- Metrics, traces, and alerts function.
- Benchmarks are reproducible.
- Performance and relevance claims are measured or explicitly unverified.
- Remaining limitations and production risks are documented.

Do not declare production readiness solely because an advertisement appears on a webpage.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, enterprise-oriented Contextual Advertising Platform using Java 21, Spring Boot, Next.js, PostgreSQL, Redis, and Kafka where justified**.

Prioritize:

1. **Contextual relevance:** Show advertisements based on actual webpage topics and content.
2. **Financial correctness:** Enforce campaign budgets and prevent duplicate charges.
3. **Low-latency serving:** Bound candidate retrieval, eligibility, ranking, and budget admission.
4. **Security:** Protect advertiser accounts, publishers, uploaded creatives, and tracking endpoints.
5. **Privacy:** Prefer webpage-context signals without invasive cross-site behavioral tracking.
6. **Brand safety:** Enforce category exclusions and creative approval.
7. **Fraud resistance:** Detect invalid impression/click/conversion traffic and support reconciliation.
8. **Scalability:** Separate administrative management, ad serving, and analytics workloads.
9. **Maintainability:** Pragmatic DDD, SOLID, Clean Architecture, and minimal unnecessary dependencies.
10. **Frontend quality:** Deliver functional, responsive Next.js advertiser/publisher/admin experiences.
11. **Observability:** Monitor selection latency, fill rate, budget accuracy, invalid traffic, and data pipeline health.
12. **Reproducibility:** Complete source, seeded campaigns, publisher demo, tests, Docker, Kubernetes, and benchmarks.

Begin with a concise architectural assessment.

Choose the contextual classification strategy, campaign targeting model, ad-selection algorithm, auction model, budget consistency strategy, measurement rules, and privacy architecture.

Then implement the complete platform incrementally.

Use available development tools to create and edit source files, compile applications, execute tests, run the local platform, verify frontend/backend integration, benchmark supported workloads, and fix defects.

At completion report:

- Implemented backend features
- Implemented Next.js applications
- Contextual classification algorithms
- Ad-selection and auction rules
- Budget and financial guarantees
- Event measurement and attribution semantics
- Architecture decisions and trade-offs
- Project structure
- Exact build and run commands
- Tests actually executed
- Benchmark results actually measured
- Known limitations
- Remaining security, privacy, financial, and operational risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end Contextual Advertising Platform, including webpage content analysis, advertiser and publisher management, campaign targeting, creative approval, ad ranking and auction, budget management, ad serving, secure Next.js rendering, event tracking, analytics, automated verification, observability, and Kubernetes deployment.**
````