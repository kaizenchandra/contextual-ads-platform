package io.contextads;

import java.util.*;
import java.security.Principal;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/v1")
class Api {
    final Platform p;
    final Publishers publishers;
    final org.springframework.jdbc.core.JdbcTemplate db;

    Api(Platform p, Publishers publishers, org.springframework.jdbc.core.JdbcTemplate db) {
        this.p = p;
        this.publishers = publishers;
        this.db = db;
    }

    void role(Authentication a, String role) {
        p.require(a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_" + role)));
    }

    String bounded(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max) throw new IllegalArgumentException("Invalid text length");
        return s;
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody Campaign c, Authentication a) {
        role(a, "ADVERTISER");
        long daily = c.dailyBudget() == null ? c.budget() : c.dailyBudget();
        String pacing = c.pacing() == null ? "ACCELERATED" : c.pacing();
        var start = c.startsAt() == null ? java.time.Instant.now() : c.startsAt();
        var end = c.endsAt() == null ? start.plusSeconds(30 * 86400L) : c.endsAt();
        if (c.category() == null || !Classifier.TAXONOMY.containsKey(c.category()) || c.bid() < 1 || c.budget() < c.bid() || c.budget() > 9_000_000_000_000L
                || daily < c.bid() || daily > c.budget() || !Set.of("EVEN", "ACCELERATED").contains(pacing) || !end.isAfter(start)
                || java.time.Duration.between(start, end).toMillis() < 1000)
            throw new IllegalArgumentException("Invalid targeting, budget, pacing or schedule");
        UUID id = UUID.randomUUID();
        db.update("insert into campaigns(id,owner_name,name,category,headline,destination,budget,bid,daily_budget,pacing,starts_at,ends_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, a.getName(), bounded(c.name(), 100), c.category(), bounded(c.headline(), 160), Platform.destination(c.destination()), c.budget(), c.bid(), daily, pacing, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
        return Map.of("id", id);
    }

    @GetMapping("/campaigns")
    List<Map<String, Object>> list(Authentication a) {
        role(a, "ADVERTISER");
        return db.queryForList("select * from campaigns where owner_name=? order by id limit 100", a.getName());
    }

    @PostMapping("/campaigns/{id}/{action:activate|pause}")
    Map<String, Object> state(@PathVariable UUID id, @PathVariable String action, Authentication a) {
        role(a, "ADVERTISER");
        p.campaign(id, a.getName());
        db.update("update campaigns set status=? where id=? and owner_name=?", action.equals("activate") ? "ACTIVE" : "PAUSED", id, a.getName());
        return Map.of("updated", true);
    }

    @GetMapping("/admin/campaigns")
    List<Map<String, Object>> review() {
        return db.queryForList("select * from campaigns order by id limit 100");
    }

    @PostMapping({"/context/pages", "/context/analyze"})
    Map<String, Object> page(@RequestBody Page page, Principal user) {
        var c = new Classifier().analyze(page.html());
        String url = publishers.pageUrl(page.websiteId(), user.getName(), page.url());
        UUID id = UUID.randomUUID();
        db.update("insert into pages(id,owner_name,title,category,confidence,keywords,safe,website_id,page_url) values(?,?,?,?,?,?,?,?,?)", id, user.getName(), bounded(page.title(), 200), c.category(), c.confidence(), String.join(",", c.keywords()), c.safe(), page.websiteId(), url);
        return Map.of("id", id, "context", c);
    }

    @PostMapping("/ads/decide")
    Map<String, Object> decide(@RequestBody Decision d, Principal u) {
        return p.decide(d.pageId(), d.placementId(), u.getName(), d.synthetic());
    }

    @PostMapping("/ads/impressions")
    Map<String, Object> impression(@RequestBody Event e) {
        p.event(e.token(), "impression", e.synthetic());
        return Map.of("accepted", true, "verification", "client-signal-only");
    }

    @GetMapping("/ads/click/{token}")
    ResponseEntity<Void> click(@PathVariable String token) {
        return ResponseEntity.status(303).header("Location", p.event(token, "click")).header("Referrer-Policy", "no-referrer").header("Cache-Control", "no-store").build();
    }

    @PostMapping("/conversions")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> conversion(@RequestBody Conversion c, Authentication a) {
        role(a, "ADVERTISER");
        p.require(db.queryForObject("select count(*) from decisions d join campaigns c on c.id=d.campaign_id join events e on e.decision_id=d.id and e.kind='click' where d.id=? and c.owner_name=? and e.created>now()-interval '7 days'", Integer.class, c.decisionId(), a.getName()) > 0);
        int fresh = db.update("insert into conversions(owner_name,external_id,decision_id) values(?,?,?) on conflict do nothing", a.getName(), bounded(c.externalId(), 100), c.decisionId());
        if (fresh == 0 && !db.queryForObject("select decision_id from conversions where owner_name=? and external_id=?", UUID.class, a.getName(), c.externalId()).equals(c.decisionId()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Conversion identifier already has a different attribution reference");
        if (fresh == 1)
            db.update("insert into outbox(event_type,aggregate_id) values('ConversionRecorded',?)", c.decisionId());
        return Map.of("accepted", true);
    }

    @GetMapping("/analytics")
    List<Map<String, Object>> analytics(Authentication a) {
        role(a, "ADVERTISER");
        return db.queryForList("select c.id,c.name,c.budget,c.daily_budget,c.pacing,c.starts_at,c.ends_at,c.reserved,c.spent,(select count(*) from decisions d where d.campaign_id=c.id) decisions,(select count(*) from events e join decisions d on d.id=e.decision_id where d.campaign_id=c.id and e.kind='impression') impressions,(select count(*) from events e join decisions d on d.id=e.decision_id where d.campaign_id=c.id and e.kind='click') clicks from campaigns c where owner_name=?", a.getName());
    }

    @GetMapping("/admin/budget-reconciliation")
    List<Map<String, Object>> reconcile() {
        return db.queryForList("select c.id,c.reserved,c.spent,coalesce((select sum(price) from decisions d where d.campaign_id=c.id and state='RESERVED'),0) expected_reserved,coalesce((select sum(amount) from movements m join decisions d on d.id=m.decision_id where d.campaign_id=c.id and m.kind='COMMIT'),0) expected_spent from campaigns c");
    }

    @GetMapping("/campaigns/{id}/daily-budgets")
    List<Map<String, Object>> daily(@PathVariable UUID id, Principal user) {
        p.campaign(id, user.getName());
        return db.queryForList("select * from campaign_daily_budgets where campaign_id=? order by budget_day desc limit 90", id);
    }

    @GetMapping("/admin/daily-budget-reconciliation")
    List<Map<String, Object>> dailyReconciliation() {
        return db.queryForList("select b.*,coalesce((select sum(price) from decisions d where d.campaign_id=b.campaign_id and d.budget_day=b.budget_day and d.state='RESERVED'),0) expected_reserved,coalesce((select sum(price) from decisions d where d.campaign_id=b.campaign_id and d.budget_day=b.budget_day and d.state='COMMITTED'),0) expected_spent from campaign_daily_budgets b order by b.budget_day desc,b.campaign_id limit 1000");
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> conflict() {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Record already exists"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    record Campaign(String name, String category, String headline, String destination, long budget, long bid,
                    Long dailyBudget, String pacing, java.time.Instant startsAt, java.time.Instant endsAt) {
    }

    record Page(String title, String html, UUID websiteId, String url) {
    }

    record Decision(UUID pageId, UUID placementId, boolean synthetic) {
    }

    record Event(String token, boolean synthetic) {
    }

    record Conversion(String externalId, UUID decisionId) {
    }
}
