-- Apply manually to the existing PostgreSQL schema after reviewing the duplicate checks.
-- The application uses ddl-auto=none and does not run SQL migrations on startup.
BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM index_prices
        GROUP BY market_index_id, price_timestamp, interval_code, data_source_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'index_prices contains duplicate source natural keys; reconcile before creating the unique index';
    END IF;
    IF EXISTS (
        SELECT 1 FROM index_prices WHERE is_canonical = true
        GROUP BY market_index_id, price_timestamp, interval_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'index_prices contains multiple canonical rows in one bucket';
    END IF;
    IF EXISTS (
        SELECT 1 FROM security_index_memberships WHERE effective_to IS NULL
        GROUP BY market_index_id, security_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'security_index_memberships contains multiple open rows for one index/security';
    END IF;
    IF EXISTS (
        SELECT 1 FROM security_index_memberships
        GROUP BY market_index_id, security_id, effective_from
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'security_index_memberships contains duplicate period start dates';
    END IF;
    IF EXISTS (
        SELECT 1 FROM security_index_memberships
        WHERE effective_to IS NOT NULL AND effective_to < effective_from
    ) THEN
        RAISE EXCEPTION 'security_index_memberships contains inverted periods';
    END IF;
    IF EXISTS (
        SELECT 1 FROM security_index_memberships a
        JOIN security_index_memberships b ON a.id < b.id
          AND a.market_index_id = b.market_index_id
          AND a.security_id = b.security_id
          AND daterange(a.effective_from, a.effective_to, '[]')
              && daterange(b.effective_from, b.effective_to, '[]')
    ) THEN
        RAISE EXCEPTION 'security_index_memberships contains overlapping periods';
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_index_prices_source_bar
    ON index_prices (market_index_id, price_timestamp, interval_code, data_source_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_index_prices_canonical_bar
    ON index_prices (market_index_id, price_timestamp, interval_code)
    WHERE is_canonical = true;

CREATE UNIQUE INDEX IF NOT EXISTS uq_security_index_memberships_open
    ON security_index_memberships (market_index_id, security_id)
    WHERE effective_to IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_security_index_memberships_period_start
    ON security_index_memberships (market_index_id, security_id, effective_from);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_security_index_memberships_period'
                     AND conrelid = 'security_index_memberships'::regclass) THEN
        ALTER TABLE security_index_memberships
            ADD CONSTRAINT ck_security_index_memberships_period
            CHECK (effective_to IS NULL OR effective_to >= effective_from);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_index_prices_index_time
    ON index_prices (market_index_id, price_timestamp DESC, interval_code);

CREATE INDEX IF NOT EXISTS ix_security_index_memberships_asof
    ON security_index_memberships (market_index_id, effective_from, effective_to);

COMMIT;
