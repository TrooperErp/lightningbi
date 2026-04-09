-- LightningBI Engine - V4: Create Security Views
-- Separate views for different security levels
-- Column-level security: DIRECTION sees all, OPERATIONS sees no economic data

-- =====================================================
-- EXAMPLE FACT TABLE (replace with your actual schema)
-- =====================================================
-- This is a placeholder example - in real implementation, 
-- you'll create views on your actual fact tables (vendite, produzione, etc.)

-- Example fact table structure (commented - replace with real tables)
/*
CREATE TABLE IF NOT EXISTS fatti_vendite (
    id UUID DEFAULT generateUUIDv4(),
    data_documento Date,
    id_azienda String,
    id_cliente String,
    id_articolo String,
    
    -- Production data (visible to all)
    quantita Decimal(18,2),
    prezzo_unitario Decimal(18,2),
    
    -- Economic data (DIRECTION only)
    costo_unitario Decimal(18,2),
    margine_unitario Decimal(18,2),
    margine_percentuale Decimal(5,2),
    
    -- Calculated
    importo_riga Decimal(18,2),
    
    created_at DateTime64(3) DEFAULT now64(3)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(data_documento)
ORDER BY (data_documento, id_azienda, id_cliente)
SETTINGS index_granularity = 8192;
*/

-- =====================================================
-- VIEW: DIRECTION (Full Access - All Columns)
-- =====================================================
-- DIRECTION role sees ALL data including economic information

CREATE VIEW IF NOT EXISTS view_fatti_direction AS
SELECT
    -- All columns available
    id,
    data_documento,
    id_azienda,
    id_cliente,
    id_articolo,
    
    -- Production data
    quantita,
    prezzo_unitario,
    
    -- Economic data (DIRECTION ONLY)
    costo_unitario,
    margine_unitario,
    margine_percentuale,
    
    -- Calculated
    importo_riga,
    created_at
FROM analytics.fatti_vendite; -- Replace with actual table name

-- =====================================================
-- VIEW: OPERATIONS (Limited - No Economic Data)
-- =====================================================
-- OPERATIONS role sees production data only, NO costs/margins

CREATE VIEW IF NOT EXISTS view_fatti_operations AS
SELECT
    -- Identity
    id,
    data_documento,
    id_azienda,
    id_cliente,
    id_articolo,
    
    -- Production data (VISIBLE)
    quantita,
    prezzo_unitario,
    
    -- Economic data EXCLUDED
    -- costo_unitario - NOT VISIBLE
    -- margine_unitario - NOT VISIBLE
    -- margine_percentuale - NOT VISIBLE
    
    -- Calculated (revenue visible, but not margin)
    importo_riga,
    created_at
FROM analytics.fatti_vendite; -- Replace with actual table name

-- =====================================================
-- GRANT PERMISSIONS ON VIEWS
-- =====================================================

-- DIRECTION: access to full view
GRANT SELECT ON analytics.view_fatti_direction TO direction_user;

-- MANAGEMENT: access to full view (will be row-filtered by entity)
GRANT SELECT ON analytics.view_fatti_direction TO management_user;

-- OPERATIONS: access to limited view only (no economic data)
GRANT SELECT ON analytics.view_fatti_operations TO operations_user;

-- Revoke direct table access (force usage of views)
REVOKE SELECT ON analytics.fatti_vendite FROM management_user;
REVOKE SELECT ON analytics.fatti_vendite FROM operations_user;
-- DIRECTION keeps direct access for admin tasks

-- =====================================================
-- ROW POLICIES ON VIEWS (Entity Filtering)
-- =====================================================

-- DIRECTION: sees all entities
CREATE ROW POLICY IF NOT EXISTS direction_view_policy ON analytics.view_fatti_direction
    FOR SELECT
    USING 1
    TO direction_user;

-- MANAGEMENT: sees only own entity
CREATE ROW POLICY IF NOT EXISTS management_view_policy ON analytics.view_fatti_direction
    FOR SELECT
    USING id_azienda = getSetting('current_entity_id')
    TO management_user;

-- OPERATIONS: sees only own entity
CREATE ROW POLICY IF NOT EXISTS operations_view_policy ON analytics.view_fatti_operations
    FOR SELECT
    USING id_azienda = getSetting('current_entity_id')
    TO operations_user;

-- =====================================================
-- EXAMPLE ADDITIONAL VIEWS (Placeholder Templates)
-- =====================================================

-- Template for production data view (operations)
-- CREATE VIEW IF NOT EXISTS view_produzione_operations AS
-- SELECT 
--     id, data, id_azienda, id_linea, id_prodotto,
--     quantita_prodotta, ore_lavoro, efficienza_percentuale,
--     -- NO: costo_manodopera, costo_materiali
--     created_at
-- FROM analytics.fatti_produzione;

-- Template for logistics view (operations)
-- CREATE VIEW IF NOT EXISTS view_logistica_operations AS
-- SELECT
--     id, data_spedizione, id_azienda, id_cliente, id_corriere,
--     colli, peso_kg, volume_m3,
--     -- NO: costo_spedizione, ricavo_spedizione
--     created_at
-- FROM analytics.fatti_spedizioni;

-- =====================================================
-- MATERIALIZED VIEW: Aggregated KPIs (Performance)
-- =====================================================

-- Pre-aggregated daily KPIs for fast dashboard loading
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_kpi_daily_direction
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(data)
ORDER BY (data, id_azienda)
AS SELECT
    toDate(data_documento) as data,
    id_azienda,
    
    -- Counts
    count() as num_vendite,
    uniq(id_cliente) as num_clienti,
    uniq(id_articolo) as num_articoli,
    
    -- Production metrics
    sum(quantita) as quantita_totale,
    sum(importo_riga) as fatturato_totale,
    
    -- Economic metrics (DIRECTION only)
    sum(costo_unitario * quantita) as costo_totale,
    sum(margine_unitario * quantita) as margine_totale,
    avg(margine_percentuale) as margine_medio_percentuale
FROM analytics.fatti_vendite
GROUP BY data, id_azienda;

-- Operations version (no economic data)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_kpi_daily_operations
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(data)
ORDER BY (data, id_azienda)
AS SELECT
    toDate(data_documento) as data,
    id_azienda,
    
    -- Counts
    count() as num_vendite,
    uniq(id_cliente) as num_clienti,
    uniq(id_articolo) as num_articoli,
    
    -- Production metrics only
    sum(quantita) as quantita_totale,
    sum(importo_riga) as fatturato_totale
    
    -- NO economic metrics
FROM analytics.fatti_vendite
GROUP BY data, id_azienda;

-- =====================================================
-- COMMENTS
-- =====================================================
-- Views created for column-level security:
--
-- 1. view_fatti_direction:
--    - Full access to all columns
--    - Includes economic data (costs, margins)
--    - Used by DIRECTION and MANAGEMENT roles
--
-- 2. view_fatti_operations:
--    - Limited columns (no economic data)
--    - Production metrics only
--    - Used by OPERATIONS role
--
-- Security layers:
-- - Column-level: Different views expose different columns
-- - Row-level: Policies filter by entity (getSetting)
-- - Table-level: Direct table access revoked, views only
--
-- Application usage:
-- - QueryBuilder determines which view to use based on user role
-- - DIRECTION/MANAGEMENT → view_fatti_direction
-- - OPERATIONS → view_fatti_operations
-- - Entity filter applied via SET current_entity_id
--
-- Performance optimization:
-- - Materialized views (mv_kpi_daily_*) pre-aggregate common metrics
-- - Separate MV for each security level
-- - Partitioned by month for efficient queries
--
-- IMPLEMENTATION NOTES:
-- - This V4 migration is a TEMPLATE
-- - Replace 'fatti_vendite' with your actual fact tables
-- - Create similar view pairs for each fact table in your schema
-- - Adjust column names to match your data model
-- - Add additional views as needed for your domain
--
-- TESTING:
-- -- As DIRECTION (sees economic data):
-- SET current_entity_id = 'ENTITY_001';
-- SELECT * FROM view_fatti_direction LIMIT 10;
-- 
-- -- As OPERATIONS (no economic data):
-- SET current_entity_id = 'ENTITY_001';
-- SELECT * FROM view_fatti_operations LIMIT 10;
-- -- Should NOT have costo_unitario, margine columns