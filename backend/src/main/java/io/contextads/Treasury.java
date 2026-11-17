package io.contextads;

import java.util.*;
import java.math.BigInteger;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
class Treasury {
    private final JdbcTemplate db;
    Treasury(JdbcTemplate db){this.db=db;}
    static void reason(String reason){if(reason==null||reason.strip().length()<12||reason.length()>2000)throw new IllegalArgumentException("Evidence/reason must contain 12–2000 characters");}
    static String text(String value){if(value==null||value.isBlank()||value.length()>200)throw new IllegalArgumentException("Required identifier must contain 1–200 characters");return value;}
    static long number(Object value){return ((Number)value).longValue();}
    static long share(long amount,int bps){return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(bps)).divide(BigInteger.valueOf(10000)).longValueExact();}
    void lock(String kind,String subject){text(subject);db.update("insert into financial_accounts(kind,subject) values(?,?) on conflict do nothing",kind,subject);db.queryForMap("select * from financial_accounts where kind=? and subject=? for update",kind,subject);}
    void audit(UUID entity,String actor,String action,String reason){db.update("insert into treasury_audit(entity_id,actor,action,reason) values(?,?,?,?)",entity,actor,action,reason);}
    void event(UUID id,String type,String kind){db.update("insert into outbox(aggregate_id,event_type,aggregate_type) values(?,?,?)",id,type,kind);}
    Map<String,Object> row(String sql,Object...args){return db.queryForList(sql,args).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Financial record not found"));}
    void conflict(boolean invalid,String detail){if(invalid)throw new ResponseStatusException(HttpStatus.CONFLICT,detail);}

    // Caller already holds the decision lock; account lock order is advertiser then publisher.
    @Transactional public void bookRevenue(UUID billingEntry) {
        var b=row("select b.*,coalesce(d.publisher_subject,p.owner_name) publisher,d.publisher_share_bps from billing_entries b join decisions d on d.id=b.decision_id join pages p on p.id=d.page_id where b.id=?",billingEntry);
        lock("ADVERTISER",(String)b.get("advertiser"));lock("PUBLISHER",(String)b.get("publisher"));
        long amount;
        if(b.get("kind").equals("REVERSAL"))amount=-number(row("select r.* from publisher_revenue r join billing_entries b on b.id=r.billing_entry_id where b.decision_id=? and b.kind='CHARGE'",b.get("decision_id")).get("amount"));
        else amount=share(number(b.get("amount")),((Number)b.get("publisher_share_bps")).intValue());
        db.update("insert into publisher_revenue(id,billing_entry_id,decision_id,publisher,share_bps,amount) values(?,?,?,?,?,?) on conflict(billing_entry_id) do nothing",UUID.randomUUID(),billingEntry,b.get("decision_id"),b.get("publisher"),b.get("publisher_share_bps"),amount);
    }

    @Transactional public Map<String,Object> issue(String advertiser,String key,String evidence,String actor) {
        reason(evidence);text(key);lock("ADVERTISER",advertiser);
        var existing=db.queryForList("select id from invoice_batches where advertiser=? and request_key=?",advertiser,key);
        if(!existing.isEmpty())return batch((UUID)existing.getFirst().get("id"));
        UUID batch=UUID.randomUUID();db.update("insert into invoice_batches(id,advertiser,request_key) values(?,?,?)",batch,advertiser,key);
        var charges=db.queryForList("select b.*,c.name campaign_name from billing_entries b join campaigns c on c.id=b.campaign_id where b.advertiser=? and b.kind='CHARGE' and not exists(select 1 from invoice_lines l where l.billing_entry_id=b.id) order by b.created,b.id limit 500",advertiser);
        if(!charges.isEmpty())document(batch,advertiser,"INVOICE",charges,evidence,actor);
        var credits=db.queryForList("select r.*,ol.id original_line,ol.description original_description from billing_entries r join billing_entries c on c.decision_id=r.decision_id and c.kind='CHARGE' join invoice_lines ol on ol.billing_entry_id=c.id where r.advertiser=? and r.kind='REVERSAL' and not exists(select 1 from invoice_lines l where l.billing_entry_id=r.id) order by r.created,r.id limit 500",advertiser);
        if(!credits.isEmpty())document(batch,advertiser,"CREDIT_NOTE",credits,evidence,actor);
        audit(batch,actor,"ISSUE_BATCH",evidence);return batch(batch);
    }
    private void document(UUID batch,String advertiser,String kind,List<Map<String,Object>> entries,String reason,String actor){
        long total=0;for(var e:entries)total=Math.addExact(total,number(e.get("amount")));
        UUID id=UUID.randomUUID();db.update("insert into invoices(id,batch_id,advertiser,kind,total) values(?,?,?,?,?)",id,batch,advertiser,kind,total);
        for(var e:entries)db.update("insert into invoice_lines(id,invoice_id,billing_entry_id,description,amount,original_line_id) values(?,?,?,?,?,?)",UUID.randomUUID(),id,e.get("id"),kind.equals("INVOICE")?"Contextual advertising: "+e.get("campaign_name"):"Credit: "+e.get("original_description"),e.get("amount"),e.get("original_line"));
        audit(id,actor,"ISSUE_"+kind,reason);event(id,"DocumentIssued","INVOICE");
    }
    Map<String,Object> batch(UUID id){return Map.of("batchId",id,"documents",db.queryForList("select * from invoices where batch_id=? order by document_number",id));}
    Map<String,Object> document(UUID id,String owner){var doc=row("select * from invoice_balances where id=? and advertiser=?",id,owner);return Map.of("document",doc,"lines",db.queryForList("select l.*,oi.document_number original_document_number from invoice_lines l left join invoice_lines ol on ol.id=l.original_line_id left join invoices oi on oi.id=ol.invoice_id where l.invoice_id=? order by l.id",id));}

    private void lockInvoicePublishers(UUID invoice){
        for(String publisher:db.queryForList("select distinct r.publisher from invoice_lines l join publisher_revenue r on r.billing_entry_id=l.billing_entry_id where l.invoice_id=? order by r.publisher",String.class,invoice))lock("PUBLISHER",publisher);
    }
    @Transactional public Map<String,Object> importCash(String advertiser,String source,String reference,String direction,long amount,String currency,UUID original,Instant occurredAt,String evidence,String actor){
        reason(evidence);text(source);text(reference);lock("ADVERTISER",advertiser);
        if(occurredAt!=null)occurredAt=occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(amount<1||amount>9_000_000_000_000L||!"USD".equals(currency)||direction==null||!Set.of("RECEIPT","RETURN").contains(direction)||occurredAt==null||occurredAt.isAfter(Instant.now().plusSeconds(60)))throw new IllegalArgumentException("Invalid cash amount, direction, currency or event time");
        var prior=db.queryForList("select * from cash_records where source=? and external_reference=?",source,reference);
        if(!prior.isEmpty()){
            var old=prior.getFirst();conflict(!old.get("advertiser").equals(advertiser)||!old.get("direction").equals(direction)||number(old.get("amount"))!=amount||!old.get("currency").equals(currency)||!Objects.equals(old.get("original_receipt_id"),original)||!((Timestamp)old.get("occurred_at")).toInstant().equals(occurredAt),"External cash reference reused with conflicting facts");return old;
        }
        if(direction.equals("RECEIPT")&&original!=null)throw new IllegalArgumentException("A receipt cannot reference an original receipt");
        if(direction.equals("RETURN")){
            var receipt=row("select * from cash_records where id=? and advertiser=? and direction='RECEIPT'",original,advertiser);
            for(UUID invoice:db.queryForList("select invoice_id from cash_allocations where cash_id=?",UUID.class,original))lockInvoicePublishers(invoice);
            long returned=db.queryForObject("select coalesce(sum(amount),0) from cash_records where original_receipt_id=?",Long.class,original);
            conflict(amount>number(receipt.get("amount"))-returned,"Returns exceed the original receipt");
        }
        UUID id=UUID.randomUUID();db.update("insert into cash_records(id,advertiser,source,external_reference,direction,amount,currency,original_receipt_id,occurred_at) values(?,?,?,?,?,?,?,?,?)",id,advertiser,source,reference,direction,amount,currency,original,Timestamp.from(occurredAt));
        audit(id,actor,"IMPORT_CASH",evidence);event(id,"CashImported","CASH");return row("select * from cash_records where id=?",id);
    }
    @Transactional public Map<String,Object> match(UUID cashId,UUID invoice,String evidence,String actor){
        reason(evidence);var cash=row("select * from cash_records where id=?",cashId);lock("ADVERTISER",(String)cash.get("advertiser"));
        var old=db.queryForList("select * from cash_allocations where cash_id=?",cashId);
        if(!old.isEmpty()){conflict(!Objects.equals(old.getFirst().get("invoice_id"),invoice),"Cash already matched to another invoice");return old.getFirst();}
        if(invoice==null){
            conflict(!cash.get("direction").equals("RETURN"),"Only an externally returned unmatched receipt can be closed without an invoice");
            var original=row("select * from cash_records where id=?",cash.get("original_receipt_id"));
            conflict(number(original.get("amount"))!=number(cash.get("amount")),"A full unmatched receipt return is required");
            conflict(db.queryForObject("select count(*) from cash_allocations where cash_id=?",Integer.class,original.get("id"))!=0,"Original receipt already matched; use its invoice");
            db.update("insert into cash_allocations(cash_id,invoice_id,amount) values(?,null,?)",original.get("id"),original.get("amount"));
            db.update("insert into cash_allocations(cash_id,invoice_id,amount) values(?,null,?)",cashId,-number(cash.get("amount")));
            audit(cashId,actor,"RECONCILE_UNMATCHED_RETURN",evidence);event(cashId,"CashMatched","CASH");event((UUID)original.get("id"),"CashMatched","CASH");
            return row("select * from cash_allocations where cash_id=?",cashId);
        }
        var doc=row("select * from invoice_balances where id=? and advertiser=? and kind='INVOICE'",invoice,cash.get("advertiser"));
        // Lock impacted publishers in deterministic order before changing collection eligibility.
        lockInvoicePublishers(invoice);
        long amount=number(cash.get("amount"));
        if(cash.get("direction").equals("RECEIPT"))conflict(amount>Math.max(0,number(doc.get("net_due"))-number(doc.get("paid"))),"Receipt exceeds outstanding invoice amount; retain unmatched for investigation");
        else{
            var original=row("select * from cash_allocations where cash_id=?",cash.get("original_receipt_id"));
            conflict(!original.get("invoice_id").equals(invoice),"Return must match the original invoice");amount=-amount;
        }
        db.update("insert into cash_allocations(cash_id,invoice_id,amount) values(?,?,?)",cashId,invoice,amount);
        audit(cashId,actor,"MATCH_CASH",evidence);event(cashId,"CashMatched","CASH");return row("select * from cash_allocations where cash_id=?",cashId);
    }

    Map<String,Object> publisherSummary(String publisher){
        long validated=db.queryForObject("select coalesce(sum(amount),0) from publisher_revenue where publisher=?",Long.class,publisher);
        long eligible=db.queryForObject("""
            select coalesce(sum(r.amount),0) from publisher_revenue r
            join billing_entries b on b.id=r.billing_entry_id and b.kind='CHARGE'
            join fraud_cases f on f.decision_id=r.decision_id and f.status='VALID'
            join invoice_lines l on l.billing_entry_id=b.id join invoice_balances i on i.id=l.invoice_id
            where r.publisher=? and i.paid>=i.net_due
            and not exists(select 1 from fraud_signals fs where fs.decision_id=r.decision_id)
            and not exists(select 1 from cash_records cr join cash_allocations ca on ca.cash_id=cr.original_receipt_id
                where cr.direction='RETURN' and ca.invoice_id=i.id and not exists(select 1 from cash_allocations applied where applied.cash_id=cr.id))
            """,Long.class,publisher);
        long reserved=db.queryForObject("select coalesce(sum(s.amount),0) from settlements s join settlement_states st on st.settlement_id=s.id where s.publisher=? and st.status in ('RESERVED','SUBMITTED')",Long.class,publisher);
        long paid=db.queryForObject("select coalesce(sum(e.amount),0) from settlements s join payout_evidence e on e.settlement_id=s.id where s.publisher=? and e.outcome='PAID'",Long.class,publisher);
        return Map.of("currency","USD","validatedRevenue",validated,"collectedEligible",eligible,"reservedForSettlement",reserved,"settled",paid,"available",eligible-reserved-paid,"recoveryDebt",Math.max(0,paid-eligible),"fundingShortfall",Math.max(0,paid+reserved-eligible),"shareBasisPoints",7000);
    }
    @Transactional public Map<String,Object> reserve(String publisher,String key,long amount,String evidence,String actor){
        reason(evidence);text(key);lock("PUBLISHER",publisher);
        var old=db.queryForList("select s.*,st.status from settlements s join settlement_states st on st.settlement_id=s.id where s.publisher=? and s.request_key=?",publisher,key);
        if(!old.isEmpty()){conflict(number(old.getFirst().get("amount"))!=amount,"Settlement idempotency key reused with different amount");return old.getFirst();}
        long available=number(publisherSummary(publisher).get("available"));conflict(amount<1||amount>available,"Insufficient collected publisher balance");
        UUID id=UUID.randomUUID();db.update("insert into settlements(id,publisher,request_key,amount) values(?,?,?,?)",id,publisher,key,amount);db.update("insert into settlement_states(settlement_id,status) values(?,'RESERVED')",id);
        audit(id,actor,"RESERVE_SETTLEMENT",evidence);event(id,"SettlementReserved","SETTLEMENT");return settlement(id);
    }
    Map<String,Object> settlement(UUID id){return row("select s.*,st.status from settlements s join settlement_states st on st.settlement_id=s.id where s.id=?",id);}
    @Transactional public Map<String,Object> submit(UUID id,String evidence,String actor){
        reason(evidence);var s=settlement(id);lock("PUBLISHER",(String)s.get("publisher"));s=settlement(id);
        if(Set.of("SUBMITTED","PAID").contains(s.get("status")))return s;
        conflict(!s.get("status").equals("RESERVED"),"Settlement cannot be submitted");
        conflict(number(publisherSummary((String)s.get("publisher")).get("available"))<0,"Funding changed; resolve holds before producing an instruction");
        db.update("insert into payout_instructions(settlement_id,idempotency_key) values(?,?)",id,id);
        db.update("update settlement_states set status='SUBMITTED',updated=clock_timestamp() where settlement_id=?",id);
        audit(id,actor,"SUBMIT_MANUAL_INSTRUCTION",evidence);event(id,"SettlementSubmitted","SETTLEMENT");return settlement(id);
    }
    @Transactional public Map<String,Object> confirm(UUID id,String outcome,String reference,long amount,String currency,String evidence,String actor){
        reason(evidence);text(reference);if(outcome==null||!Set.of("PAID","FAILED").contains(outcome)||!"USD".equals(currency))throw new IllegalArgumentException("Invalid payout outcome/currency");
        var s=settlement(id);lock("PUBLISHER",(String)s.get("publisher"));s=settlement(id);
        var prior=db.queryForList("select * from payout_evidence where settlement_id=?",id);
        if(!prior.isEmpty()){var e=prior.getFirst();conflict(!e.get("bank_reference").equals(reference)||!e.get("outcome").equals(outcome)||number(e.get("amount"))!=amount,"Conflicting bank confirmation");return s;}
        conflict(!s.get("status").equals("SUBMITTED")||number(s.get("amount"))!=amount,"Confirmation must match a submitted instruction exactly");
        db.update("insert into payout_evidence(settlement_id,outcome,bank_reference,amount,currency) values(?,?,?,?,?)",id,outcome,reference,amount,currency);
        db.update("update settlement_states set status=?,updated=clock_timestamp() where settlement_id=?",outcome,id);
        audit(id,actor,"CONFIRM_"+outcome,evidence);event(id,"SettlementConfirmed","SETTLEMENT");return settlement(id);
    }
    @Transactional public Map<String,Object> cancel(UUID id,String evidence,String actor){
        reason(evidence);var s=settlement(id);lock("PUBLISHER",(String)s.get("publisher"));s=settlement(id);
        if(s.get("status").equals("CANCELLED"))return s;
        conflict(!s.get("status").equals("RESERVED"),"Unknown/submitted payouts cannot be released without bank failure evidence");
        db.update("update settlement_states set status='CANCELLED',updated=clock_timestamp() where settlement_id=?",id);audit(id,actor,"CANCEL_SETTLEMENT",evidence);event(id,"SettlementCancelled","SETTLEMENT");return settlement(id);
    }
}
