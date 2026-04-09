-- LightningBI Engine - V2: Create Audit Tables
-- Audit Log, Auth Events

-- =====================================================
-- SYSTEM AUDIT LOG (IMMUTABLE)
-- =====================================================
CREATE TABLE IF NOT EXISTS system_audit_log (
    -- Identity
    id UUID DEFAULT generateUUIDv4(),
    timestamp DateTime64(3) DEFAULT now64(3),
    
    -- Session tracking
    session_id String,
    request_id String,
    
    -- User context
    user_id UUID,
    username String,
    role String,
    
    -- Action details
    action String, -- QUERY_EXECUTED, EXPORT_DATA, CONFIG_CHANGE, etc.
    
    -- Query details
    query_hash String, -- SHA256 hash of full query
    query_snippet String, -- First 200 chars for debugging
    view_accessed String,
    filters_json String, -- JSON of applied filters
    
    -- Results
    rows_returned UInt64,
    duration_ms UInt32,
    
    -- Error handling
    error_flag UInt8 DEFAULT 0,
    error_message Nullable(String),
    
    -- Network
    ip_address_anonymized String, -- Last octet removed for GDPR
    user_agent String,
    
    PRIMARY KEY (id)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (timestamp, user_id)
TTL timestamp + INTERVAL 730 DAY -- 2 years retention
SETTINGS index_granularity = 8192;

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_audit_user ON system_audit_log (user_id) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_audit_action ON system_audit_log (action) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_audit_session ON system_audit_log (session_id) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_audit_error ON system_audit_log (error_flag) TYPE set(0) GRANULARITY 1;

-- =====================================================
-- SYSTEM AUTH EVENTS
-- =====================================================
CREATE TABLE IF NOT EXISTS system_auth_events (
    -- Identity
    id UUID DEFAULT generateUUIDv4(),
    timestamp DateTime64(3) DEFAULT now64(3),
    
    -- User context
    user_id Nullable(UUID), -- NULL for failed login attempts
    username String,
    
    -- Event type
    event_type String, -- LOGIN_SUCCESS, LOGIN_FAIL, MFA_CHALLENGE, MFA_SUCCESS, MFA_FAIL, LOGOUT, ACCOUNT_LOCKED, PASSWORD_RESET
    
    -- Network
    ip_address String,
    user_agent String,
    
    -- Additional details
    details_json String, -- JSON with event-specific data
    
    PRIMARY KEY (id)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (timestamp, username)
TTL timestamp + INTERVAL 365 DAY -- 1 year retention
SETTINGS index_granularity = 8192;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_auth_user ON system_auth_events (user_id) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_auth_username ON system_auth_events (username) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_auth_event_type ON system_auth_events (event_type) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_auth_ip ON system_auth_events (ip_address) TYPE bloom_filter GRANULARITY 1;

-- =====================================================
-- MATERIALIZED VIEWS FOR ANALYTICS
-- =====================================================

-- Audit summary per user per day
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_audit_daily_summary
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (date, user_id, action)
AS SELECT
    toDate(timestamp) as date,
    user_id,
    username,
    action,
    count() as action_count,
    sum(rows_returned) as total_rows,
    avg(duration_ms) as avg_duration_ms,
    sum(error_flag) as error_count
FROM system_audit_log
GROUP BY date, user_id, username, action;

-- Failed login attempts per IP per hour (security monitoring)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_failed_logins_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, ip_address, username)
AS SELECT
    toStartOfHour(timestamp) as hour,
    ip_address,
    username,
    count() as attempt_count
FROM system_auth_events
WHERE event_type = 'LOGIN_FAIL'
GROUP BY hour, ip_address, username;

-- =====================================================
-- COMMENTS
-- =====================================================
-- Tables created:
-- - system_audit_log: IMMUTABLE log of all queries and actions
--   * Partitioned by month for efficient archival
--   * TTL 2 years (configurable)
--   * IP anonymized for GDPR compliance
--   * Query hash prevents leaking sensitive SQL
--
-- - system_auth_events: Authentication events log
--   * Login success/fail, MFA, logout, account locks
--   * TTL 1 year
--   * Separate from audit for security analysis
--
-- Materialized views:
-- - mv_audit_daily_summary: Aggregated user activity per day
-- - mv_failed_logins_hourly: Brute force detection data
--
-- IMMUTABILITY ENFORCEMENT:
-- Application layer must enforce INSERT-only
-- No UPDATE or DELETE permissions for non-admin roles