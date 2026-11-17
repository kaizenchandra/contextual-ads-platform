package io.contextads;

import java.util.*;
import java.time.Instant;
import java.security.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
class TreasuryApi {
    private final JdbcTemplate db;private final Treasury treasury;
    TreasuryApi(JdbcTemplate db,Treasury treasury){this.db=db;this.treasury=treasury;}
    record Issue(String advertiser,String requestKey,String reason){}
    record Cash(String advertiser,String source,String reference,String direction,long amount,String currency,UUID originalReceiptId,Instant occurredAt,String reason){}
    record Match(UUID invoiceId,String reason){}
    record Reserve(String publisher,String requestKey,long amount,String reason){}
    record Evidence(String reason){}
    record Confirm(String outcome,String reference,long amount,String currency,String reason){}
    @GetMapping("/identity") Map<String,Object> identity(Principal user){return Map.of("subject",user.getName());}
    @PostMapping("/admin/invoices/issue") Map<String,Object> issue(@RequestBody Issue body,Principal user){return treasury.issue(body.advertiser(),body.requestKey(),body.reason(),user.getName());}
    @GetMapping("/billing/invoices") List<Map<String,Object>> invoices(Principal user){return db.queryForList("select *,greatest(0,net_due-paid) outstanding,greatest(0,paid-net_due) overpaid from invoice_balances where advertiser=? order by document_number desc limit 100",user.getName());}
    @GetMapping("/billing/invoices/{id}") Map<String,Object> invoice(@PathVariable UUID id,Principal user){return treasury.document(id,user.getName());}
    @GetMapping("/admin/invoices") List<Map<String,Object>> allInvoices(){return db.queryForList("select *,greatest(0,net_due-paid) outstanding from invoice_balances order by document_number desc limit 100");}
    @PostMapping("/admin/cash/import") Map<String,Object> cash(@RequestBody Cash body,Principal user){return treasury.importCash(body.advertiser(),body.source(),body.reference(),body.direction(),body.amount(),body.currency(),body.originalReceiptId(),body.occurredAt(),body.reason(),user.getName());}
    @PostMapping("/admin/cash/{id}/match") Map<String,Object> match(@PathVariable UUID id,@RequestBody Match body,Principal user){return treasury.match(id,body.invoiceId(),body.reason(),user.getName());}
    @GetMapping("/admin/cash") List<Map<String,Object>> cash(){return db.queryForList("select c.*,a.invoice_id,a.amount allocated_amount from cash_records c left join cash_allocations a on a.cash_id=c.id order by c.created desc,c.id limit 100");}
    @GetMapping("/billing/payments") List<Map<String,Object>> payments(Principal user){return db.queryForList("select c.*,a.invoice_id,a.amount allocated_amount from cash_records c left join cash_allocations a on a.cash_id=c.id where c.advertiser=? order by c.created desc,c.id limit 100",user.getName());}
    @GetMapping("/publishers/revenue") Map<String,Object> publisher(Principal user){return treasury.publisherSummary(user.getName());}
    @GetMapping("/publishers/revenue/entries") List<Map<String,Object>> revenue(Principal user){return db.queryForList("select id,decision_id,share_bps,amount,currency,created from publisher_revenue where publisher=? order by created desc,id limit 100",user.getName());}
    @GetMapping("/publishers/settlements") List<Map<String,Object>> settlements(Principal user){return db.queryForList("select s.*,st.status,i.idempotency_key,e.bank_reference,e.outcome from settlements s join settlement_states st on st.settlement_id=s.id left join payout_instructions i on i.settlement_id=s.id left join payout_evidence e on e.settlement_id=s.id where s.publisher=? order by s.created desc,s.id limit 100",user.getName());}
    @GetMapping("/admin/settlements") List<Map<String,Object>> settlements(){return db.queryForList("select s.*,st.status,i.idempotency_key,e.bank_reference,e.outcome from settlements s join settlement_states st on st.settlement_id=s.id left join payout_instructions i on i.settlement_id=s.id left join payout_evidence e on e.settlement_id=s.id order by s.created desc,s.id limit 100");}
    @PostMapping("/admin/settlements") Map<String,Object> reserve(@RequestBody Reserve body,Principal user){return treasury.reserve(body.publisher(),body.requestKey(),body.amount(),body.reason(),user.getName());}
    @PostMapping("/admin/settlements/{id}/submit") Map<String,Object> submit(@PathVariable UUID id,@RequestBody Evidence body,Principal user){return treasury.submit(id,body.reason(),user.getName());}
    @PostMapping("/admin/settlements/{id}/cancel") Map<String,Object> cancel(@PathVariable UUID id,@RequestBody Evidence body,Principal user){return treasury.cancel(id,body.reason(),user.getName());}
    @PostMapping("/admin/settlements/{id}/confirm") Map<String,Object> confirm(@PathVariable UUID id,@RequestBody Confirm body,Principal user){return treasury.confirm(id,body.outcome(),body.reference(),body.amount(),body.currency(),body.reason(),user.getName());}
    @GetMapping("/admin/treasury/reconciliation") Map<String,Object> reconcile(){
        return Map.of("settlements",db.queryForList("select s.id,s.amount,st.status,e.outcome,e.amount evidenced_amount from settlements s join settlement_states st on st.settlement_id=s.id left join payout_evidence e on e.settlement_id=s.id order by s.created desc limit 100"),"documents",db.queryForList("select i.id,i.kind,i.total,coalesce(sum(l.amount),0) line_total from invoices i left join invoice_lines l on l.invoice_id=i.id group by i.id order by i.document_number desc limit 100"),
            "unmatchedCash",db.queryForObject("select count(*) from cash_records c where not exists(select 1 from cash_allocations a where a.cash_id=c.id)",Long.class),
            "publishers",db.queryForList("select distinct publisher from publisher_revenue order by publisher limit 100",String.class).stream().map(p->Map.of("publisher",p,"balances",treasury.publisherSummary(p))).toList());
    }
    @GetMapping("/admin/treasury/audit/{id}") List<Map<String,Object>> audit(@PathVariable UUID id){return db.queryForList("select * from treasury_audit where entity_id=? order by id limit 100",id);}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ProblemDetail> bad(IllegalArgumentException error){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,error.getMessage()));}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) ResponseEntity<ProblemDetail> duplicate(){return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Financial reference is already recorded"));}
}
