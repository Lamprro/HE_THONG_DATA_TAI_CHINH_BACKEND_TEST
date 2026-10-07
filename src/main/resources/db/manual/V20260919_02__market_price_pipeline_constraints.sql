-- Review and run manually. The application uses spring.jpa.hibernate.ddl-auto=none.
-- This script never deletes or rewrites market data.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM market_prices
        GROUP BY security_id, price_timestamp, interval_code, data_source_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'market_prices contains duplicate source natural keys; reconcile first';
    END IF;
    IF EXISTS (
        SELECT 1 FROM market_prices WHERE is_canonical = true
        GROUP BY security_id, price_timestamp, interval_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'market_prices contains multiple canonical rows in one bucket';
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_market_prices_source_bar
    ON market_prices (security_id, price_timestamp, interval_code, data_source_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_market_prices_canonical_bar
    ON market_prices (security_id, price_timestamp, interval_code)
    WHERE is_canonical = true;

CREATE INDEX IF NOT EXISTS ix_market_prices_security_time
    ON market_prices (security_id, price_timestamp DESC, interval_code);
