package io.contextads;

import java.time.Instant;
import java.util.UUID;

record IntegrationEvent(UUID eventId, long sequence, int schemaVersion, String eventType, UUID decisionId,
                        Instant occurredAt) {
}
