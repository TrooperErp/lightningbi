-- Indice bitmap per il calcolo degli stati associativi (verde/grigio).
--
-- Una riga per ogni valore distinto di ogni dimensione di ogni Area:
-- "righe" contiene la bitmap dei numeri di riga della tabella fatti in
-- cui quel valore compare. Gli stati si calcolano con operazioni
-- bitmap (OR dentro una dimensione, AND tra dimensioni, cardinalita'
-- dell'intersezione per ogni valore) invece che con una SELECT DISTINCT
-- per dimensione.
--
-- I numeri di riga NON sono salvati nella tabella fatti: vengono
-- assegnati al volo durante la ricostruzione, con un'unica scansione
-- della tabella fatti, cosi' sono coerenti tra tutte le bitmap della
-- stessa ricostruzione. L'indice di un'area si ricostruisce per intero
-- dopo ogni sincronizzazione (piena o incrementale), prima del bump
-- della dataVersion.
--
-- Partizionato per area (come stringa, cosi' la partizione si indirizza
-- direttamente col valore dell'UUID): la ricostruzione di un'area
-- scrive in ch_lbi_assoc_bitmap_staging e poi sostituisce la partizione
-- nella tabella principale con REPLACE PARTITION, operazione atomica.
-- Non esiste quindi un momento in cui l'indice dell'area e' vuoto o
-- parziale mentre un utente sta interrogando.

CREATE TABLE IF NOT EXISTS ch_lbi_assoc_bitmap
(
    area_id       UUID,
    dimensione_id UUID,
    valore_id     Int64,
    righe         AggregateFunction(groupBitmap, UInt32)
)
ENGINE = MergeTree
PARTITION BY toString(area_id)
ORDER BY (area_id, dimensione_id, valore_id);

CREATE TABLE IF NOT EXISTS ch_lbi_assoc_bitmap_staging
(
    area_id       UUID,
    dimensione_id UUID,
    valore_id     Int64,
    righe         AggregateFunction(groupBitmap, UInt32)
)
ENGINE = MergeTree
PARTITION BY toString(area_id)
ORDER BY (area_id, dimensione_id, valore_id);