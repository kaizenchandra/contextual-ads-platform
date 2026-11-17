package io.contextads;

import java.util.*;
import java.security.Principal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/v1")
class FinanceApi {
    private final JdbcTemplate db;
    private final Billing billing;
    private final EventProcessor processor;

    FinanceApi(JdbcTemplate db, Billing billing, EventProcessor processor) {
        this.db = db;
        this.billing = billing;
        this.processor = processor;
    }

    @GetMapping("/admin/fraud")
    List<Map<String, Object>> fraud() {
        return db.queryForList("select f.*,d.campaign_id,d.price,d.synthetic from fraud_cases f join decisions d on d.id=f.decision_id order by f.updated desc,f.decision_id limit 100");
    }

    @PostMapping("/admin/fraud/{id}/review")
    Map<String, Object> review(@PathVariable UUID id, @RequestBody Review review, Principal user) {
        return billing.review(id, review.action(), review.reason(), user.getName());
    }

    @GetMapping("/admin/financial-audit/{id}")
    List<Map<String, Object>> audit(@PathVariable UUID id) {
        return db.queryForList("select * from financial_audit where decision_id=? order by created,id limit 100", id);
    }

    @GetMapping("/admin/billing-reconciliation")
    List<Map<String, Object>> reconciliation() {
        return billing.reconciliation();
    }

    @GetMapping("/billing/entries")
    List<Map<String, Object>> entries(Principal user) {
        return db.queryForList("select id,decision_id,campaign_id,currency,kind,amount,created from billing_entries where advertiser=? order by created desc,id limit 100", user.getName());
    }

    @GetMapping("/billing/summary")
    Map<String, Object> summary(Principal user) {
        return db.queryForMap("select coalesce(sum(case when kind='CHARGE' then amount else 0 end),0) validated_charges,coalesce(-sum(case when kind='REVERSAL' then amount else 0 end),0) reversed_charges,coalesce(sum(amount),0) net_receivable, 'USD' as currency,0 as invoiced,0 as paid from billing_entries where advertiser=?", user.getName());
    }

    @GetMapping("/admin/events/status")
    Map<String, Object> delivery() {
        return Map.of("events", db.queryForList("select event_id,event_type,state,attempts,created,published_at,last_error from outbox order by id desc limit 100"), "outbox", db.queryForList("select state,count(*) records,min(created) oldest from outbox group by state"), "unprocessedSent", db.queryForObject("select count(*) from outbox o where state='SENT' and not exists(select 1 from processed_events p where p.event_id=o.event_id and p.consumer_name='fraud-review-v1')", Long.class), "inbox", db.queryForObject("select count(*) from processed_events", Long.class), "deadLetters", db.queryForList("select id,topic,partition_id,offset_id,error_code,state,created from consumer_failures order by id desc limit 100"));
    }

    @PostMapping("/admin/events/{event}/replay")
    Map<String, Object> replay(@PathVariable UUID event, @RequestBody Replay replay, Principal user) {
        processor.reset(event, user.getName(), replay.reason());
        return Map.of("queued", true);
    }

    @PostMapping("/admin/dead-letters/{id}/replay")
    Map<String, Object> replayFailure(@PathVariable long id, @RequestBody Replay replay, Principal user) {
        processor.replay(id, user.getName(), replay.reason());
        return Map.of("queued", true);
    }

    @PostMapping("/admin/dead-letters/{id}/discard")
    Map<String, Object> discard(@PathVariable long id, @RequestBody Replay replay, Principal user) {
        processor.discard(id, user.getName(), replay.reason());
        return Map.of("discarded", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    record Review(String action, String reason) {
    }

    record Replay(String reason) {
    }
}
