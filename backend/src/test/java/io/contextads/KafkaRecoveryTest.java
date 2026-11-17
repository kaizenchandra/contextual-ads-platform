package io.contextads;

import java.util.*;
import java.time.*;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(properties = {"ads.events.initial-delay-ms=3600000", "ads.events.poll-ms=3600000"})
@Testcontainers
class KafkaRecoveryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");
    @Autowired
    JdbcTemplate db;
    @Autowired
    OutboxRelay relay;
    @Autowired
    EventProcessor processor;
    @Autowired
    Billing billing;
    @Autowired Treasury treasury;
    @Autowired
    KafkaTemplate<String, String> template;
    @Autowired
    KafkaListenerEndpointRegistry registry;
    @Autowired
    ObjectMapper json;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for recovery");
    }

    long count(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    String body(UUID event) throws Exception {
        var row = db.queryForMap("select * from outbox where event_id=?", event);
        return json.writeValueAsString(new IntegrationEvent(event, ((Number) row.get("id")).longValue(), 1, (String) row.get("event_type"), (UUID) row.get("aggregate_id"), ((java.sql.Timestamp) row.get("created")).toInstant()));
    }

    @Test
    void brokerOutageCrashReplayConsumerRestartAndPoisonRecovery() throws Exception {
        UUID campaign = UUID.randomUUID(), page = UUID.randomUUID(), decision = UUID.randomUUID();
        db.update("insert into campaigns(id,owner_name,name,category,headline,destination,budget,bid,daily_budget,spent) values(?,'advertiser','kafka','technology.java','Java','https://example.org',100,5,25,5)", campaign);
        db.update("insert into pages(id,owner_name,title,category,confidence,keywords,safe) values(?,'publisher','Java','technology.java',1,'java',true)", page);
        db.update("insert into campaign_daily_budgets(campaign_id,budget_day,budget_limit,spent) values(?,(now() at time zone 'UTC')::date,25,5)", campaign);
        db.update("insert into decisions(id,campaign_id,page_id,price,expires,budget_day,state) values(?,?,?,5,now()+interval '5 minutes',(now() at time zone 'UTC')::date,'COMMITTED')", decision, campaign, page);
        db.update("insert into movements(decision_id,kind,amount) values(?,'COMMIT',5)", decision);
        UUID event = db.queryForObject("insert into outbox(event_type,aggregate_id) values('SpendCommitted',?) returning event_id", UUID.class, decision);
        // Real paused broker, not a mocked producer. Failure must leave a durable retryable outbox.
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertFalse(relay.publishOne());
            assertEquals("READY", db.queryForObject("select state from outbox where event_id=?", String.class, event));
            assertEquals(0, count("select count(*) from billing_entries"));
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }
        db.update("update outbox set available_at=now() where event_id=?", event);
        assertTrue(relay.publishOne());
        await(() -> count("select count(*) from processed_events where event_id=?", event) == 1);
        assertEquals("PENDING", db.queryForObject("select status from fraud_cases where decision_id=?", String.class, decision));
        assertEquals(0, count("select count(*) from billing_entries"));
        // Simulate broker ACK followed by publisher process death before the SENT database write.
        db.update("update outbox set state='PUBLISHING',lease_token=?,lease_until=now()-interval '1 second' where event_id=?", UUID.randomUUID(), event);
        assertTrue(relay.publishOne());
        template.send(KafkaEvents.TOPIC, decision.toString(), body(event)).get();
        await(() -> count("select count(*) from processed_events where event_id=?", event) == 1);
        assertEquals(1, count("select count(*) from fraud_cases"));
        billing.review(decision, "VALIDATE", "Independent test evidence reviewed by administrator", "admin-subject");
        assertEquals(5, count("select sum(amount) from billing_entries"));
        // Consumer restart and replay keep the inbox and financial effects intact.
        var listener = registry.getListenerContainer("fraud-review");
        listener.stop();
        processor.reset(event, "admin-subject", "Replay after controlled consumer restart");
        while (relay.publishOne()) {
        }
        listener.start();
        template.send(KafkaEvents.TOPIC, decision.toString(), body(event)).get();
        // Unknown schema goes to durable SQL DLQ, not to the financial ledger.
        String poison = body(event).replace("\"schemaVersion\":1", "\"schemaVersion\":999");
        template.send(KafkaEvents.TOPIC, decision.toString(), poison).get();
        await(() -> count("select count(*) from consumer_failures where state='OPEN'") >= 1);
        assertEquals(5, count("select sum(amount) from billing_entries"));
        long failed = db.queryForObject("select id from consumer_failures where body like '%999%' order by id desc limit 1", Long.class);
        processor.replay(failed, "admin-subject", "Replay canonical version after schema compatibility review");
        while (relay.publishOne()) {
        }
        assertEquals("REPLAYED", db.queryForObject("select state from consumer_failures where id=?", String.class, failed));
        template.send(KafkaEvents.TOPIC, "poison", "malformed-json").get();
        await(() -> count("select count(*) from consumer_failures where body='malformed-json'") == 1);
        long malformed = db.queryForObject("select id from consumer_failures where body='malformed-json'", Long.class);
        assertThrows(IllegalArgumentException.class, () -> processor.replay(malformed, "admin-subject", "Cannot fabricate missing durable event reference"));
        // A later valid record still processes after poison recovery.
        UUID next = db.queryForObject("insert into outbox(event_type,aggregate_id) values('ClickRecorded',?) returning event_id", UUID.class, decision);
        while (relay.publishOne()) {
        }
        await(() -> count("select count(*) from processed_events where event_id=?", next) == 1);
        assertEquals(1, count("select count(*) from billing_entries where kind='CHARGE'"));
        billing.review(decision, "REVERSE", "Confirmed test dispute after replay and restart", "admin-subject");
        while (relay.publishOne()) {
        }
        assertEquals(0, count("select sum(amount) from billing_entries"));
        assertEquals(5, count("select spent from campaigns where id=?", campaign));
        // Inject an actual PostgreSQL business-write failure AFTER inbox insertion. Both effects must roll back.
        UUID second = UUID.randomUUID();
        db.update("update campaigns set spent=spent+5 where id=?", campaign);
        db.update("update campaign_daily_budgets set spent=spent+5 where campaign_id=?", campaign);
        db.update("insert into decisions(id,campaign_id,page_id,price,expires,budget_day,state) values(?,?,?,5,now()+interval '5 minutes',(now() at time zone 'UTC')::date,'COMMITTED')", second, campaign, page);
        db.update("insert into movements(decision_id,kind,amount) values(?,'COMMIT',5)", second);
        db.execute("create function fail_fraud_write() returns trigger language plpgsql as $$ begin if NEW.decision_id='" + second + "'::uuid then raise exception 'injected business failure'; end if; return NEW; end $$");
        db.execute("create trigger fail_fraud_write before insert or update on fraud_cases for each row execute function fail_fraud_write()");
        UUID failedEvent = db.queryForObject("insert into outbox(event_type,aggregate_id) values('SpendCommitted',?) returning event_id", UUID.class, second);
        while (relay.publishOne()) {
        }
        await(() -> count("select count(*) from consumer_failures where body like ?", "%" + failedEvent + "%") >= 1);
        assertEquals(0, count("select count(*) from processed_events where event_id=?", failedEvent));
        assertEquals(0, count("select count(*) from fraud_cases where decision_id=?", second));
        db.execute("drop trigger fail_fraud_write on fraud_cases");
        db.execute("drop function fail_fraud_write()");
        long businessFailure = db.queryForObject("select id from consumer_failures where body like ? order by id desc limit 1", Long.class, "%" + failedEvent + "%");
        processor.replay(businessFailure, "admin-subject", "Database fault repaired; retry original immutable event");
        while (relay.publishOne()) {
        }
        await(() -> count("select count(*) from processed_events where event_id=?", failedEvent) == 1);
        assertEquals("PENDING", db.queryForObject("select status from fraud_cases where decision_id=?", String.class, second));
        assertEquals(0, count("select count(*) from billing_entries where decision_id=?", second));
        processor.discard(malformed, "admin-subject", "Malformed record investigated; no durable source exists");
        assertEquals("DISCARDED", db.queryForObject("select state from consumer_failures where id=?", String.class, malformed));
        // Reconciliation recovers a SENT event missing its durable inbox effect.
        UUID lost = db.queryForObject("insert into outbox(event_type,aggregate_id,state,published_at) values('ClickRecorded',?,'SENT',now()-interval '10 minutes') returning event_id", UUID.class, second);
        relay.recoverMissingInbox();
        assertEquals("READY", db.queryForObject("select state from outbox where event_id=?", String.class, lost));
        while (relay.publishOne()) {
        }
        await(() -> count("select count(*) from processed_events where event_id=?", lost) == 1);

        // Finance stream: durable issuance commits even when the broker is unavailable.
        billing.review(second,"VALIDATE","Independent finance integration evidence reviewed","admin-subject");
        while(relay.publishOne()){}
        var batch=treasury.issue("advertiser","kafka-finance-close","Finance evidence reviewed before invoice issuance","admin-subject");
        UUID invoice=db.queryForObject("select id from invoices where kind='INVOICE'",UUID.class);
        UUID financeEvent=db.queryForObject("select event_id from outbox where aggregate_type='INVOICE' and aggregate_id=?",UUID.class,invoice);
        db.execute("create function fail_treasury_projection() returns trigger language plpgsql as $$ begin if NEW.entity_id='"+invoice+"'::uuid then raise exception 'injected finance consumer failure'; end if; return NEW; end $$");
        db.execute("create trigger fail_treasury_projection before insert on treasury_notifications for each row execute function fail_treasury_projection()");
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try{assertFalse(relay.publishOne());assertEquals(2,count("select count(*) from invoices"));}
        finally{kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();}
        db.update("update outbox set available_at=now() where state='READY'");while(relay.publishOne()){}
        await(()->count("select count(*) from consumer_failures where topic=? and body like ?",KafkaEvents.FINANCE_TOPIC,"%"+financeEvent+"%")>=1);
        assertEquals(0,count("select count(*) from processed_events where consumer_name=? and event_id=?",TreasuryConsumer.NAME,financeEvent));
        db.execute("drop trigger fail_treasury_projection on treasury_notifications");db.execute("drop function fail_treasury_projection()");
        long financeFailure=db.queryForObject("select id from consumer_failures where topic=? and body like ? order by id desc limit 1",Long.class,KafkaEvents.FINANCE_TOPIC,"%"+financeEvent+"%");
        processor.replay(financeFailure,"admin-subject","Finance projection fault repaired; replay canonical record");while(relay.publishOne()){}
        await(()->count("select count(*) from treasury_notifications where event_id=?",financeEvent)==1);
        processor.reset(financeEvent,"admin-subject","Controlled duplicate financial notification replay");while(relay.publishOne()){}
        UUID receipt=(UUID)treasury.importCash("advertiser","integration-bank","receipt-1","RECEIPT",5,"USD",null,java.time.Instant.now(),"Observed bank receipt evidence verified","admin-subject").get("id");
        treasury.match(receipt,invoice,"Receipt matched to the original invoice","admin-subject");
        UUID settlement=(UUID)treasury.reserve("publisher","kafka-settlement",3,"Publisher funds and evidence verified","admin-subject").get("id");
        treasury.submit(settlement,"Manual bank instruction independently authorized","admin-subject");
        treasury.confirm(settlement,"PAID","integration-bank:payout-1",3,"USD","Bank payout confirmation evidence verified","admin-subject");
        while(relay.publishOne()){}
        await(()->count("select count(*) from outbox o where aggregate_type<>'DECISION' and not exists(select 1 from processed_events p where p.event_id=o.event_id and p.consumer_name=?)",TreasuryConsumer.NAME)==0);
        assertEquals(1,count("select count(*) from payout_evidence"));assertEquals(3,Treasury.number(treasury.publisherSummary("publisher").get("settled")));
        assertEquals(1,count("select count(*) from treasury_notifications where event_id=?",financeEvent));

    }
}
