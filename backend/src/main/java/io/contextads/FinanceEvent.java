package io.contextads;
import java.time.Instant;import java.util.UUID;
record FinanceEvent(UUID eventId,long sequence,int schemaVersion,String eventType,String aggregateType,UUID aggregateId,Instant occurredAt) {}
