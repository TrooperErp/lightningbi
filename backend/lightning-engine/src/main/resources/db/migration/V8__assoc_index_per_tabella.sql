-- Indice bitmap associativo PER TABELLA (motore a propagazione, fase E).
--
-- Una riga per ogni valore distinto di ogni campo di ogni tabella (occorrenza)
-- di un dataset: "righe" contiene la bitmap dei numeri di riga PERSISTENTI
-- (lbi_rid, assegnati al caricamento) di quella tabella in cui il valore
-- compare. Come in Qlik le tabelle restano separate: lo stato di ogni tabella
-- e di ogni campo si ricava propagando le selezioni lungo le associazioni.
--
--   tabella_id  occorrenza della tabella nel dataset (AreaTabella.id)
--   campo       nome del campo nel dataset (dopo rinomine e qualifiche)
--   valore_id   id del valore nella symbol table del campo
--
-- Ogni dataset si ricostruisce per intero dopo la sincronizzazione delle sue
-- tabelle: si scrive in ch_lbi_idx_staging e poi si sostituisce la partizione
-- del dataset con REPLACE PARTITION, atomico. La vecchia ch_lbi_assoc_bitmap
-- resta fino a quando gli stati leggono il nuovo indice.

CREATE TABLE IF NOT EXISTS ch_lbi_idx
(
    area_id    UUID,
    tabella_id UUID,
    campo      LowCardinality(String),
    valore_id  UInt64,
    righe      AggregateFunction(groupBitmap, UInt32)
)
ENGINE = MergeTree
PARTITION BY toString(area_id)
ORDER BY (area_id, tabella_id, campo, valore_id);

CREATE TABLE IF NOT EXISTS ch_lbi_idx_staging
(
    area_id    UUID,
    tabella_id UUID,
    campo      LowCardinality(String),
    valore_id  UInt64,
    righe      AggregateFunction(groupBitmap, UInt32)
)
ENGINE = MergeTree
PARTITION BY toString(area_id)
ORDER BY (area_id, tabella_id, campo, valore_id);