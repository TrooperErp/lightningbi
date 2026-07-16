-- LightningBI Engine - V1: Create System Tables
-- Users, Roles, Permissions, Sessions

-- =====================================================
-- SYSTEM USERS
-- =====================================================
CREATE TABLE IF NOT EXISTS system_users (
    id UUID DEFAULT generateUUIDv4(),
    username String,
    email String,
    password_hash String,
    last_login Nullable(DateTime64(3)),
    failed_attempts UInt8 DEFAULT 0,
    locked_until Nullable(DateTime64(3)),
    mfa_enabled UInt8 DEFAULT 0,
    mfa_secret Nullable(String),
    recovery_codes_hash Nullable(String),
    active UInt8 DEFAULT 1,
    created_at DateTime64(3) DEFAULT now64(3),
    updated_at DateTime64(3) DEFAULT now64(3),
    PRIMARY KEY (id)
) ENGINE = MergeTree()
ORDER BY (id)
SETTINGS index_granularity = 8192;

CREATE INDEX IF NOT EXISTS idx_users_username ON system_users (username) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_users_email ON system_users (email) TYPE bloom_filter GRANULARITY 1;

-- =====================================================
-- SYSTEM ROLES
-- =====================================================
CREATE TABLE IF NOT EXISTS system_roles (
    id UUID DEFAULT generateUUIDv4(),
    name String,
    description String,
    created_at DateTime64(3) DEFAULT now64(3),
    PRIMARY KEY (id)
) ENGINE = MergeTree()
ORDER BY (id)
SETTINGS index_granularity = 8192;

-- =====================================================
-- SYSTEM PERMISSIONS
-- =====================================================
CREATE TABLE IF NOT EXISTS system_permissions (
    id UUID DEFAULT generateUUIDv4(),
    code String,
    description String,
    category String,
    created_at DateTime64(3) DEFAULT now64(3),
    PRIMARY KEY (id)
) ENGINE = MergeTree()
ORDER BY (id)
SETTINGS index_granularity = 8192;

CREATE INDEX IF NOT EXISTS idx_permissions_code ON system_permissions (code) TYPE bloom_filter GRANULARITY 1;

-- =====================================================
-- SYSTEM ROLE PERMISSIONS (many-to-many)
-- =====================================================
CREATE TABLE IF NOT EXISTS system_role_permissions (
    role_id UUID,
    permission_id UUID,
    assigned_at DateTime64(3) DEFAULT now64(3),
    PRIMARY KEY (role_id, permission_id)
) ENGINE = MergeTree()
ORDER BY (role_id, permission_id)
SETTINGS index_granularity = 8192;

-- =====================================================
-- SYSTEM USER ROLES (many-to-many)
-- =====================================================
CREATE TABLE IF NOT EXISTS system_user_roles (
    user_id UUID,
    role_id UUID,
    assigned_at DateTime64(3) DEFAULT now64(3),
    PRIMARY KEY (user_id, role_id)
) ENGINE = MergeTree()
ORDER BY (user_id, role_id)
SETTINGS index_granularity = 8192;

-- =====================================================
-- SYSTEM USER ENTITIES (many-to-many for multi-tenant)
-- =====================================================
CREATE TABLE IF NOT EXISTS system_user_entities (
    user_id UUID,
    entity_id String,
    assigned_at DateTime64(3) DEFAULT now64(3),
    PRIMARY KEY (user_id, entity_id)
) ENGINE = MergeTree()
ORDER BY (user_id, entity_id)
SETTINGS index_granularity = 8192;

-- =====================================================
-- SYSTEM SESSIONS
-- =====================================================
CREATE TABLE IF NOT EXISTS system_sessions (
    session_id String,
    user_id UUID,
    role_id UUID,
    ip_address String,
    user_agent String,
    created_at DateTime64(3) DEFAULT now64(3),
    expires_at DateTime64(3),
    last_activity DateTime64(3) DEFAULT now64(3),
    revoked UInt8 DEFAULT 0,
    PRIMARY KEY (session_id)
) ENGINE = MergeTree()
ORDER BY (session_id)
SETTINGS index_granularity = 8192;

CREATE INDEX IF NOT EXISTS idx_sessions_user ON system_sessions (user_id) TYPE bloom_filter GRANULARITY 1;
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON system_sessions (expires_at) TYPE minmax GRANULARITY 1;

-- =====================================================
-- INSERT DEFAULT ROLES
-- =====================================================
INSERT INTO system_roles (id, name, description) VALUES
    (generateUUIDv4(), 'DIRECTION', 'Direction - complete access'),
    (generateUUIDv4(), 'MANAGEMENT', 'Management - accesso dati economici propria entità'),
    (generateUUIDv4(), 'OPERATIONS', 'Operations - accesso dati produttivi propria entità');

-- =====================================================
-- INSERT DEFAULT PERMISSIONS
-- =====================================================
INSERT INTO system_permissions (id, code, description, category) VALUES
    (generateUUIDv4(), 'VIEW_ALL_ENTITIES', 'Visualizza tutte le entità', 'DATA_ACCESS'),
    (generateUUIDv4(), 'VIEW_OWN_ENTITY', 'Visualizza solo propria entità', 'DATA_ACCESS'),
    (generateUUIDv4(), 'VIEW_ECONOMIC_DATA', 'Visualizza dati economici (costi, margini)', 'DATA_ACCESS'),
    (generateUUIDv4(), 'VIEW_PRODUCTION_DATA', 'Visualizza dati produttivi', 'DATA_ACCESS'),
    (generateUUIDv4(), 'EXPORT_DATA', 'Esporta dati', 'EXPORT'),
    (generateUUIDv4(), 'EXPORT_UNLIMITED', 'Esportazioni illimitate', 'EXPORT'),
    (generateUUIDv4(), 'MANAGE_USERS', 'Gestione utenti', 'ADMIN'),
    (generateUUIDv4(), 'MANAGE_ROLES', 'Gestione ruoli', 'ADMIN'),
    (generateUUIDv4(), 'VIEW_AUDIT_LOG', 'Visualizza audit log', 'ADMIN'),
    (generateUUIDv4(), 'SYSTEM_CONFIG', 'Configurazione sistema', 'ADMIN');

-- =====================================================
-- ASSIGN PERMISSIONS TO ROLES
-- =====================================================

INSERT INTO system_role_permissions (role_id, permission_id)
SELECT 
    r.id as role_id,
    p.id as permission_id
FROM system_roles r
CROSS JOIN system_permissions p
WHERE r.name = 'DIRECTION';

INSERT INTO system_role_permissions (role_id, permission_id)
SELECT 
    r.id as role_id,
    p.id as permission_id
FROM system_roles r
CROSS JOIN system_permissions p
WHERE r.name = 'MANAGEMENT'
  AND p.code IN ('VIEW_OWN_ENTITY', 'VIEW_ECONOMIC_DATA', 'VIEW_PRODUCTION_DATA', 'EXPORT_DATA');

INSERT INTO system_role_permissions (role_id, permission_id)
SELECT 
    r.id as role_id,
    p.id as permission_id
FROM system_roles r
CROSS JOIN system_permissions p
WHERE r.name = 'OPERATIONS'
  AND p.code IN ('VIEW_OWN_ENTITY', 'VIEW_PRODUCTION_DATA', 'EXPORT_DATA');