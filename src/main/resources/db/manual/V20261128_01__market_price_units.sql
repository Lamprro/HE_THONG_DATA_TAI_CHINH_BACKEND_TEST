ALTER TABLE market_prices
    ADD COLUMN IF NOT EXISTS currency varchar(3),
    ADD COLUMN IF NOT EXISTS price_unit varchar(24);
