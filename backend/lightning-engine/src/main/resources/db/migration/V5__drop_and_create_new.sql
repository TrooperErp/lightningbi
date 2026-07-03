-- ch_lbi_audit_log
CREATE TABLE IF NOT EXISTS ch_lbi_audit_log AS system_audit_log;
INSERT INTO ch_lbi_audit_log SELECT * FROM system_audit_log;
DROP TABLE system_audit_log;

-- ch_lbi_auth_events
CREATE TABLE IF NOT EXISTS ch_lbi_auth_events AS system_auth_events;
INSERT INTO ch_lbi_auth_events SELECT * FROM system_auth_events;
DROP TABLE system_auth_events;

DROP TABLE IF EXISTS mv_audit_daily_summary;
DROP TABLE IF EXISTS mv_failed_logins_hourly;

CREATE MATERIALIZED VIEW ch_lbi_mv_audit_daily_summary
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (date, user_id, action)
AS SELECT
    toDate(timestamp) as date, user_id, username, action,
    count() as action_count, sum(rows_returned) as total_rows,
    avg(duration_ms) as avg_duration_ms, sum(error_flag) as error_count
FROM ch_lbi_audit_log
GROUP BY date, user_id, username, action;

CREATE MATERIALIZED VIEW ch_lbi_mv_failed_logins_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, ip_address, username)
AS SELECT
    toStartOfHour(timestamp) as hour, ip_address, username, count() as attempt_count
FROM ch_lbi_auth_events
WHERE event_type = 'LOGIN_FAIL'
GROUP BY hour, ip_address, username;

DROP TABLE IF EXISTS system_users;
DROP TABLE IF EXISTS system_roles;
DROP TABLE IF EXISTS system_permissions;
DROP TABLE IF EXISTS system_sessions;
DROP TABLE IF EXISTS system_user_roles;
DROP TABLE IF EXISTS system_user_entities;
DROP TABLE IF EXISTS system_role_permissions;