ALTER TABLE decisions
    ADD COLUMN synthetic boolean NOT NULL DEFAULT true;
ALTER TABLE decisions
    ALTER COLUMN synthetic SET DEFAULT false;
ALTER TABLE campaigns
    ADD COLUMN currency char(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD');
CREATE TABLE event_counters
(
    decision_id uuid   NOT NULL REFERENCES decisions (id),
    kind        text   NOT NULL,
    attempts    bigint NOT NULL DEFAULT 1 CHECK (attempts > 0),
    PRIMARY KEY (decision_id, kind)
);
CREATE TABLE fraud_signals
(
    decision_id uuid        NOT NULL REFERENCES decisions (id),
    rule        text        NOT NULL,
    created     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (decision_id, rule)
);
CREATE TABLE fraud_cases
(
    decision_id  uuid PRIMARY KEY REFERENCES decisions (id),
    status       text        NOT NULL DEFAULT 'PENDING' CHECK (status IN
                                                               ('PENDING', 'REVIEW', 'VALID', 'INVALID', 'DISPUTED',
                                                                'REVERSED')),
    rules        text        NOT NULL DEFAULT '',
    rule_version int         NOT NULL DEFAULT 1,
    updated      timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE billing_entries
(
    id          uuid PRIMARY KEY,
    decision_id uuid        NOT NULL REFERENCES decisions (id),
    campaign_id uuid        NOT NULL REFERENCES campaigns (id),
    advertiser  text        NOT NULL,
    currency    char(3)     NOT NULL CHECK (currency = 'USD'),
    kind        text        NOT NULL CHECK (kind IN ('CHARGE', 'REVERSAL')),
    amount      bigint      NOT NULL,
    created     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (decision_id, kind),
    CHECK ((kind = 'CHARGE' AND amount > 0) OR (kind = 'REVERSAL' AND amount < 0))
);
CREATE INDEX billing_advertiser ON billing_entries (advertiser, created, id);
CREATE TABLE financial_audit
(
    id          uuid PRIMARY KEY,
    decision_id uuid        NOT NULL REFERENCES decisions (id),
    actor       text        NOT NULL,
    action      text        NOT NULL,
    reason      text        NOT NULL,
    old_status  text        NOT NULL,
    new_status  text        NOT NULL,
    created     timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION append_only_financial_history() RETURNS trigger
    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Financial history is append-only';
END $$;
CREATE TRIGGER billing_immutable
    BEFORE UPDATE OR DELETE ON billing_entries FOR EACH ROW
EXECUTE FUNCTION append_only_financial_history();
CREATE TRIGGER financial_audit_immutable
    BEFORE UPDATE OR DELETE ON financial_audit FOR EACH ROW
EXECUTE FUNCTION append_only_financial_history();
ALTER TABLE outbox
    ADD COLUMN event_id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE outbox
    ADD CONSTRAINT outbox_event_unique UNIQUE (event_id);
ALTER TABLE outbox
    ADD COLUMN schema_version int NOT NULL DEFAULT 1;
ALTER TABLE outbox
    ADD COLUMN state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY', 'PUBLISHING', 'SENT', 'DEAD'));
ALTER TABLE outbox
    ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE outbox
    ADD COLUMN available_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE outbox
    ADD COLUMN lease_until timestamptz;
ALTER TABLE outbox
    ADD COLUMN lease_token uuid;
ALTER TABLE outbox
    ADD COLUMN published_at timestamptz;
ALTER TABLE outbox
    ADD COLUMN last_error text;
CREATE INDEX outbox_dispatch ON outbox (state, available_at, id) WHERE state<>'SENT';
CREATE TABLE processed_events
(
    consumer_name text        NOT NULL,
    event_id      uuid        NOT NULL REFERENCES outbox (event_id),
    processed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);
CREATE TABLE consumer_failures
(
    id           bigserial PRIMARY KEY,
    topic        text        NOT NULL,
    partition_id int         NOT NULL,
    offset_id    bigint      NOT NULL,
    body         text        NOT NULL,
    error_code   text        NOT NULL,
    state        text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'REPLAYED', 'DISCARDED')),
    created      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (topic, partition_id, offset_id)
);
CREATE TABLE delivery_audit
(
    id       bigserial PRIMARY KEY,
    actor    text        NOT NULL,
    action   text        NOT NULL,
    event_id uuid,
    details  text        NOT NULL,
    created  timestamptz NOT NULL DEFAULT now()
);
-- Old committed measurements remain reviewable, never automatically charged.
INSERT INTO outbox(event_type, aggregate_id)
SELECT 'SpendCommitted', d.id
FROM decisions d
WHERE d.state = 'COMMITTED'
  AND NOT EXISTS(SELECT 1 FROM outbox o WHERE o.aggregate_id = d.id AND o.event_type = 'SpendCommitted');
