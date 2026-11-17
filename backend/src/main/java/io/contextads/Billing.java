package io.contextads;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
class Billing {
    private final JdbcTemplate db;
    private final Fraud fraud;

    Billing(JdbcTemplate db, Fraud fraud) {
        this.db = db;
        this.fraud = fraud;
    }

    @Transactional
    public Map<String, Object> review(UUID decision, String action, String reason, String actor) {
        if (reason == null || reason.strip().length() < 12 || reason.length() > 2000)
            throw new IllegalArgumentException("Review evidence/reason must contain 12–2000 characters");
        if (action == null || !Set.of("VALIDATE", "INVALIDATE", "REVERSE").contains(action))
            throw new IllegalArgumentException("Invalid review action");
        var rows = db.queryForList("select d.*,c.owner_name,c.currency from decisions d join campaigns c on c.id=d.campaign_id where d.id=? for update of d", decision);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found");
        var d = rows.getFirst();
        if (!d.get("state").equals("COMMITTED"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A committed impression is required");
        fraud.assess(decision);
        var current = db.queryForMap("select * from fraud_cases where decision_id=? for update", decision);
        String previous = (String) current.get("status");
        String target = action.equals("VALIDATE") ? "VALID" : action.equals("INVALIDATE") ? "INVALID" : "REVERSED";
        if (previous.equals(target)) return Map.of("status", target, "duplicate", true);
        if (Set.of("INVALID", "REVERSED").contains(previous))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Terminal review state");
        if (action.equals("VALIDATE")) {
            // All automatic rules hold traffic. A narrative alone cannot bypass a positive invalid-traffic rule.
            if (!fraud.rules(decision).isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Traffic flags prevent charging; invalidate or investigate");
            if (previous.equals("DISPUTED"))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Disputed charges require reversal");
            db.update("insert into billing_entries(id,decision_id,campaign_id,advertiser,currency,kind,amount) values(?,?,?,?,?,'CHARGE',?)", UUID.randomUUID(), decision, d.get("campaign_id"), d.get("owner_name"), d.get("currency"), d.get("price"));
        } else if (action.equals("REVERSE")) {
            var charges = db.queryForList("select * from billing_entries where decision_id=? and kind='CHARGE'", decision);
            if (charges.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No charge to reverse");
            db.update("insert into billing_entries(id,decision_id,campaign_id,advertiser,currency,kind,amount) values(?,?,?,?,?,'REVERSAL',?)", UUID.randomUUID(), decision, d.get("campaign_id"), d.get("owner_name"), d.get("currency"), -((Number) charges.getFirst().get("amount")).longValue());
        } else if (Set.of("VALID", "DISPUTED").contains(previous))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use REVERSE for an existing charge");
        db.update("update fraud_cases set status=?,updated=now() where decision_id=?", target, decision);
        db.update("insert into financial_audit(id,decision_id,actor,action,reason,old_status,new_status) values(?,?,?,?,?,?,?)", UUID.randomUUID(), decision, actor, action, reason, previous, target);
        db.update("insert into outbox(event_type,aggregate_id) values(?,?)", action.equals("VALIDATE") ? "ChargeValidated" : action.equals("REVERSE") ? "ChargeReversed" : "TrafficInvalidated", decision);
        return Map.of("status", target, "duplicate", false);
    }

    // Financial and budget ledgers deliberately have distinct meanings. Reversals do not reopen budget rights.
    List<Map<String, Object>> reconciliation() {
        return db.queryForList("""
                select d.id,d.campaign_id,d.price,d.state,coalesce(f.status,'UNASSESSED') review_status,
                coalesce((select sum(b.amount) from billing_entries b where b.decision_id=d.id),0) net_charge,
                (select count(*) from billing_entries b where b.decision_id=d.id and b.kind='CHARGE') charges,
                (select count(*) from billing_entries b where b.decision_id=d.id and b.kind='REVERSAL') reversals,
                case when coalesce(f.status,'') in ('VALID','DISPUTED') then d.price else 0 end expected_net
                from decisions d left join fraud_cases f on f.decision_id=d.id where d.state='COMMITTED' order by d.created desc,d.id limit 1000
                """);
    }
}
