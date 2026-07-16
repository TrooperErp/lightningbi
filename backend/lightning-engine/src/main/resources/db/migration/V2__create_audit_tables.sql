-- LightningBI Engine - V2: Create Audit Tables
-- Audit Log, Auth Events

CREATE TABLE IF NOT EXISTS system_audit_log (
    id UUID DEFAULT generateUUIDv4(),
    event_time DateTime64(3) DEFAULT now64(3),
    session_id String,
    request_id String,
    user_id UUID,
    username String,
    role String,
    action String,
    query_hash String,
    query_snippet String,
    view_accessed String,
    filters_json String,
    rows_returned UInt64,
    duration_ms UInt32,
    error_flag UInt8 DEFAULT 0,
    error_message Nullable(String),
    ip_address_anonymized String,
    user_agent String
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_time)
ORDER BY (event_time, user_id)
TTL event_time + INTERVAL 730 DAY
SETTINGS index_granularity = 8192;

CREATE INDEX IF NOT EXISTS idx_audit_user ON system_audit_log (user_id) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_audit_action ON system_audit_log (action) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_audit_session ON system_audit_log (session_id) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_audit_error ON system_audit_log (error_flag) TYPE set(0) GRANULARITY 1;

CREATE TABLE IF NOT EXISTS system_auth_events (
    id UUID DEFAULT generateUUIDv4(),
    event_time DateTime64(3) DEFAULT now64(3),
    user_id Nullable(UUID),
    username String,
    event_type String,
    ip_address String,
    user_agent String,
    details_json String
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_time)
ORDER BY (event_time, username)
TTL event_time + INTERVAL 365 DAY
SETTINGS index_granularity = 8192;

CREATE INDEX IF NOT EXISTS idx_auth_user ON system_auth_events (user_id) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_auth_username ON system_auth_events (username) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_auth_event_type ON system_auth_events (event_type) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_auth_ip ON system_auth_events (ip_address) TYPE bloom_filter GRANULARITY 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_audit_daily_summary
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (date, user_id, action)
AS SELECT
    toDate(event_time) as date,
    user_id,
    username,
    action,
    count() as action_count,
    sum(rows_returned) as total_rows,
    avg(duration_ms) as avg_duration_ms,
    sum(error_flag) as error_count
FROM system_audit_log
GROUP BY date, user_id, username, action;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_failed_logins_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, ip_address, username)
AS SELECT
    toStartOfHour(event_time) as hour,
    ip_address,
    username,
    count() as attempt_count
FROM system_auth_events
WHERE event_type = 'LOGIN_FAIL'
GROUP BY hour, ip_address, username;