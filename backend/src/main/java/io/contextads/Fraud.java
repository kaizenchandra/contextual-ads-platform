package io.contextads;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class Fraud {
    private final JdbcTemplate db;
    private final Treasury treasury;

    Fraud(JdbcTemplate db,Treasury treasury) {
        this.db = db;
        this.treasury=treasury;
    }

    // Called inside the decision-locked measurement transaction. No identifiers or IP profiles.
    void attempt(UUID decision, String kind, boolean synthetic) {
        long attempts = db.queryForObject("insert into event_counters(decision_id,kind) values(?,?) on conflict(decision_id,kind) do update set attempts=event_counters.attempts+1 returning attempts", Long.class, decision, kind);
        if (synthetic) signal(decision, "KNOWN_SYNTHETIC");
        if (kind.equals("click") && attempts >= 5) signal(decision, "REPEATED_CLICKS");
        if (kind.equals("impression") && attempts >= 10) signal(decision, "REPEATED_IMPRESSIONS");
    }

    private void publisherLock(UUID decision){
        var row=db.queryForMap("select coalesce(d.publisher_subject,p.owner_name) publisher from decisions d join pages p on p.id=d.page_id where d.id=?",decision);
        treasury.lock("PUBLISHER",(String)row.get("publisher"));
    }
    void signal(UUID decision, String rule) {
        publisherLock(decision);
        int fresh = db.update("insert into fraud_signals(decision_id,rule) values(?,?) on conflict do nothing", decision, rule);
        if (fresh == 1)
            db.update("insert into outbox(event_type,aggregate_id) values('FraudSignalTriggered',?)", decision);
    }

    List<String> rules(UUID decision) {
        var rules = new ArrayList<>(db.queryForList("select rule from fraud_signals where decision_id=? order by rule", String.class, decision));
        if (Boolean.TRUE.equals(db.queryForObject("select synthetic from decisions where id=?", Boolean.class, decision)) && !rules.contains("KNOWN_SYNTHETIC"))
            rules.add("KNOWN_SYNTHETIC");
        if (db.queryForObject("select count(*) from decisions d join campaigns c on c.id=d.campaign_id join pages p on p.id=d.page_id where d.id=? and c.owner_name=p.owner_name", Integer.class, decision) > 0)
            rules.add("PUBLISHER_SELF_TRAFFIC");
        return rules.stream().sorted().distinct().toList();
    }

    @org.springframework.transaction.annotation.Transactional
    public void assess(UUID decision) {
        db.queryForMap("select id from decisions where id=? for update",decision);
        publisherLock(decision);
        var old = db.queryForList("select status from fraud_cases where decision_id=?", String.class, decision);
        List<String> rules = rules(decision);
        db.update("insert into fraud_cases(decision_id,status,rules) values(?,?,?) on conflict(decision_id) do update set rules=excluded.rules,status=case when fraud_cases.status='VALID' and excluded.rules<>'' then 'DISPUTED' when fraud_cases.status='PENDING' and excluded.rules<>'' then 'REVIEW' else fraud_cases.status end,updated=now()", decision, rules.isEmpty() ? "PENDING" : "REVIEW", String.join(",", rules));
        String previous = old.isEmpty() ? "UNASSESSED" : old.getFirst();
        String next = db.queryForObject("select status from fraud_cases where decision_id=?", String.class, decision);
        if (!previous.equals(next))
            db.update("insert into financial_audit(id,decision_id,actor,action,reason,old_status,new_status) values(?,?,'system:fraud-v1','ASSESS',?,?,?)", UUID.randomUUID(), decision, "Rule evaluation v1: " + (rules.isEmpty() ? "no positive rules" : String.join(",", rules)), previous, next);
    }
}
