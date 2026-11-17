package io.contextads;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EventProcessor {
    static final String CONSUMER = "fraud-review-v1";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Fraud fraud;

    EventProcessor(JdbcTemplate db, ObjectMapper json, Fraud fraud) {
        this.db = db;
        this.json = json;
        this.fraud = fraud;
    }

    IntegrationEvent parse(String body) {
        if (body == null || body.length() > 8192) throw new IllegalArgumentException("Invalid event size");
        try {
            return json.readValue(body, IntegrationEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid event contract");
        }
    }

    @Transactional
    public void consume(String body) {
        var event = parse(body);
        if (event.schemaVersion() != 1 || event.eventId() == null || event.decisionId() == null)
            throw new IllegalArgumentException("Unsupported event contract");
        var rows = db.queryForList("select * from outbox where event_id=?", event.eventId());
        if (rows.isEmpty()) throw new IllegalArgumentException("Unrecognized durable event");
        var canonical = rows.getFirst();
        if (!canonical.get("aggregate_id").equals(event.decisionId()) || !canonical.get("event_type").equals(event.eventType())
                || ((Number) canonical.get("id")).longValue() != event.sequence() || ((Number) canonical.get("schema_version")).intValue() != event.schemaVersion()
                || !((java.sql.Timestamp) canonical.get("created")).toInstant().equals(event.occurredAt()))
            throw new IllegalArgumentException("Event differs from durable source");
        int fresh = db.update("insert into processed_events(consumer_name,event_id) values(?,?) on conflict do nothing", CONSUMER, event.eventId());
        if (fresh == 0) return;
        // Consumer processing uses current authoritative state, so reordering cannot undo later review.
        var decisions = db.queryForList("select * from decisions where id=? for update", event.decisionId());
        if (decisions.isEmpty()) throw new IllegalArgumentException("Unknown decision");
        if (Set.of("SpendCommitted", "FraudSignalTriggered", "ClickRecorded", "ConversionRecorded").contains(event.eventType()))
            fraud.assess(event.decisionId());
        else if (!Set.of("AdDecisionMade", "ChargeValidated", "ChargeReversed", "TrafficInvalidated").contains(event.eventType()))
            throw new IllegalArgumentException("Unknown event type");
    }

    @Transactional
    public void quarantine(String topic, int partition, long offset, String body) {
        db.update("insert into consumer_failures(topic,partition_id,offset_id,body,error_code) values(?,?,?,?,'CONSUMER_RETRY_EXHAUSTED') on conflict do nothing",
                topic, partition, offset, body == null ? "" : body.substring(0, Math.min(body.length(), 8192)));
    }

    @Transactional
    public void replay(long failure, String actor, String reason) {
        var failures = db.queryForList("select * from consumer_failures where id=? for update", failure);
        if (failures.isEmpty())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Dead letter not found");
        var row = failures.getFirst();
        var event = parse((String) row.get("body"));
        if (event.eventId() == null || db.queryForObject("select count(*) from outbox where event_id=?", Integer.class, event.eventId()) != 1)
            throw new IllegalArgumentException("Cannot replay an event without a durable source");
        reset(event.eventId(), actor, reason);
        db.update("update consumer_failures set state='REPLAYED' where id=?", failure);
    }

    @Transactional
    public void discard(long failure, String actor, String reason) {
        if (reason == null || reason.strip().length() < 12 || reason.length() > 2000)
            throw new IllegalArgumentException("Discard reason must contain 12–2000 characters");
        var rows = db.queryForList("select * from consumer_failures where id=? for update", failure);
        if (rows.isEmpty())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Dead letter not found");
        if (rows.getFirst().get("state").equals("DISCARDED")) return;
        db.update("update consumer_failures set state='DISCARDED' where id=?", failure);
        db.update("insert into delivery_audit(actor,action,details) values(?,'DISCARD',?)", actor, "dead-letter " + failure + ": " + reason);
    }

    @Transactional
    public void reset(UUID event, String actor, String reason) {
        if (reason == null || reason.strip().length() < 12 || reason.length() > 2000)
            throw new IllegalArgumentException("Replay reason must contain 12–2000 characters");
        // Stable event ID is retained; consumer inbox is deliberately never deleted.
        int count = db.update("update outbox set state='READY',available_at=now(),lease_token=null,lease_until=null,attempts=0,last_error=null where event_id=? and state<>'PUBLISHING'", event);
        if (count != 1)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Unknown event or active publisher lease");
        db.update("insert into delivery_audit(actor,action,event_id,details) values(?,'REPLAY',?,?)", actor, event, reason);
    }
}
