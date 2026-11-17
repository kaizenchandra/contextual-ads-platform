ALTER TABLE decisions ADD COLUMN publisher_subject text;
UPDATE decisions d SET publisher_subject=p.owner_name FROM pages p WHERE p.id=d.page_id;
ALTER TABLE decisions ADD COLUMN publisher_share_bps int NOT NULL DEFAULT 7000 CHECK(publisher_share_bps BETWEEN 0 AND 10000);
CREATE TABLE financial_accounts(kind text NOT NULL CHECK(kind IN ('ADVERTISER','PUBLISHER')),subject text NOT NULL,PRIMARY KEY(kind,subject));
CREATE TABLE invoice_batches(id uuid PRIMARY KEY,advertiser text NOT NULL,request_key text NOT NULL,created timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(advertiser,request_key));
CREATE TABLE invoices(id uuid PRIMARY KEY,document_number bigserial UNIQUE NOT NULL,batch_id uuid NOT NULL REFERENCES invoice_batches(id),advertiser text NOT NULL,kind text NOT NULL CHECK(kind IN ('INVOICE','CREDIT_NOTE')),currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'),total bigint NOT NULL,issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),due_at timestamptz NOT NULL DEFAULT (clock_timestamp()+interval '30 days'),CHECK((kind='INVOICE' AND total>0) OR (kind='CREDIT_NOTE' AND total<0)));
CREATE INDEX invoices_owner ON invoices(advertiser,document_number);
CREATE TABLE invoice_lines(id uuid PRIMARY KEY,invoice_id uuid NOT NULL REFERENCES invoices(id),billing_entry_id uuid UNIQUE NOT NULL REFERENCES billing_entries(id),description text NOT NULL,amount bigint NOT NULL CHECK(amount<>0),original_line_id uuid REFERENCES invoice_lines(id));
CREATE INDEX invoice_lines_document ON invoice_lines(invoice_id);
CREATE TABLE cash_records(id uuid PRIMARY KEY,advertiser text NOT NULL,source text NOT NULL,external_reference text NOT NULL,direction text NOT NULL CHECK(direction IN ('RECEIPT','RETURN')),amount bigint NOT NULL CHECK(amount>0),currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'),original_receipt_id uuid REFERENCES cash_records(id),occurred_at timestamptz NOT NULL,created timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(source,external_reference),CHECK((direction='RECEIPT' AND original_receipt_id IS NULL) OR (direction='RETURN' AND original_receipt_id IS NOT NULL)));
CREATE TABLE cash_allocations(cash_id uuid PRIMARY KEY REFERENCES cash_records(id),invoice_id uuid REFERENCES invoices(id),amount bigint NOT NULL CHECK(amount<>0),created timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX cash_invoice ON cash_allocations(invoice_id);
CREATE TABLE publisher_revenue(id uuid PRIMARY KEY,billing_entry_id uuid UNIQUE NOT NULL REFERENCES billing_entries(id),decision_id uuid NOT NULL REFERENCES decisions(id),publisher text NOT NULL,share_bps int NOT NULL CHECK(share_bps BETWEEN 0 AND 10000),amount bigint NOT NULL,currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'),created timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX publisher_revenue_owner ON publisher_revenue(publisher,created);
CREATE TABLE settlements(id uuid PRIMARY KEY,publisher text NOT NULL,request_key text NOT NULL,amount bigint NOT NULL CHECK(amount>0),currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'),created timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(publisher,request_key));
CREATE TABLE settlement_states(settlement_id uuid PRIMARY KEY REFERENCES settlements(id),status text NOT NULL CHECK(status IN ('RESERVED','SUBMITTED','PAID','FAILED','CANCELLED')),updated timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE payout_instructions(settlement_id uuid PRIMARY KEY REFERENCES settlements(id),idempotency_key uuid UNIQUE NOT NULL,created timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE payout_evidence(settlement_id uuid PRIMARY KEY REFERENCES settlements(id),outcome text NOT NULL CHECK(outcome IN ('PAID','FAILED')),bank_reference text UNIQUE NOT NULL,amount bigint NOT NULL CHECK(amount>0),currency char(3) NOT NULL CHECK(currency='USD'),created timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE treasury_audit(id bigserial PRIMARY KEY,entity_id uuid NOT NULL,actor text NOT NULL,action text NOT NULL,reason text NOT NULL,created timestamptz NOT NULL DEFAULT clock_timestamp());
DO $$ DECLARE t text; BEGIN FOREACH t IN ARRAY ARRAY['invoice_batches','invoices','invoice_lines','cash_records','cash_allocations','publisher_revenue','settlements','payout_instructions','payout_evidence','treasury_audit'] LOOP EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION append_only_financial_history()',t||'_immutable',t); END LOOP; END $$;
CREATE VIEW invoice_balances AS
 SELECT i.*,
 coalesce((SELECT sum(cl.amount) FROM invoice_lines ol JOIN invoice_lines cl ON cl.original_line_id=ol.id WHERE ol.invoice_id=i.id),0) AS credits,
 coalesce((SELECT sum(a.amount) FROM cash_allocations a WHERE a.invoice_id=i.id),0) AS paid,
 i.total+coalesce((SELECT sum(cl.amount) FROM invoice_lines ol JOIN invoice_lines cl ON cl.original_line_id=ol.id WHERE ol.invoice_id=i.id),0) AS net_due
 FROM invoices i;
ALTER TABLE outbox ADD COLUMN aggregate_type text NOT NULL DEFAULT 'DECISION' CHECK(aggregate_type IN ('DECISION','INVOICE','CASH','SETTLEMENT'));
CREATE TABLE treasury_notifications(event_id uuid PRIMARY KEY REFERENCES outbox(event_id),entity_id uuid NOT NULL,entity_type text NOT NULL,event_type text NOT NULL,created timestamptz NOT NULL DEFAULT clock_timestamp());
-- Backfill existing validated charges and exact compensating credits. Never infer collection from charges.
INSERT INTO publisher_revenue(id,billing_entry_id,decision_id,publisher,share_bps,amount)
SELECT gen_random_uuid(),b.id,b.decision_id,d.publisher_subject,d.publisher_share_bps,
CASE WHEN b.kind='CHARGE' THEN floor(b.amount::numeric*d.publisher_share_bps/10000)::bigint ELSE 0 END
FROM billing_entries b JOIN decisions d ON d.id=b.decision_id WHERE b.kind='CHARGE' AND d.publisher_subject IS NOT NULL;
INSERT INTO publisher_revenue(id,billing_entry_id,decision_id,publisher,share_bps,amount)
SELECT gen_random_uuid(),b.id,b.decision_id,r.publisher,r.share_bps,-r.amount
FROM billing_entries b JOIN billing_entries c ON c.decision_id=b.decision_id AND c.kind='CHARGE' JOIN publisher_revenue r ON r.billing_entry_id=c.id WHERE b.kind='REVERSAL';
