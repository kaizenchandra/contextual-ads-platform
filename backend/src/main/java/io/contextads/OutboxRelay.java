package io.contextads;

import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "ads.events.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    OutboxRelay(JdbcTemplate db, PlatformTransactionManager manager, KafkaTemplate<String, String> kafka, ObjectMapper json, MeterRegistry metrics) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.kafka = kafka;
        this.json = json;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${ads.events.poll-ms:1000}", initialDelayString = "${ads.events.initial-delay-ms:5000}")
    public void poll() {
        for (int i = 0; i < 10; i++) if (!publishOne()) break;
    }

    @Scheduled(fixedDelayString = "${ads.events.reconcile-ms:60000}", initialDelayString = "${ads.events.initial-delay-ms:5000}")
    public void recoverMissingInbox() {
        // Covers broker data loss/retention or consumer-offset loss without granting new event identities.
        db.update("""
                update outbox set state='READY',available_at=clock_timestamp(),published_at=null
                where id in (select o.id from outbox o where o.state='SENT'
                and o.published_at<clock_timestamp()-interval '5 minutes'
                and not exists(select 1 from processed_events p where p.event_id=o.event_id and p.consumer_name=case when o.aggregate_type='DECISION' then 'fraud-review-v1' else 'treasury-observer-v1' end)
                order by o.published_at limit 20 for update skip locked)
                """);
    }

    public boolean publishOne() {
        UUID lease = UUID.randomUUID();
        Map<String, Object> row = tx.execute(status -> {
            var rows = db.queryForList("""
                    select * from outbox where (state='READY' and available_at<=clock_timestamp())
                       or (state='PUBLISHING' and lease_until<clock_timestamp()) order by id limit 1 for update skip locked
                    """);
            if (rows.isEmpty()) return null;
            var candidate = rows.getFirst();
            db.update("update outbox set state='PUBLISHING',lease_token=?,lease_until=clock_timestamp()+interval '15 seconds',attempts=attempts+1 where id=?", lease, candidate.get("id"));
            return candidate;
        });
        if (row == null) return false;
        try {
            UUID aggregate=(UUID)row.get("aggregate_id");
            Object envelope;
            String topic;
            if(row.get("aggregate_type").equals("DECISION")){
                envelope=new IntegrationEvent((UUID)row.get("event_id"),((Number)row.get("id")).longValue(),((Number)row.get("schema_version")).intValue(),(String)row.get("event_type"),aggregate,((java.sql.Timestamp)row.get("created")).toInstant());topic=KafkaEvents.TOPIC;
            }else{
                envelope=new FinanceEvent((UUID)row.get("event_id"),((Number)row.get("id")).longValue(),((Number)row.get("schema_version")).intValue(),(String)row.get("event_type"),(String)row.get("aggregate_type"),aggregate,((java.sql.Timestamp)row.get("created")).toInstant());topic=KafkaEvents.FINANCE_TOPIC;
            }
            kafka.send(topic,aggregate.toString(),json.writeValueAsString(envelope)).get(5,TimeUnit.SECONDS);
            // Crash after broker ACK but before this update causes safe duplicate publication.
            db.update("update outbox set state='SENT',published_at=clock_timestamp(),lease_token=null,lease_until=null,last_error=null where id=? and lease_token=?", row.get("id"), lease);
            metrics.counter("ads.outbox.published").increment();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            db.update("update outbox set state=case when attempts>=10 then 'DEAD' else 'READY' end,available_at=clock_timestamp()+interval '5 seconds',last_error='PUBLISH_FAILED',lease_token=null,lease_until=null where id=? and lease_token=?", row.get("id"), lease);
            metrics.counter("ads.outbox.failures").increment();
            return false;
        }
        return true;
    }
}
