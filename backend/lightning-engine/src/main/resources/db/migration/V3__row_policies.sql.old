-- LightningBI Engine - V3: Create Row-Level Security Policies
-- ClickHouse Row Policies for RBAC enforcement at database level

-- =====================================================
-- NOTE: Row policies in ClickHouse require specific users
-- These policies reference ClickHouse database users that must exist
-- Application will use connection pools with these users
-- =====================================================

-- =====================================================
-- CREATE CLICKHOUSE USERS (if not exists)
-- =====================================================
-- These are ClickHouse database users, separate from application users in system_users table
-- Application uses these for connection pooling based on role

-- User for DIRECTION role (full access)
CREATE USER IF NOT EXISTS direction_user IDENTIFIED WITH plaintext_password BY 'direction_dev_pass';

-- User for MANAGEMENT role (row-filtered access)
CREATE USER IF NOT EXISTS management_user IDENTIFIED WITH plaintext_password BY 'management_dev_pass';

-- User for OPERATIONS role (row-filtered access)
CREATE USER IF NOT EXISTS operations_user IDENTIFIED WITH plaintext_password BY 'operations_dev_pass';

-- =====================================================
-- GRANT BASIC PERMISSIONS TO USERS
-- =====================================================

-- DIRECTION: full access to analytics database
GRANT SELECT, INSERT ON analytics.* TO direction_user;
GRANT SELECT ON system.* TO direction_user;

-- MANAGEMENT: access to specific tables
GRANT SELECT ON analytics.system_users TO management_user;
GRANT SELECT ON analytics.system_roles TO management_user;
GRANT SELECT ON analytics.system_permissions TO management_user;
GRANT SELECT ON analytics.system_sessions TO management_user;

-- OPERATIONS: access to specific tables (no audit)
GRANT SELECT ON analytics.system_users TO operations_user;
GRANT SELECT ON analytics.system_sessions TO operations_user;

-- =====================================================
-- ROW POLICIES: DEFAULT DENY ALL
-- =====================================================
-- Start with restrictive default: no access unless explicitly granted

-- Deny all access to system_users by default
CREATE ROW POLICY IF NOT EXISTS deny_all_users ON analytics.system_users
    FOR SELECT
    USING 0
    TO ALL;

-- Deny all access to system_audit_log by default
CREATE ROW POLICY IF NOT EXISTS deny_all_audit ON analytics.system_audit_log
    FOR SELECT
    USING 0
    TO ALL;

-- Deny all access to system_auth_events by default
CREATE ROW POLICY IF NOT EXISTS deny_all_auth_events ON analytics.system_auth_events
    FOR SELECT
    USING 0
    TO ALL;

-- =====================================================
-- ROW POLICIES: DIRECTION (Full Access)
-- =====================================================

-- DIRECTION can see all users
CREATE ROW POLICY IF NOT EXISTS direction_users_policy ON analytics.system_users
    FOR SELECT
    USING 1
    TO direction_user;

-- DIRECTION can see all audit logs
CREATE ROW POLICY IF NOT EXISTS direction_audit_policy ON analytics.system_audit_log
    FOR SELECT
    USING 1
    TO direction_user;

-- DIRECTION can see all auth events
CREATE ROW POLICY IF NOT EXISTS direction_auth_events_policy ON analytics.system_auth_events
    FOR SELECT
    USING 1
    TO direction_user;

-- =====================================================
-- ROW POLICIES: MANAGEMENT (Own Entity Only)
-- =====================================================

-- MANAGEMENT can only see users from their entity
-- Note: This requires passing entity_id context via settings or query
-- For MVP, we'll implement entity filter in application layer
-- Database policy as additional defense layer

CREATE ROW POLICY IF NOT EXISTS management_users_policy ON analytics.system_users
    FOR SELECT
    USING id IN (
        SELECT user_id 
        FROM analytics.system_user_entities 
        WHERE entity_id = getSetting('current_entity_id')
    )
    TO management_user;

-- MANAGEMENT can only see their own audit logs
CREATE ROW POLICY IF NOT EXISTS management_audit_policy ON analytics.system_audit_log
    FOR SELECT
    USING user_id IN (
        SELECT user_id 
        FROM analytics.system_user_entities 
        WHERE entity_id = getSetting('current_entity_id')
    )
    TO management_user;

-- =====================================================
-- ROW POLICIES: OPERATIONS (Own Entity Only, No Audit)
-- =====================================================

-- OPERATIONS can only see users from their entity
CREATE ROW POLICY IF NOT EXISTS operations_users_policy ON analytics.system_users
    FOR SELECT
    USING id IN (
        SELECT user_id 
        FROM analytics.system_user_entities 
        WHERE entity_id = getSetting('current_entity_id')
    )
    TO operations_user;

-- OPERATIONS has NO access to audit logs (deny policy already in place)

-- =====================================================
-- SHOW POLICIES (for verification)
-- =====================================================
-- Run these queries to verify policies are active:
-- SHOW ROW POLICIES;
-- SHOW GRANTS FOR direction_user;
-- SHOW GRANTS FOR management_user;
-- SHOW GRANTS FOR operations_user;

-- =====================================================
-- COMMENTS
-- =====================================================
-- Row-level security implementation:
--
-- 1. Three ClickHouse database users created:
--    - direction_user: full access
--    - management_user: entity-filtered
--    - operations_user: entity-filtered, no audit
--
-- 2. Default deny-all policies protect sensitive tables
--
-- 3. Role-specific policies grant access:
--    - DIRECTION: sees everything (USING 1)
--    - MANAGEMENT: sees only own entity users/audit
--    - OPERATIONS: sees only own entity users, NO audit
--
-- 4. Entity filtering uses getSetting('current_entity_id'):
--    - Application must set this setting when executing queries
--    - Example: SET current_entity_id = 'ENTITY_001';
--    - Enforced at database level, not just application
--
-- 5. Connection pooling strategy:
--    - Application maintains 3 connection pools
--    - Each pool uses appropriate database user
--    - User's application role determines which pool to use
--
-- SECURITY NOTES:
-- - Passwords shown here are DEV only
-- - Production passwords MUST be in environment variables
-- - Consider using ClickHouse Keeper for user management in prod
-- - Row policies are evaluated AFTER table permissions
-- - Policies are cumulative (most permissive wins if multiple apply)
--
-- TESTING:
-- To test policies, connect as specific user:
-- clickhouse-client --user management_user --password management_dev_pass
-- SET current_entity_id = 'test_entity';
-- SELECT * FROM analytics.system_users; -- Should only see filtered users