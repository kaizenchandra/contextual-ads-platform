package io.contextads;

import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties="ads.events.enabled=false") @AutoConfigureMockMvc @Testcontainers
class TreasuryIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6");
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);}
    @Autowired JdbcTemplate db;@Autowired Treasury treasury;@Autowired Billing billing;@Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    UUID campaign,page;
    static final String WHY="Independent bank statement and financial evidence reviewed";
    @BeforeEach void seed(){
        db.execute("truncate financial_accounts,invoice_batches,invoices,invoice_lines,cash_records,cash_allocations,publisher_revenue,settlements,settlement_states,payout_instructions,payout_evidence,treasury_audit,treasury_notifications,conversions,events,movements,outbox,decisions,campaign_daily_budgets,pages,publisher_placements,domain_ownership,publisher_websites,campaigns cascade");
        campaign=UUID.randomUUID();page=UUID.randomUUID();
        db.update("insert into campaigns(id,owner_name,name,category,headline,destination,budget,bid,daily_budget) values(?,'advertiser','finance','technology.java','Java','https://example.org',10000,101,10000)",campaign);
        db.update("insert into pages(id,owner_name,title,category,confidence,keywords,safe) values(?,'publisher','Java','technology.java',1,'java',true)",page);
        db.update("insert into campaign_daily_budgets(campaign_id,budget_day,budget_limit) values(?,(now() at time zone 'UTC')::date,10000)",campaign);
    }
    long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    UUID charge(long price){
        UUID d=UUID.randomUUID();db.update("update campaigns set spent=spent+? where id=?",price,campaign);
        db.update("update campaign_daily_budgets set spent=spent+? where campaign_id=?",price,campaign);
        db.update("insert into decisions(id,campaign_id,page_id,price,expires,budget_day,state,publisher_subject) values(?,?,?,?,now()+interval '5 minutes',(now() at time zone 'UTC')::date,'COMMITTED','publisher')",d,campaign,page,price);
        db.update("insert into movements(decision_id,kind,amount) values(?,'COMMIT',?)",d,price);
        billing.review(d,"VALIDATE",WHY,"admin");return d;
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> documents(Map<String,Object> batch){return (List<Map<String,Object>>)batch.get("documents");}
    UUID invoice(){return (UUID)documents(treasury.issue("advertiser",UUID.randomUUID().toString(),WHY,"admin")).stream().filter(x->x.get("kind").equals("INVOICE")).findFirst().orElseThrow().get("id");}
    UUID receipt(long amount){return (UUID)treasury.importCash("advertiser","bank",UUID.randomUUID().toString(),"RECEIPT",amount,"USD",null,Instant.now(),WHY,"admin").get("id");}
    UUID collected(){charge(101);UUID invoice=invoice();treasury.match(receipt(101),invoice,WHY,"admin");return invoice;}
    long pub(String field){return Treasury.number(treasury.publisherSummary("publisher").get(field));}
    @Test void invoiceIssuanceIsConcurrentIdempotentAndSnapshotIsImmutable() throws Exception{
        charge(101);
        try(var pool=Executors.newFixedThreadPool(8)){
            var calls=new ArrayList<Callable<Map<String,Object>>>();for(int i=0;i<16;i++)calls.add(()->treasury.issue("advertiser","same-command",WHY,"admin"));
            Set<UUID> ids=new HashSet<>();for(var f:pool.invokeAll(calls))ids.add((UUID)f.get().get("batchId"));assertEquals(1,ids.size());
        }
        assertEquals(1,count("select count(*) from invoices"));assertEquals(1,count("select count(*) from invoice_lines"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update invoices set total=1"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("delete from invoice_lines"));
        assertTrue(documents(treasury.issue("advertiser","new-command",WHY,"admin")).isEmpty());
    }
    @Test void invoiceFailureRollsBackBatchLinesAuditAndOutbox(){
        charge(101);long out=count("select count(*) from outbox");
        db.execute("create function fail_invoice_line() returns trigger language plpgsql as $$ begin raise exception 'injected invoice failure'; end $$");
        db.execute("create trigger fail_invoice_line before insert on invoice_lines for each row execute function fail_invoice_line()");
        assertThrows(org.springframework.dao.DataAccessException.class,()->treasury.issue("advertiser","retryable",WHY,"admin"));
        assertEquals(0,count("select count(*) from invoice_batches"));assertEquals(0,count("select count(*) from invoices"));assertEquals(out,count("select count(*) from outbox"));
        db.execute("drop trigger fail_invoice_line on invoice_lines");db.execute("drop function fail_invoice_line()");
        assertEquals(1,documents(treasury.issue("advertiser","retryable",WHY,"admin")).size());
    }
    @Test void creditsNeverRewriteOriginalInvoiceAndRoundingReversesExactly(){
        UUID d=charge(101),inv=invoice();assertEquals(70,pub("validatedRevenue"));
        billing.review(d,"REVERSE",WHY,"admin");assertEquals(0,pub("validatedRevenue"));
        var docs=documents(treasury.issue("advertiser","credit-batch",WHY,"admin"));assertEquals(1,docs.size());assertEquals("CREDIT_NOTE",docs.getFirst().get("kind"));
        assertEquals(101,count("select total from invoices where id=?",inv));assertEquals(0,count("select net_due from invoice_balances where id=?",inv));
        assertEquals(-70,count("select r.amount from publisher_revenue r join billing_entries b on b.id=r.billing_entry_id where b.kind='REVERSAL'"));
        assertEquals(1,count("select count(*) from invoice_lines where original_line_id is not null"));assertEquals(0,pub("available"));
    }
    @Test void reversalBeforeInvoicingProducesLinkedDocumentsWithoutPayoutEligibility(){
        UUID d=charge(101);billing.review(d,"REVERSE",WHY,"admin");
        assertEquals(2,documents(treasury.issue("advertiser","close",WHY,"admin")).size());
        assertEquals(0,count("select sum(total) from invoices"));assertEquals(0,pub("collectedEligible"));
    }
    @Test void partialPaymentsDuplicatesConflictsAndOverpaymentsRemainAuditable(){
        charge(101);UUID inv=invoice();Instant time=Instant.now();
        var first=treasury.importCash("advertiser","bank","ref-one","RECEIPT",40,"USD",null,time,WHY,"admin");
        assertEquals(first.get("id"),treasury.importCash("advertiser","bank","ref-one","RECEIPT",40,"USD",null,time,WHY,"admin").get("id"));
        assertThrows(ResponseStatusException.class,()->treasury.importCash("advertiser","bank","ref-one","RECEIPT",41,"USD",null,time,WHY,"admin"));
        treasury.match((UUID)first.get("id"),inv,WHY,"admin");treasury.match((UUID)first.get("id"),inv,WHY,"admin");
        assertEquals(40,count("select paid from invoice_balances where id=?",inv));assertEquals(0,pub("available"));
        treasury.match(receipt(61),inv,WHY,"admin");assertEquals(70,pub("available"));
        UUID excess=receipt(1);assertThrows(ResponseStatusException.class,()->treasury.match(excess,inv,WHY,"admin"));
        assertEquals(3,count("select count(*) from cash_records"));assertEquals(2,count("select count(*) from cash_allocations"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update cash_records set amount=2"));
    }
    @Test void unallocatedReturnHoldsEligibilityAndCannotExceedOriginalReceipt(){
        charge(101);UUID inv=invoice(),receipt=receipt(101);treasury.match(receipt,inv,WHY,"admin");assertEquals(70,pub("available"));
        UUID returned=(UUID)treasury.importCash("advertiser","bank","returned","RETURN",101,"USD",receipt,Instant.now(),WHY,"admin").get("id");
        assertEquals(0,pub("available"));
        assertThrows(ResponseStatusException.class,()->treasury.importCash("advertiser","bank","over-return","RETURN",1,"USD",receipt,Instant.now(),WHY,"admin"));
        treasury.match(returned,inv,WHY,"admin");treasury.match(returned,inv,WHY,"admin");assertEquals(0,count("select paid from invoice_balances where id=?",inv));assertEquals(0,pub("available"));
    }
    @Test void receivableSummarySubtractsCashAndShowsCreditUntilRefundReconciles() throws Exception{
        UUID inv=collected();
        when(decoder.decode("receivable-owner")).thenReturn(Jwt.withTokenValue("receivable-owner").header("alg","RS256").subject("advertiser").claim("resource_access",Map.of("contextads-api",Map.of("roles",List.of("ADVERTISER")))).build());
        mvc.perform(get("/api/v1/billing/summary").header("Authorization","Bearer receivable-owner")).andExpect(status().isOk()).andExpect(jsonPath("$.paid").value(101)).andExpect(jsonPath("$.net_charges").value(101)).andExpect(jsonPath("$.net_receivable").value(0));
        UUID d=db.queryForObject("select id from decisions",UUID.class);billing.review(d,"REVERSE",WHY,"admin");treasury.issue("advertiser","post-payment-credit",WHY,"admin");
        mvc.perform(get("/api/v1/billing/summary").header("Authorization","Bearer receivable-owner")).andExpect(jsonPath("$.net_receivable").value(-101));
        UUID original=db.queryForObject("select id from cash_records where direction='RECEIPT'",UUID.class);
        UUID returned=(UUID)treasury.importCash("advertiser","bank","customer-refund","RETURN",101,"USD",original,Instant.now(),WHY,"admin").get("id");treasury.match(returned,inv,WHY,"admin");
        mvc.perform(get("/api/v1/billing/summary").header("Authorization","Bearer receivable-owner")).andExpect(jsonPath("$.net_receivable").value(0)).andExpect(jsonPath("$.paid").value(0));
    }
    @Test void overpaymentCanBeClosedWithEvidenceOfFullExternalReturn(){
        collected();UUID excess=receipt(20);
        UUID returned=(UUID)treasury.importCash("advertiser","bank","unmatched-return","RETURN",20,"USD",excess,Instant.now(),WHY,"admin").get("id");
        treasury.match(returned,null,WHY,"admin");treasury.match(returned,null,WHY,"admin");
        assertEquals(0,count("select sum(amount) from cash_allocations where invoice_id is null"));
        assertEquals(0,count("select count(*) from cash_records c where not exists(select 1 from cash_allocations a where a.cash_id=c.id)"));
        assertEquals(70,pub("available"));
    }
    @Test void returnedFundsBlockNewSubmissionWithoutDroppingExistingReservation(){
        charge(101);UUID inv=invoice(),received=receipt(101);treasury.match(received,inv,WHY,"admin");
        UUID settlement=(UUID)treasury.reserve("publisher","funding-changes",70,WHY,"admin").get("id");
        treasury.importCash("advertiser","bank","return-before-submit","RETURN",101,"USD",received,Instant.now(),WHY,"admin");
        assertThrows(ResponseStatusException.class,()->treasury.submit(settlement,WHY,"admin"));
        assertEquals("RESERVED",treasury.settlement(settlement).get("status"));assertEquals(70,pub("fundingShortfall"));assertEquals(0,pub("recoveryDebt"));
        treasury.cancel(settlement,WHY,"admin");assertEquals(0,pub("recoveryDebt"));
    }
    @Test void settlementConcurrencyCannotReserveSameCollectedFundsTwice() throws Exception{
        collected();
        try(var pool=Executors.newFixedThreadPool(8)){
            var calls=new ArrayList<Callable<Boolean>>();for(int i=0;i<16;i++){String key="settlement-"+i;calls.add(()->{try{treasury.reserve("publisher",key,70,WHY,"admin");return true;}catch(ResponseStatusException e){return false;}});}
            int successes=0;for(var f:pool.invokeAll(calls))if(f.get())successes++;assertEquals(1,successes);
        }
        assertEquals(70,pub("reservedForSettlement"));assertEquals(0,pub("available"));
    }
    @Test void ambiguousSubmissionNeverExpiresOrReleasesAndConfirmationRetriesAreIdempotent(){
        collected();UUID s=(UUID)treasury.reserve("publisher","payout",70,WHY,"admin").get("id");
        treasury.submit(s,WHY,"admin");treasury.submit(s,WHY,"admin");
        assertEquals(1,count("select count(*) from payout_instructions"));
        assertThrows(ResponseStatusException.class,()->treasury.cancel(s,WHY,"admin"));assertEquals(70,pub("reservedForSettlement"));
        treasury.confirm(s,"PAID","bank-out-1",70,"USD",WHY,"admin");treasury.confirm(s,"PAID","bank-out-1",70,"USD",WHY,"admin");
        assertEquals(70,pub("settled"));assertEquals(0,pub("reservedForSettlement"));assertEquals(1,count("select count(*) from payout_evidence"));
        assertThrows(ResponseStatusException.class,()->treasury.confirm(s,"PAID","other-bank-reference",70,"USD",WHY,"admin"));
    }
    @Test void payoutConfirmationFailureRollsBackAndSameBankReferenceCanBeRetried(){
        collected();UUID s=(UUID)treasury.reserve("publisher","payout",70,WHY,"admin").get("id");treasury.submit(s,WHY,"admin");
        db.execute("create function fail_payout() returns trigger language plpgsql as $$ begin if NEW.status='PAID' then raise exception 'injected confirmation failure'; end if; return NEW; end $$");
        db.execute("create trigger fail_payout before update on settlement_states for each row execute function fail_payout()");
        assertThrows(org.springframework.dao.DataAccessException.class,()->treasury.confirm(s,"PAID","retry-bank",70,"USD",WHY,"admin"));
        assertEquals("SUBMITTED",treasury.settlement(s).get("status"));assertEquals(0,count("select count(*) from payout_evidence"));assertEquals(70,pub("reservedForSettlement"));
        db.execute("drop trigger fail_payout on settlement_states");db.execute("drop function fail_payout()");
        treasury.confirm(s,"PAID","retry-bank",70,"USD",WHY,"admin");assertEquals(70,pub("settled"));
    }
    @Test void confirmedFailureReleasesFundsAndCancelledKeysDoNotCreateNewInstructions(){
        collected();UUID s=(UUID)treasury.reserve("publisher","failed",70,WHY,"admin").get("id");treasury.submit(s,WHY,"admin");
        treasury.confirm(s,"FAILED","bank-failure-evidence",70,"USD",WHY,"admin");assertEquals(70,pub("available"));
        UUID cancelled=(UUID)treasury.reserve("publisher","cancelled",70,WHY,"admin").get("id");treasury.cancel(cancelled,WHY,"admin");
        assertEquals("CANCELLED",treasury.reserve("publisher","cancelled",70,WHY,"admin").get("status"));
        assertThrows(ResponseStatusException.class,()->treasury.submit(cancelled,WHY,"admin"));assertEquals(70,pub("available"));
    }
    @Test void reversalAfterPayoutCreatesRecoveryDebtWithoutDeletingHistory(){
        UUID d=charge(101),inv=invoice();treasury.match(receipt(101),inv,WHY,"admin");
        UUID s=(UUID)treasury.reserve("publisher","paid",70,WHY,"admin").get("id");treasury.submit(s,WHY,"admin");treasury.confirm(s,"PAID","bank-paid",70,"USD",WHY,"admin");
        billing.review(d,"REVERSE",WHY,"admin");assertEquals(-70,pub("available"));assertEquals(70,pub("recoveryDebt"));
        treasury.issue("advertiser","credit",WHY,"admin");assertEquals(0,count("select net_due from invoice_balances where id=?",inv));
        charge(201);UUID next=invoice();treasury.match(receipt(201),next,WHY,"admin");assertEquals(70,pub("available"));assertEquals(0,pub("recoveryDebt"));
        assertEquals(1,count("select count(*) from payout_evidence"));assertEquals(302,count("select spent from campaigns where id=?",campaign));
    }
    @Test void v3UpgradePreservesHistoryAndBackfillsExactRevenue() throws Exception{
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");db.execute("create schema "+schema);
        try{
            org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            try(var connection=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())){
                connection.setSchema(schema);var old=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
                UUID campaign=UUID.randomUUID(),page=UUID.randomUUID(),d=UUID.randomUUID();
                old.update("insert into campaigns(id,owner_name,name,category,headline,destination,budget,bid,daily_budget,spent) values(?,'legacy-advertiser','legacy','technology.java','Java','https://example.org',1000,101,1000,101)",campaign);
                old.update("insert into pages(id,owner_name,title,category,confidence,keywords,safe) values(?,'legacy-publisher','Java','technology.java',1,'java',true)",page);
                old.update("insert into campaign_daily_budgets(campaign_id,budget_day,budget_limit,spent) values(?,current_date,1000,101)",campaign);
                old.update("insert into decisions(id,campaign_id,page_id,price,expires,budget_day,state) values(?,?,?,101,now(),current_date,'COMMITTED')",d,campaign,page);
                old.update("insert into billing_entries(id,decision_id,campaign_id,advertiser,currency,kind,amount) values(?,?,?,'legacy-advertiser','USD','CHARGE',101)",UUID.randomUUID(),d,campaign);
                old.update("insert into billing_entries(id,decision_id,campaign_id,advertiser,currency,kind,amount) values(?,?,?,'legacy-advertiser','USD','REVERSAL',-101)",UUID.randomUUID(),d,campaign);
                org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
                assertEquals(2,old.queryForObject("select count(*) from billing_entries",Integer.class));
                assertEquals(2,old.queryForObject("select count(*) from publisher_revenue",Integer.class));
                assertEquals(70,old.queryForObject("select max(amount) from publisher_revenue",Long.class));
                assertEquals(-70,old.queryForObject("select min(amount) from publisher_revenue",Long.class));
                assertEquals(0,old.queryForObject("select sum(amount) from publisher_revenue",Long.class));
                assertEquals(0,old.queryForObject("select count(*) from cash_records",Integer.class));
                assertEquals("legacy-publisher",old.queryForObject("select publisher_subject from decisions",String.class));
            }
        }finally{db.execute("drop schema "+schema+" cascade");}
    }
    @Test void financialReadsAndMutationsAreTenantAndRoleIsolated() throws Exception{
        charge(101);UUID inv=invoice();
        for(String user:List.of("other","publisher"))when(decoder.decode(user)).thenReturn(Jwt.withTokenValue(user).header("alg","RS256").subject(user).claim("resource_access",Map.of("contextads-api",Map.of("roles",List.of(user.equals("publisher")?"PUBLISHER":"ADVERTISER")))).build());
        mvc.perform(get("/api/v1/billing/invoices/"+inv).header("Authorization","Bearer other")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/billing/invoices").header("Authorization","Bearer other")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/v1/admin/invoices/issue").header("Authorization","Bearer publisher").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/publishers/revenue").header("Authorization","Bearer publisher")).andExpect(status().isOk()).andExpect(jsonPath("$.validatedRevenue").value(70)).andExpect(jsonPath("$.collectedEligible").value(0));
    }
}
