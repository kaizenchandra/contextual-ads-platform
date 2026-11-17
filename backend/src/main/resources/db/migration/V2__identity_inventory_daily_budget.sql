CREATE TABLE publisher_websites (
 id uuid PRIMARY KEY, owner_name text NOT NULL, domain text NOT NULL,
 verification_token text NOT NULL, challenge_expires timestamptz NOT NULL,
 verified_until timestamptz, created timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_name,domain)
);
-- A claim alone never locks another publisher out. Verification serializes claims per domain.
CREATE TABLE domain_ownership(domain text PRIMARY KEY, website_id uuid NOT NULL UNIQUE REFERENCES publisher_websites(id));
CREATE TABLE publisher_placements (
 id uuid PRIMARY KEY, website_id uuid NOT NULL REFERENCES publisher_websites(id),
 name text NOT NULL CHECK(length(name) BETWEEN 1 AND 100), status text NOT NULL DEFAULT 'ACTIVE'
 CHECK(status IN ('ACTIVE','PAUSED'))
);
CREATE INDEX websites_owner ON publisher_websites(owner_name);
CREATE INDEX placements_website ON publisher_placements(website_id);
ALTER TABLE pages ADD COLUMN website_id uuid REFERENCES publisher_websites(id);
ALTER TABLE pages ADD COLUMN page_url text;
ALTER TABLE campaigns ADD COLUMN daily_budget bigint;
UPDATE campaigns SET daily_budget=budget;
ALTER TABLE campaigns ALTER COLUMN daily_budget SET NOT NULL;
ALTER TABLE campaigns ADD CONSTRAINT positive_daily CHECK(daily_budget>0 AND daily_budget<=budget);
ALTER TABLE campaigns ADD COLUMN pacing text NOT NULL DEFAULT 'ACCELERATED' CHECK(pacing IN ('EVEN','ACCELERATED'));
ALTER TABLE campaigns ADD COLUMN starts_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE campaigns ADD COLUMN ends_at timestamptz NOT NULL DEFAULT (now()+interval '30 days');
ALTER TABLE campaigns ADD CONSTRAINT campaign_window CHECK(ends_at>starts_at);
ALTER TABLE decisions ADD COLUMN budget_day date;
UPDATE decisions SET budget_day=(created AT TIME ZONE 'UTC')::date;
ALTER TABLE decisions ALTER COLUMN budget_day SET NOT NULL;
ALTER TABLE decisions ADD COLUMN placement_id uuid REFERENCES publisher_placements(id);
CREATE TABLE campaign_daily_budgets (
 campaign_id uuid NOT NULL REFERENCES campaigns(id), budget_day date NOT NULL,
 budget_limit bigint NOT NULL CHECK(budget_limit>0), reserved bigint NOT NULL DEFAULT 0 CHECK(reserved>=0),
 spent bigint NOT NULL DEFAULT 0 CHECK(spent>=0), PRIMARY KEY(campaign_id,budget_day),
 CHECK(spent+reserved<=budget_limit)
);
INSERT INTO campaign_daily_budgets(campaign_id,budget_day,budget_limit,reserved,spent)
 SELECT d.campaign_id,d.budget_day,GREATEST(c.daily_budget,sum(CASE WHEN d.state IN ('RESERVED','COMMITTED') THEN d.price ELSE 0 END)),
 sum(CASE WHEN d.state='RESERVED' THEN d.price ELSE 0 END),sum(CASE WHEN d.state='COMMITTED' THEN d.price ELSE 0 END)
 FROM decisions d JOIN campaigns c ON c.id=d.campaign_id GROUP BY d.campaign_id,d.budget_day,c.daily_budget;
ALTER TABLE decisions ADD CONSTRAINT decision_daily_bucket FOREIGN KEY(campaign_id,budget_day) REFERENCES campaign_daily_budgets(campaign_id,budget_day);
CREATE INDEX expired_reservations ON decisions(expires) WHERE state='RESERVED';
