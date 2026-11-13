CREATE TABLE orders (
    id         UUID                     PRIMARY KEY,
    partner_id VARCHAR(64)              NOT NULL,
    msisdn     VARCHAR(15)              NOT NULL,
    plan_code  VARCHAR(64)              NOT NULL,
    sim_type   VARCHAR(16)              NOT NULL,
    status     VARCHAR(16)              NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_orders_partner  FOREIGN KEY (partner_id) REFERENCES partners (id),
    CONSTRAINT fk_orders_plan     FOREIGN KEY (plan_code)  REFERENCES plans (code),
    CONSTRAINT ck_orders_sim_type CHECK (sim_type IN ('PHYSICAL', 'ESIM')),
    CONSTRAINT ck_orders_status   CHECK (status IN ('CREATED', 'VALIDATED', 'PROVISIONING', 'ACTIVE', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_orders_partner_created ON orders (partner_id, created_at);
CREATE INDEX ix_orders_partner_msisdn  ON orders (partner_id, msisdn);
