CREATE TABLE partners (
    id         VARCHAR(64)              PRIMARY KEY,
    name       VARCHAR(200)             NOT NULL,
    active     BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE plans (
    code   VARCHAR(64)  PRIMARY KEY,
    name   VARCHAR(200) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO partners (id, name, active, created_at) VALUES
    ('partner-a',         'Partner A',         TRUE,  CURRENT_TIMESTAMP),
    ('partner-b',         'Partner B',         TRUE,  CURRENT_TIMESTAMP),
    ('partner-suspended', 'Suspended Partner', FALSE, CURRENT_TIMESTAMP);

INSERT INTO plans (code, name, active) VALUES
    ('BASIC_5G',  'Basic 5G',  TRUE),
    ('UNLIMITED', 'Unlimited', TRUE),
    ('LEGACY_3G', 'Legacy 3G', FALSE);
