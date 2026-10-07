-- Initial schema. All timestamps are timestamptz (mapped to java.time.Instant).
-- Enum-like columns use varchar + CHECK instead of PostgreSQL ENUM types so they map
-- cleanly with @Enumerated(EnumType.STRING) and are easy to evolve in later migrations.

CREATE TABLE users (
    id            bigserial    PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'ADMIN' CHECK (role IN ('ADMIN')),
    created_at    timestamptz  NOT NULL
);

CREATE TABLE sites (
    id                     bigserial     PRIMARY KEY,
    name                   varchar(100)  NOT NULL,
    url                    varchar(2048) NOT NULL UNIQUE,
    check_interval_seconds integer       NOT NULL CHECK (check_interval_seconds >= 30),
    active                 boolean       NOT NULL DEFAULT true,
    public_visible         boolean       NOT NULL DEFAULT false,
    last_checked_at        timestamptz,
    created_at             timestamptz   NOT NULL,
    updated_at             timestamptz   NOT NULL
);

CREATE TABLE checks (
    id             bigserial    PRIMARY KEY,
    site_id        bigint       NOT NULL REFERENCES sites (id) ON DELETE CASCADE,
    checked_at     timestamptz  NOT NULL,
    up             boolean      NOT NULL,
    status_code    integer,
    latency_ms     integer,
    error_message  varchar(500),
    cert_expires_at timestamptz
);

CREATE INDEX idx_checks_site_checked_at ON checks (site_id, checked_at DESC);

CREATE TABLE alert_rules (
    id        bigserial   PRIMARY KEY,
    site_id   bigint      NOT NULL REFERENCES sites (id) ON DELETE CASCADE,
    type      varchar(30) NOT NULL CHECK (type IN ('DOWN', 'HIGH_LATENCY', 'SSL_EXPIRING')),
    -- Meaning depends on type: consecutive failures (DOWN), milliseconds (HIGH_LATENCY), days (SSL_EXPIRING).
    threshold integer     NOT NULL CHECK (threshold > 0),
    enabled   boolean     NOT NULL DEFAULT true,
    CONSTRAINT uq_alert_rules_site_type UNIQUE (site_id, type)
);

CREATE TABLE incidents (
    id          bigserial    PRIMARY KEY,
    site_id     bigint       NOT NULL REFERENCES sites (id) ON DELETE CASCADE,
    type        varchar(30)  NOT NULL CHECK (type IN ('DOWN', 'HIGH_LATENCY', 'SSL_EXPIRING')),
    opened_at   timestamptz  NOT NULL,
    resolved_at timestamptz,
    details     varchar(500),
    CONSTRAINT ck_incidents_resolved_after_opened CHECK (resolved_at IS NULL OR resolved_at >= opened_at)
);

-- At most ONE open incident per (site, type): the database itself prevents repeated alerts,
-- even if two checks are processed concurrently.
CREATE UNIQUE INDEX uq_incidents_one_open_per_site_type
    ON incidents (site_id, type)
    WHERE resolved_at IS NULL;

CREATE INDEX idx_incidents_site_opened_at ON incidents (site_id, opened_at DESC);
