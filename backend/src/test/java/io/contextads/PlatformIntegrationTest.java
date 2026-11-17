package io.contextads;

import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import java.sql.Timestamp;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "ads.events.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class PlatformIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @Autowired
    JdbcTemplate db;
    @Autowired
    Platform platform;
    @Autowired
    Publishers publishers;
    @Autowired
    Tokens tokens;
    @Autowired
    MockMvc mvc;
    @Autowired
    Billing billing;
    @Autowired
    Fraud fraud;
    @Autowired
    EventProcessor processor;
    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoBean
    DnsTxtResolver dns;
    @MockitoBean
    JwtDecoder decoder;
    UUID website, placement, page, campaign;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setup() {
        db.execute("truncate conversions,events,movements,outbox,decisions,campaign_daily_budgets,pages,publisher_placements,domain_ownership,publisher_websites,campaigns cascade");
        var site = publishers.register("publisher.com", "publisher");
        website = (UUID) site.get("id");
        when(dns.lookup("_contextads-verification.publisher.com")).thenReturn(List.of((String) site.get("recordValue")));
        publishers.verify(website, "publisher");
        placement = UUID.randomUUID();
        page = UUID.randomUUID();
        campaign = UUID.randomUUID();
        db.update("insert into publisher_placements(id,website_id,name) values(?,?,'native')", placement, website);
        db.update("insert into pages(id,owner_name,title,category,confidence,keywords,safe,website_id,page_url) values(?,'publisher','Java','technology.java',1,'java',true,?,'https://publisher.com/java')", page, website);
        db.update("insert into campaigns(id,owner_name,name,category,headline,destination,budget,bid,daily_budget,approved,status) values(?,'advertiser','test','technology.java','Java','https://example.org',100,5,25,true,'ACTIVE')", campaign);
    }

    Map<String, Object> decide() {
        return platform.decide(page, placement, "publisher");
    }

    void assertAccounting() {
        var c = db.queryForMap("select * from campaigns where id=?", campaign);
        long reserved = db.queryForObject("select coalesce(sum(price),0) from decisions where campaign_id=? and state='RESERVED'", Long.class, campaign);
        long spent = db.queryForObject("select coalesce(sum(price),0) from decisions where campaign_id=? and state='COMMITTED'", Long.class, campaign);
        assertEquals(reserved, ((Number) c.get("reserved")).longValue());
        assertEquals(spent, ((Number) c.get("spent")).longValue());
        for (var b : db.queryForList("select * from campaign_daily_budgets where campaign_id=?", campaign)) {
            assertEquals(((Number) b.get("reserved")).longValue(), db.queryForObject("select coalesce(sum(price),0) from decisions where campaign_id=? and budget_day=? and state='RESERVED'", Long.class, campaign, b.get("budget_day")));
            assertEquals(((Number) b.get("spent")).longValue(), db.queryForObject("select coalesce(sum(price),0) from decisions where campaign_id=? and budget_day=? and state='COMMITTED'", Long.class, campaign, b.get("budget_day")));
            assertTrue(((Number) b.get("reserved")).longValue() + ((Number) b.get("spent")).longValue() <= ((Number) b.get("budget_limit")).longValue());
        }
    }

    @Test
    void concurrentDailyAdmissionAndDeduplication() throws Exception {
        try (var pool = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<Callable<Map<String, Object>>>();
            for (int i = 0; i < 40; i++) tasks.add(this::decide);
            var wins = new ArrayList<Map<String, Object>>();
            for (var f : pool.invokeAll(tasks)) {
                var d = f.get();
                if (Boolean.TRUE.equals(d.get("filled"))) wins.add(d);
            }
            assertEquals(5, wins.size());
            var events = new ArrayList<Callable<String>>();
            for (int i = 0; i < 20; i++)
                events.add(() -> platform.event((String) wins.getFirst().get("impressionToken"), "impression"));
            for (var f : pool.invokeAll(events)) f.get();
            assertEquals(5, db.queryForObject("select spent from campaigns where id=?", Long.class, campaign));
            assertEquals(1, db.queryForObject("select count(*) from events", Integer.class));
            assertAccounting();
        }
    }

    @Test
    void lifetimeBudgetStillBoundsMultipleDays() {
        db.update("update campaigns set budget=25,daily_budget=25,spent=20 where id=?", campaign);
        // Existing historical financial balance: seed an owned prior-day commitment with its immutable movement.
        UUID historic = UUID.randomUUID();
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        db.update("insert into campaign_daily_budgets(campaign_id,budget_day,budget_limit,spent) values(?,?,25,20)", campaign, yesterday);
        db.update("insert into decisions(id,campaign_id,page_id,placement_id,price,expires,budget_day,state) values(?,?,?,?,20,now(),?,'COMMITTED')", historic, campaign, page, placement, yesterday);
        db.update("insert into movements(decision_id,kind,amount) values(?,'COMMIT',20)", historic);
        assertEquals(true, decide().get("filled"));
        assertEquals(false, decide().get("filled"));
        assertAccounting();
    }

    @Test
    void expiredEventAndExpiryWorkerRaceCannotDoubleRelease() throws Exception {
        var d = decide();
        db.update("update decisions set expires=clock_timestamp()-interval '1 second' where id=?", d.get("id"));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++)
                futures.add(pool.submit(() -> {
                    platform.expire();
                    assertThrows(IllegalArgumentException.class, () -> platform.event((String) d.get("impressionToken"), "impression"));
                }));
            for (var f : futures) f.get();
        }
        assertEquals(1, db.queryForObject("select count(*) from movements where kind='RELEASE'", Integer.class));
        assertAccounting();
    }

    UUID committed() {
        var d = decide();
        platform.event((String) d.get("impressionToken"), "impression");
        return (UUID) d.get("id");
    }

    @Test
    void unreviewedSignalsNeverChargeAndValidatedChargesAreIdempotent() throws Exception {
        UUID d = committed();
        assertEquals(0, db.queryForObject("select count(*) from billing_entries", Integer.class));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Map<String, Object>>>();
            for (int i = 0; i < 16; i++)
                tasks.add(() -> billing.review(d, "VALIDATE", "Reviewed authorized measurement evidence", "admin-subject"));
            for (var future : pool.invokeAll(tasks)) assertEquals("VALID", future.get().get("status"));
        }
        assertEquals(1, db.queryForObject("select count(*) from billing_entries", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from financial_audit where action='VALIDATE'", Integer.class));
        assertEquals(5, db.queryForObject("select sum(amount) from billing_entries", Long.class));
        assertAccounting();
    }

    @Test
    void reversalsAreAppendOnlyIdempotentAndDoNotReopenBudget() {
        UUID d = committed();
        billing.review(d, "VALIDATE", "Evidence reviewed and independently confirmed", "admin-subject");
        billing.review(d, "REVERSE", "Advertiser dispute independently confirmed", "admin-subject");
        billing.review(d, "REVERSE", "Retry after an ambiguous response", "admin-subject");
        assertEquals(2, db.queryForObject("select count(*) from billing_entries", Integer.class));
        assertEquals(0, db.queryForObject("select sum(amount) from billing_entries", Long.class));
        assertEquals(5, db.queryForObject("select spent from campaigns where id=?", Long.class, campaign));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("update billing_entries set amount=1"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("delete from financial_audit"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> billing.review(d, "VALIDATE", "Cannot recharge a reversed impression", "admin-subject"));
        assertAccounting();
    }

    @Test
    void knownSyntheticAndRepeatedClicksAreHeldNotBilled() {
        var decision = platform.decide(page, placement, "publisher", true);
        platform.event((String) decision.get("impressionToken"), "impression");
        UUID d = (UUID) decision.get("id");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> billing.review(d, "VALIDATE", "Client says human but synthetic flag is present", "admin-subject"));
        billing.review(d, "INVALIDATE", "Known automated synthetic traffic confirmed", "admin-subject");
        assertEquals(0, db.queryForObject("select count(*) from billing_entries", Integer.class));
        var normal = decide();
        platform.event((String) normal.get("impressionToken"), "impression");
        for (int i = 0; i < 5; i++) platform.event((String) normal.get("clickToken"), "click");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> billing.review((UUID) normal.get("id"), "VALIDATE", "Repeated click signals require investigation", "admin-subject"));
        assertEquals(1, db.queryForObject("select count(*) from events where decision_id=? and kind='click'", Integer.class, normal.get("id")));
        assertAccounting();
    }

    @Test
    void newFlagsDisputeButNeverSilentlyReverseAnExistingCharge() {
        var decision = decide();
        platform.event((String) decision.get("impressionToken"), "impression");
        UUID d = (UUID) decision.get("id");
        billing.review(d, "VALIDATE", "Independent viewability evidence reviewed", "admin-subject");
        for (int i = 0; i < 5; i++) platform.event((String) decision.get("clickToken"), "click");
        fraud.assess(d);
        assertEquals("DISPUTED", db.queryForObject("select status from fraud_cases where decision_id=?", String.class, d));
        assertEquals(5, db.queryForObject("select sum(amount) from billing_entries", Long.class));
        billing.review(d, "REVERSE", "Repeated click abuse confirmed after investigation", "admin-subject");
        assertEquals(0, db.queryForObject("select sum(amount) from billing_entries", Long.class));
    }

    @Test
    void forgedKafkaEnvelopeCannotCreateInboxOrCharge() throws Exception {
        UUID d = committed();
        var out = db.queryForMap("select * from outbox where aggregate_id=? and event_type='SpendCommitted'", d);
        var good = new IntegrationEvent((UUID) out.get("event_id"), ((Number) out.get("id")).longValue(), 1, "SpendCommitted", d, ((java.sql.Timestamp) out.get("created")).toInstant());
        var forged = new IntegrationEvent(good.eventId(), good.sequence(), 1, "SpendCommitted", UUID.randomUUID(), good.occurredAt());
        assertThrows(IllegalArgumentException.class, () -> processor.consume(json.writeValueAsString(forged)));
        assertEquals(0, db.queryForObject("select count(*) from processed_events", Integer.class));
        processor.consume(json.writeValueAsString(good));
        processor.consume(json.writeValueAsString(good));
        assertEquals(1, db.queryForObject("select count(*) from processed_events", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from billing_entries", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> billing.review(d, null, "Sufficient evidence reason but missing action", "admin"));
    }

    @Test
    void conversionReplayIsIdempotentAndConflictingAttributionIsRejected() throws Exception {
        var first = decide();
        var second = decide();
        platform.event((String) first.get("clickToken"), "click");
        platform.event((String) second.get("clickToken"), "click");
        when(decoder.decode("conversion-advertiser")).thenReturn(jwt("advertiser", "ADVERTISER"));
        String body = "{\"externalId\":\"order-123\",\"decisionId\":\"" + first.get("id") + "\"}";
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/v1/conversions").header("Authorization", "Bearer conversion-advertiser").contentType("application/json").content(body)).andExpect(status().isOk());
        String conflicting = "{\"externalId\":\"order-123\",\"decisionId\":\"" + second.get("id") + "\"}";
        mvc.perform(post("/api/v1/conversions").header("Authorization", "Bearer conversion-advertiser").contentType("application/json").content(conflicting)).andExpect(status().isConflict());
        assertEquals(1, db.queryForObject("select count(*) from conversions", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from outbox where event_type='ConversionRecorded'", Integer.class));
    }

    @Test
    void financeEndpointsEnforceRolesAndLedgerOwnership() throws Exception {
        UUID d = committed();
        billing.review(d, "VALIDATE", "Independent evidence reviewed and approved", "admin");
        when(decoder.decode("publisher-finance")).thenReturn(jwt("publisher", "PUBLISHER"));
        when(decoder.decode("advertiser-finance")).thenReturn(jwt("advertiser", "ADVERTISER"));
        when(decoder.decode("other-finance")).thenReturn(jwt("other-advertiser", "ADVERTISER"));
        mvc.perform(get("/api/v1/billing/entries").header("Authorization", "Bearer publisher-finance")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/fraud/" + d + "/review").header("Authorization", "Bearer advertiser-finance").contentType("application/json").content("{\"action\":\"REVERSE\",\"reason\":\"Unauthorized reversal attempt\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/billing/entries").header("Authorization", "Bearer other-finance")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/billing/summary").header("Authorization", "Bearer advertiser-finance")).andExpect(status().isOk()).andExpect(jsonPath("$.net_receivable").value(5));
    }

    @Test
    void reviewFailureRollsBackAuditChargeAndOutbox() {
        UUID d = committed();
        assertThrows(IllegalArgumentException.class, () -> billing.review(d, "VALIDATE", "short", "admin"));
        assertEquals(0, db.queryForObject("select count(*) from billing_entries", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from financial_audit", Integer.class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> billing.review(d, "REVERSE", "No existing charge has been authorized", "admin"));
        assertEquals(0, db.queryForObject("select count(*) from fraud_cases", Integer.class));
    }

    @Test
    void financialReconciliationReportsValidatedAndReversedBalances() {
        UUID d = committed();
        billing.review(d, "VALIDATE", "Approved evidence from controlled verification", "admin");
        var row = billing.reconciliation().getFirst();
        assertEquals(((Number) row.get("net_charge")).longValue(), ((Number) row.get("expected_net")).longValue());
        billing.review(d, "REVERSE", "Confirmed advertiser dispute requires credit", "admin");
        row = billing.reconciliation().getFirst();
        assertEquals(0, ((Number) row.get("net_charge")).longValue());
        assertEquals(0, ((Number) row.get("expected_net")).longValue());
    }

    @Test
    void evenPacingCannotBorrowFutureAllowance() {
        db.update("update campaigns set pacing='EVEN',starts_at=clock_timestamp()-interval '1 second',ends_at=clock_timestamp()+interval '1 hour' where id=?", campaign);
        assertEquals(false, decide().get("filled"));
        assertAccounting();
    }

    @Test
    void expiryReleasesBothLedgersAndRejectsLateSignals() {
        var d = decide();
        db.update("update decisions set expires=clock_timestamp()-interval '1 second' where id=?", d.get("id"));
        platform.expire();
        platform.expire();
        assertThrows(IllegalArgumentException.class, () -> platform.event((String) d.get("impressionToken"), "impression"));
        assertEquals(0, db.queryForObject("select reserved from campaigns where id=?", Long.class, campaign));
        assertAccounting();
    }

    @Test
    void lateImpressionUsesAdmissionDay() {
        var d = decide();
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        db.update("insert into campaign_daily_budgets(campaign_id,budget_day,budget_limit,reserved) values(?,?,25,5)", campaign, yesterday);
        db.update("update decisions set budget_day=? where id=?", yesterday, d.get("id"));
        db.update("update campaign_daily_budgets set reserved=0 where campaign_id=? and budget_day<>?", campaign, yesterday);
        platform.event((String) d.get("impressionToken"), "impression");
        assertEquals(5, db.queryForObject("select spent from campaign_daily_budgets where campaign_id=? and budget_day=?", Long.class, campaign, yesterday));
        assertEquals(0, db.queryForObject("select spent from campaign_daily_budgets where campaign_id=? and budget_day<>?", Long.class, campaign, yesterday));
        assertAccounting();
    }

    @Test
    void revokedExpiredAndForeignInventoryCannotServe() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> platform.decide(page, placement, "publisher2"));
        db.update("update publisher_websites set verified_until=clock_timestamp()-interval '1 second' where id=?", website);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, this::decide);
    }

    @Test
    void pageUrlMustBelongToExactVerifiedDomain() {
        assertEquals("https://publisher.com/article", publishers.pageUrl(website, "publisher", "https://publisher.com/article"));
        for (String url : List.of("https://evil.com/x", "http://publisher.com/x", "https://publisher.com.evil.com/x", "https://publisher.com:8443/x", "https://publisher.com/x?personal=y"))
            assertThrows(IllegalArgumentException.class, () -> publishers.pageUrl(website, "publisher", url));
    }

    @Test
    void domainClaimNeedsCorrectChallengeAndCannotImpersonate() {
        var other = publishers.register("publisher.com", "publisher2");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> publishers.verify((UUID) other.get("id"), "publisher2"));
        when(dns.lookup("_contextads-verification.publisher.com")).thenReturn(List.of((String) other.get("recordValue")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> publishers.verify((UUID) other.get("id"), "publisher2"));
        db.update("update publisher_websites set verified_until=clock_timestamp()-interval '1 second' where id=?", website);
        publishers.verify((UUID) other.get("id"), "publisher2");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> publishers.active(website, "publisher"));
    }

    @Test
    void expiredChallengeRejected() {
        db.update("update publisher_websites set challenge_expires=clock_timestamp()-interval '1 second' where id=?", website);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> publishers.verify(website, "publisher"));
    }

    @Test
    void defaultAuthenticationRejectsBasicAndUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/campaigns")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/campaigns").header("Authorization", "Basic YWR2ZXJ0aXNlcjphZHZlcnRpc2VyLWRldg==")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/campaigns").header("x-demo-user", "admin")).andExpect(status().isUnauthorized());
    }

    Jwt jwt(String user, String role) {
        return Jwt.withTokenValue("signed").header("alg", "RS256").subject(user).claim("resource_access", Map.of("contextads-api", Map.of("roles", List.of(role)))).build();
    }

    @Test
    void bearerClientRolesAndCrossTenantAuthorization() throws Exception {
        when(decoder.decode("publisher")).thenReturn(jwt("publisher", "PUBLISHER"));
        when(decoder.decode("advertiser2")).thenReturn(jwt("advertiser2", "ADVERTISER"));
        mvc.perform(get("/api/v1/admin/campaigns").header("Authorization", "Bearer publisher")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/campaigns/" + campaign + "/pause").header("Authorization", "Bearer advertiser2")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/publishers/websites").header("Authorization", "Bearer publisher")).andExpect(status().isOk());
    }
}
