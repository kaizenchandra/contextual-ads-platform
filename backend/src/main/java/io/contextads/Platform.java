package io.contextads;

import java.util.*;
import java.net.URI;
import java.time.*;
import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
class Platform {
    private final JdbcTemplate db;
    private final Tokens tokens;
    private final Fraud fraud;
    private final Rendering rendering;
    private final TransactionTemplate tx;

    Platform(JdbcTemplate db, Tokens tokens, Fraud fraud, Rendering rendering, PlatformTransactionManager manager) {
        this.db = db;
        this.tokens = tokens;
        this.fraud = fraud;
        this.rendering=rendering;
        this.tx = new TransactionTemplate(manager);
    }

    static String destination(String input) {
        var u = URI.create(input);
        if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || u.getRawQuery() != null)
            throw new IllegalArgumentException("HTTPS destination without credentials, query or fragment required");
        return u.toASCIIString();
    }

    void require(boolean ok) {
        if (!ok) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    Map<String, Object> campaign(UUID id, String user) {
        return db.queryForList("select * from campaigns where id=? and owner_name=?", id, user).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    @Transactional
    public Map<String, Object> decide(UUID page, UUID placement, String user) {
        return decide(page, placement, user, false);
    }

    @Transactional
    public Map<String, Object> decide(UUID page, UUID placement, String user, boolean synthetic) {
        var contexts = db.queryForList("""
                select p.* from pages p
                join publisher_websites w on w.id=p.website_id
                join domain_ownership o on o.website_id=w.id
                join publisher_placements s on s.website_id=w.id
                where p.id=? and s.id=? and p.owner_name=? and w.owner_name=?
                  and w.verified_until>clock_timestamp() and s.status='ACTIVE'
                """, page, placement, user, user);
        require(contexts.size() == 1);
        var context = contexts.getFirst();
        if (!Boolean.TRUE.equals(context.get("safe")) || context.get("category").equals("unknown"))
            return Map.of("filled", false);
        var candidates = db.queryForList("""
                select id from campaigns where category=? and status='ACTIVE' and approved=true
                and starts_at<=clock_timestamp() and ends_at>clock_timestamp()
                order by bid desc,id limit 100
                """, context.get("category"));
        for (var candidate : candidates) {
            UUID id = (UUID) candidate.get("id");
            var c = db.queryForMap("select * from campaigns where id=? for update", id);
            // Database wall time, sampled AFTER lock acquisition, defines the admission day/window.
            Instant now = db.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
            LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
            var creativeRows=db.queryForList("select * from creatives where id=? and campaign_id=? and status='APPROVED' for share",c.get("current_creative_id"),id);
            if(creativeRows.isEmpty())continue;
            var creative=creativeRows.getFirst();
            long bid = ((Number) c.get("bid")).longValue();
            long daily = ((Number) c.get("daily_budget")).longValue();
            if (!c.get("status").equals("ACTIVE") || !Boolean.TRUE.equals(c.get("approved"))) continue;
            Instant start = ((Timestamp) c.get("starts_at")).toInstant();
            Instant end = ((Timestamp) c.get("ends_at")).toInstant();
            if (now.isBefore(start) || !now.isBefore(end)) continue;
            db.update("insert into campaign_daily_budgets(campaign_id,budget_day,budget_limit) values(?,?,?) on conflict do nothing", id, day, daily);
            var bucket = db.queryForMap("select * from campaign_daily_budgets where campaign_id=? and budget_day=? for update", id, day);
            long used = ((Number) bucket.get("spent")).longValue() + ((Number) bucket.get("reserved")).longValue();
            long allowance = Pacing.allowance((String) c.get("pacing"), ((Number) bucket.get("budget_limit")).longValue(), now, start, end);
            if (bid > allowance - used) continue;
            int admitted = db.update("update campaigns set reserved=reserved+? where id=? and ?<=budget-spent-reserved", bid, id, bid);
            if (admitted == 0) continue;
            db.update("update campaign_daily_budgets set reserved=reserved+? where campaign_id=? and budget_day=?", bid, id, day);
            UUID decision = UUID.randomUUID();
            db.update("insert into decisions(id,campaign_id,page_id,placement_id,price,expires,budget_day,created,synthetic,publisher_subject,creative_id,creative_headline,creative_description,creative_destination) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    decision, id, page, placement, bid, Timestamp.from(now.plusSeconds(300)), day, Timestamp.from(now), synthetic,context.get("owner_name"),creative.get("id"),creative.get("headline"),creative.get("description"),creative.get("destination"));
            movement(decision, "RESERVE", bid);
            db.update("insert into outbox(event_type,aggregate_id) values('AdDecisionMade',?)", decision);
            return Map.of("filled", true, "id", decision, "headline", creative.get("headline"), "advertiser", c.get("owner_name"),
                    "renderUrl",rendering.url(decision),"creativeId",creative.get("id"),"format",creative.get("format"),"priceMinor", bid, "impressionToken", tokens.sign(decision, "impression"), "clickToken", tokens.sign(decision, "click"));
        }
        return Map.of("filled", false);
    }

    @Transactional
    public String event(String token, String kind) {
        return event(token, kind, false);
    }

    @Transactional
    public String event(String token, String kind, boolean synthetic) {
        UUID id = tokens.verify(token, kind);
        var rows = db.queryForList("select * from decisions where id=? for update", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown decision");
        var d = rows.getFirst();
        if (!Boolean.TRUE.equals(db.queryForObject("select expires>clock_timestamp() from decisions where id=?", Boolean.class, id)))
            throw new IllegalArgumentException("Expired decision");
        if (d.get("state").equals("RELEASED")) throw new IllegalArgumentException("Released decision");
        if(d.get("creative_id")!=null && db.queryForObject("select count(*) from creatives cr join campaigns c on c.id=cr.campaign_id where cr.id=? and cr.status='APPROVED' and c.current_creative_id=cr.id and c.approved=true",Integer.class,d.get("creative_id"))!=1)throw new IllegalArgumentException("Creative decision revoked");
        fraud.attempt(id, kind, synthetic || Boolean.TRUE.equals(d.get("synthetic")));
        int fresh = db.update("insert into events(decision_id,kind) values(?,?) on conflict do nothing", id, kind);
        if (fresh == 1 && kind.equals("impression") && d.get("state").equals("RESERVED")) {
            // All accounting effects share one transaction; attribution stays on admission day.
            db.update("update campaigns set reserved=reserved-?,spent=spent+? where id=?", d.get("price"), d.get("price"), d.get("campaign_id"));
            db.update("update campaign_daily_budgets set reserved=reserved-?,spent=spent+? where campaign_id=? and budget_day=?",
                    d.get("price"), d.get("price"), d.get("campaign_id"), d.get("budget_day"));
            db.update("update decisions set state='COMMITTED' where id=?", id);
            movement(id, "COMMIT", ((Number) d.get("price")).longValue());
            db.update("insert into outbox(event_type,aggregate_id) values('SpendCommitted',?)", id);
        }
        if (fresh == 1 && kind.equals("click"))
            db.update("insert into outbox(event_type,aggregate_id) values('ClickRecorded',?)", id);
        return d.get("creative_destination")!=null?(String)d.get("creative_destination"):db.queryForObject("select destination from campaigns where id=?", String.class, d.get("campaign_id"));
    }

    @Scheduled(fixedDelay = 30000)
    public void expire() {
        var ids = db.queryForList("select id from decisions where state='RESERVED' and expires<clock_timestamp() order by expires limit 200", UUID.class);
        // One transaction per decision prevents a batch holding unrelated campaign locks.
        for (UUID id : ids) tx.executeWithoutResult(status -> releaseExpired(id));
    }

    private void releaseExpired(UUID id) {
        var rows = db.queryForList("select * from decisions where id=? and state='RESERVED' and expires<clock_timestamp() for update skip locked", id);
        if (rows.isEmpty()) return;
        var d = rows.getFirst();
        db.update("update campaigns set reserved=reserved-? where id=?", d.get("price"), d.get("campaign_id"));
        db.update("update campaign_daily_budgets set reserved=reserved-? where campaign_id=? and budget_day=?", d.get("price"), d.get("campaign_id"), d.get("budget_day"));
        db.update("update decisions set state='RELEASED' where id=?", id);
        movement(id, "RELEASE", ((Number) d.get("price")).longValue());
    }

    private void movement(UUID id, String kind, long amount) {
        db.update("insert into movements(decision_id,kind,amount) values(?,?,?)", id, kind, amount);
    }
}
