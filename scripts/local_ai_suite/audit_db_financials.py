import psycopg2
import pandas as pd
import numpy as np

conn = psycopg2.connect('postgresql://postgres:rootpassword123@192.168.50.12:5432/postgres')

# 1. Duplicates check
print("="*60)
print("1. CHECK DUPLICATES IN FINANCIAL_STATEMENTS (is_current & is_canonical)")
cur = conn.cursor()
cur.execute("""
    SELECT se.symbol, p.period_type, p.end_date, s.statement_type, count(*) as cnt
    FROM financial_statements s
    JOIN financial_periods p ON p.id = s.financial_period_id
    JOIN securities se ON se.id = s.security_id
    WHERE s.is_current = true AND s.is_canonical = true
    GROUP BY se.symbol, p.period_type, p.end_date, s.statement_type
    HAVING count(*) > 1
""")
dup_rows = cur.fetchall()
print(f"Duplicate current/canonical statements: {len(dup_rows)}")
if dup_rows:
    for d in dup_rows[:5]:
        print("  Dup:", d)

# 2. Check total statements and symbols
cur.execute("""
    SELECT se.symbol, s.currency, s.unit_scale, s.report_scope, s.period_basis, count(*) as cnt
    FROM financial_statements s
    JOIN securities se ON se.id = s.security_id
    WHERE s.is_current = true AND s.is_canonical = true
    GROUP BY se.symbol, s.currency, s.unit_scale, s.report_scope, s.period_basis
""")
rows = cur.fetchall()
df_summary = pd.DataFrame(rows, columns=['symbol', 'currency', 'unit_scale', 'report_scope', 'period_basis', 'count'])
print("\n" + "="*60)
print("2. METADATA DISTRIBUTION (currency, unit_scale, report_scope, period_basis)")
print("Symbols with currency is null:", df_summary[df_summary.currency.isna()]['symbol'].nunique())
print("Symbols with currency == 'VND':", df_summary[df_summary.currency == 'VND']['symbol'].nunique())
print("\nAggregated counts by (currency, unit_scale, report_scope, period_basis):")
agg = df_summary.groupby(['currency', 'unit_scale', 'report_scope', 'period_basis'], dropna=False)['count'].sum().reset_index()
print(agg.to_string())

# 3. Magnitude comparison between legacy symbols (currency=VND) and new symbols (currency=NULL)
print("\n" + "="*60)
print("3. MAGNITUDE COMPARISON: LEGACY VS NEW SYMBOLS")
cur.execute("""
    SELECT se.symbol, 
           CASE WHEN s.currency = 'VND' THEN 'LEGACY_VND' ELSE 'NEW_NULL_CURRENCY' END as cohort,
           p.end_date,
           i.item_code,
           i.value
    FROM financial_statements s
    JOIN securities se ON se.id = s.security_id
    JOIN financial_periods p ON p.id = s.financial_period_id
    JOIN financial_statement_items i ON i.financial_statement_id = s.id
    WHERE s.is_current = true AND s.is_canonical = true
      AND i.item_code IN ('TOTAL_ASSETS', 'NET_PROFIT_AFTER_TAX', 'NET_SALES')
      AND p.end_date >= '2023-01-01'
""")
item_rows = cur.fetchall()
df_items = pd.DataFrame(item_rows, columns=['symbol', 'cohort', 'end_date', 'item_code', 'value'])
df_items['value'] = pd.to_numeric(df_items['value'], errors='coerce')

for code in ['TOTAL_ASSETS', 'NET_PROFIT_AFTER_TAX', 'NET_SALES']:
    print(f"\n--- Item: {code} (Periods from 2023 to present) ---")
    sub = df_items[df_items.item_code == code]
    for cohort, g in sub.groupby('cohort'):
        vals = g['value'].dropna()
        print(f"Cohort {cohort:<18}: N={len(vals):<5} Median={vals.median():>18,.0f}  Mean={vals.mean():>20,.0f}  Min={vals.min():>18,.0f}  Max={vals.max():>20,.0f}")

# 4. Check raw payload metadata from VNDIRECT
print("\n" + "="*60)
print("4. CHECK RAW PAYLOAD SAMPLE FOR UNIT & CURRENCY")
cur.execute("""
    SELECT r.id, r.data_source_id, r.payload
    FROM raw_payloads r
    WHERE r.payload::text LIKE '%financial_statements%'
    LIMIT 1
""")
raw_sample = cur.fetchone()
if raw_sample:
    payload = raw_sample[2]
    print("Raw payload top-level keys:", list(payload.keys()) if isinstance(payload, dict) else type(payload))
    if isinstance(payload, dict) and 'data' in payload and len(payload['data']) > 0:
        print("Sample data row from VNDIRECT raw payload:")
        print(payload['data'][0])

conn.close()
