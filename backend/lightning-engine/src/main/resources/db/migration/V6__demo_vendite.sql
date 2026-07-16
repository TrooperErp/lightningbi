CREATE TABLE IF NOT EXISTS ch_lbi_demo_vendite (
    cliente_id UInt32,
    prodotto_id UInt32,
    importo Decimal(18,2),
    _partition_key String
) ENGINE = MergeTree()
ORDER BY (cliente_id, prodotto_id);

INSERT INTO ch_lbi_demo_vendite (cliente_id, prodotto_id, importo, _partition_key) VALUES
    (1, 1, 1000.00, '(1,20260716)'),
    (1, 2, 250.50, '(1,20260716)'),
    (2, 1, 800.00, '(1,20260716)'),
    (2, 3, 1500.75, '(1,20260716)'),
    (3, 2, 300.00, '(1,20260716)'),
    (3, 3, 450.25, '(1,20260716)');