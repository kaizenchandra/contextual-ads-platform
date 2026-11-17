package io.contextads;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TreasuryConsumer {
    static final String NAME="treasury-observer-v1";
    private final JdbcTemplate db;private final ObjectMapper json;
    TreasuryConsumer(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    @Transactional public void consume(String body){
        FinanceEvent e;
        try{if(body==null||body.length()>8192)throw new IllegalArgumentException();e=json.readValue(body,FinanceEvent.class);}
        catch(Exception bad){throw new IllegalArgumentException("Invalid finance event");}
        if(e.schemaVersion()!=1||e.eventId()==null||e.aggregateId()==null)throw new IllegalArgumentException("Unsupported finance event");
        var rows=db.queryForList("select * from outbox where event_id=?",e.eventId());
        if(rows.isEmpty())throw new IllegalArgumentException("Unknown finance source");var o=rows.getFirst();
        if(!o.get("aggregate_id").equals(e.aggregateId())||!o.get("aggregate_type").equals(e.aggregateType())||!o.get("event_type").equals(e.eventType())||((Number)o.get("id")).longValue()!=e.sequence()||((Number)o.get("schema_version")).intValue()!=e.schemaVersion()||!((java.sql.Timestamp)o.get("created")).toInstant().equals(e.occurredAt()))throw new IllegalArgumentException("Finance event differs from source");
        String table=switch(e.aggregateType()){case "INVOICE"->"invoices";case "CASH"->"cash_records";case "SETTLEMENT"->"settlements";default->throw new IllegalArgumentException("Unknown financial aggregate");};
        if(!Set.of("DocumentIssued","CashImported","CashMatched","SettlementReserved","SettlementSubmitted","SettlementConfirmed","SettlementCancelled").contains(e.eventType())||db.queryForObject("select count(*) from "+table+" where id=?",Integer.class,e.aggregateId())!=1)throw new IllegalArgumentException("Invalid financial source");
        if(db.update("insert into processed_events(consumer_name,event_id) values(?,?) on conflict do nothing",NAME,e.eventId())==0)return;
        db.update("insert into treasury_notifications(event_id,entity_id,entity_type,event_type) values(?,?,?,?)",e.eventId(),e.aggregateId(),e.aggregateType(),e.eventType());
    }
}
