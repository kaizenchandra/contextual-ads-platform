# Versioned creatives, image uploads and isolated publisher rendering

## Implemented path

The platform supports versioned TEXT and IMAGE native cards. Campaign creation initializes text version 1 in the same database transaction through a Flyway-managed trigger. Existing campaigns/decisions are backfilled by V5 without changing their monetary accounting. The current creative pointer is selected under the campaign lock. New versions always enter PENDING and disable new decisions until the exact current version is approved. Approval of an older version cannot activate a newer pending version. Native cards are responsive inside a fixed-height iframe; arbitrary banner/video formats are not implemented.

Advertisers upload through POST `/api/v1/creatives/assets` (multipart `file`), create metadata with POST `/api/v1/creatives`, list owned versions at GET `/api/v1/creatives`, and read an owned version at GET `/api/v1/creatives/{id}`. Draft JSON:

```json
{"campaignId":"UUID","headline":"Developer tools","description":"A concise product description","destination":"https://example.org/product","assetId":"owned-asset-UUID-or-null"}
```

The advertiser portal has actual upload/version forms. Administrators use GET `/api/v1/admin/creatives`, private image previews and POST `/api/v1/admin/creatives/{creativeId}/approve|reject` with `{reason}`. Review history is append-only and available through `/api/v1/admin/creatives/{id}/reviews`. Review is backend ADMIN-authorized; advertisers cannot approve or reference another tenant's campaign/asset. The old campaign-ID approval alias remains for initial text version 1 only. Later versions require the exact creative ID, preventing a stale campaign shortcut from approving changed content.

Reviewed headline, description, destination, format and asset reference cannot be updated in place: the database requires another version. Decision records snapshot creative ID, text and destination. A later creative cannot change an earlier click destination. Candidate admission verifies the creative belongs to that campaign and is approved; review obtains campaign/creative locks in the same order. Rendering and measurement recheck current approval/version. Rejecting a creative prevents new decisions, new frames and new impression/click acceptance. Already displayed pixels cannot be remotely erased; a revoked outstanding liability remains reserved until normal expiry. Historical charges are investigated/reversed through the separate financial review workflow, never silently rewritten by creative review.

## Bounded image processing and storage

Uploaded filenames and MIME claims are not trusted. The decoder accepts actual PNG/JPEG signatures and maintained Java ImageIO parsers, rejects inputs over 2 MiB, inspects dimensions before decoding (4096 pixels per side, 8 megapixels), and re-encodes decoded pixels into a fresh PNG. A maximum 5 MiB normalized output is enforced while encoding. Navigation/HTML/SVG/GIF/creative JavaScript formats are not accepted. Original metadata and trailing payloads are not served. Two decoder slots and two whole-upload slots bound decoded/output memory while storage is slow. The S3 client has a bounded four-connection pool and explicit operation deadlines/retries. This is structural normalization, **not a malware-scanner or content-policy certification**. Visual/deceptive-content/landing-page review remains necessary.

Normalized bytes are SHA-256-addressed in private S3-compatible storage. PostgreSQL owns asset metadata and tenant references; advertiser filenames are never object keys. Browser/client credentials never expose storage keys. GET `/creatives/assets/{id}` permits the owning advertiser; GET `/admin/creative-assets/{id}` permits review. Public `/ads/assets/{id}` serves normalized PNG only when an approved creative references that asset. Public responses use no-store, nosniff and cross-origin image policy. Reads verify exact byte size and SHA-256. Missing, tampered or unavailable objects fail closed with 503; an iframe does not qualify an image impression until image load succeeds.

Object storage work happens before the short metadata/review transaction. A storage failure does not approve a creative. Content-addressing makes identical byte writes safe, but a crash between object PUT and SQL metadata insertion can leave an unreferenced object. Orphan reconciliation/retention, tenant storage quotas, malware scanning and CDN edge delivery remain future work. Public image retrieval is approval-based, not a promise to remove images already downloaded elsewhere.

The Next.js proxy preserves multipart boundaries and binary responses, limits buffered request bodies to 3 MiB, and adds nosniff. Spring also enforces 2 MiB files / 3 MiB multipart requests. The proxy refuses render-document requests: they must use the direct isolated origin, preserving CSP and origin boundaries.

## Publisher integration

The browser never receives publisher OIDC credentials. The publisher's server holds a token with PUBLISHER role for the subject owning the verified website, context and placement, and calls the normal decision API:

```sh
curl -X POST "$ADS_API/api/v1/ads/decide"   -H "Authorization: Bearer $PUBLISHER_ACCESS_TOKEN"   -H 'Content-Type: application/json'   -d '{"pageId":"APPROVED_PAGE_UUID","placementId":"OWNED_PLACEMENT_UUID","synthetic":true}'
```

`synthetic:true` is for local tests/demos and makes traffic ineligible for charging. Its absence is never proof of human traffic. Real service accounts must own or be explicitly authorized for those resources; multi-user organization membership remains incomplete.

A filled response contains `renderUrl` in addition to the audited decision identity and existing event tokens. Pass only that short-lived rendering capability to publisher HTML through normal framework escaping:

```html
<div data-contextual-ad data-render-url="SERVER_ESCAPED_RENDER_URL"></div>
<script src="https://ads.example.net/sdk/v1.js" defer referrerpolicy="no-referrer"></script>
```

The SDK also provides `window.ContextualAds.mount(element,{renderUrl})`. It accepts URLs only on its own configured script origin and the exact rendering endpoint shape, rejects query/hash injection and arbitrary origins, and initializes at most 50 declarative slots. Repeated mounting disposes old listeners/timeouts. It displays Sponsored/loading state and a bounded unavailable fallback; failed frames are removed after eight seconds. It reads no browsing history, user storage, fingerprint or unrelated page content. Server-side publisher integration must obtain fresh decisions for visits; do not permanently embed a five-minute URL in a static page.

The publisher's CSP must permit the SDK origin in script-src and the rendering origin in frame-src. Production should use a dedicated cookie-free HTTPS rendering hostname, managed signing/storage secrets and explicit `ads.render-origin` / `ads.console-origin`. Local development uses backend 8086 and console 3006; their distinct ports are origins, while local cookies still share a hostname. No production TLS/cookie-isolation certification is claimed. The endpoint is a direct publisher integration, not an OpenRTB exchange.

## Browser security and measurement

The frame uses sandbox `allow-scripts allow-popups allow-popups-to-escape-sandbox`, deliberately excluding allow-same-origin, forms and top navigation. The response itself repeats these restrictions in CSP sandbox, even when visited directly. CSP defaults to none, nonce-authorizes only the platform's script/style, restricts image/connect destinations, forbids base/forms/objects, and permits ancestors only at the verified HTTPS publisher domain and configured console preview origin. Rendering has no conflicting X-Frame-Options; other backend endpoints remain DENY. All creative strings are HTML-escaped; no advertiser HTML is interpolated into scripts. UTF-8 is explicit.

Approved destinations are accessed through the signed controlled click endpoint and open with sponsored/noopener/noreferrer. Signed render/impression/click tokens are separate purposes bound to the durable decision; mismatched token kinds fail. Revoked/expired frame capabilities return Problem Details with 410, not an authentication redirect. No bearer credentials or storage credentials appear in publisher markup or frame messages.

The platform script waits for at least 50% intersection for one continuous second while the document is visible, and for image decode/load when present. Hiding or moving the frame resets dwell time. Event fetches omit credentials, retry at most three times and remain server-deduplicated. Only the public signed impression endpoint permits wildcard, non-credentialed POST CORS (including opaque sandbox origins); management CORS remains blocked. Ready messages contain only `{type:'contextads.ready',version:1}`; the SDK validates frame source/origin and does not treat ready messages as billable evidence. Client viewability remains an unverified signal. Existing fraud/manual billing gates, hard-budget accounting and synthetic-traffic exclusions are unchanged.

## Reproducible verification

MinIO's published image was unavailable, so Compose builds `contextual-ads-minio:local` from pinned upstream source using `docker/assets.Dockerfile`; the upstream AGPL license is included in the runtime image. Build this image before Testcontainers:

```sh
docker compose build assets
mvn -f backend/pom.xml verify
docker compose up --build -d
cd frontend
npm ci
npm run build
npm run typecheck
npx playwright install chromium
npx playwright test tests/workflow.spec.ts --workers=1
cd ..
python3 tests/smoke.py
python3 tests/isolated_renderer.py
```

The isolated runner starts disposable PostgreSQL, S3 and backend containers plus a synthetic publisher HTTP origin. It uses actual local Keycloak-issued roles, seeds only a reserved example.com verification fixture into the disposable database, and exercises the actual publisher content/placement checks, decision API, object store, HTML renderer, SDK and signed measurement. It never adds a DNS bypass to the running application or claims a real publisher domain. Test tokens stay in a temporary restricted file; all fixture containers/storage are removed. Browser advertiser navigation is intercepted at the reserved example.org test destination, so no real advertising traffic or money transfer is generated. The main portal suite separately verifies genuine multipart upload, limits and administrator private review.

References: iframe/sandbox behavior follows the [HTML standard](https://html.spec.whatwg.org/multipage/iframe-embed-object.html) and [CSP sandbox documentation](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/sandbox). S3 uses the [AWS Java SDK](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html); local storage source is [MinIO upstream](https://github.com/minio/minio). Production storage maintenance/licensing, malware scanning, policy appeals, stronger advertiser identity disclosure, retention and infrastructure hardening remain open.

The local S3 client currently signs for us-east-1. Automated landing-page ownership/content verification is not implemented; the destination constraints and explicit version review do not establish that every external destination is safe. Asset and publisher API limits are structural bounds, not a substitute for production rate limits, quotas or fraud analysis.
