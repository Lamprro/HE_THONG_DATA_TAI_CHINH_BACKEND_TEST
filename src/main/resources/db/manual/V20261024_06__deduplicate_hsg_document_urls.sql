-- Three records created during the 2026-10-24 backfill encoded parentheses
-- differently from the already stored issuer URL. Keep the earlier document;
-- ingestion raw payloads and all financial values remain unchanged.
DELETE FROM financial_disclosure_documents duplicate
USING financial_disclosure_documents original
WHERE duplicate.symbol='HSG' AND original.symbol='HSG'
  AND duplicate.document_id IN (
    '6afc58d70015f59d6d7a4fbdb723635d6129672f8a4da5129ced5bb163c5df92',
    '2df74985f7708317cf38ff9c5687a4cd07e840d50e7cc39df301920718205a6a',
    'f48066e2267c69453fc736462cb4e47a903a96692f7cf53b9093731262ed3cc4',
    '80be9fa7c736de6bc7ceb237331feccca6ecf28554b3affb8912f12ecd3a9ceb',
    'ab14755b2bcbfb4fe02277e30b87dd4914e76dcdae36101ec4a7213e58811940',
    '50a9996f969f9c6ccafe712cdb65f02733adf9af96e6d59a981394206a216e4f')
  AND duplicate.metadata->>'document_url' LIKE '%\%28%' ESCAPE '\'
  AND original.metadata->>'document_url' NOT LIKE '%\%28%' ESCAPE '\'
  AND replace(replace(duplicate.metadata->>'document_url','%28','('),'%29',')')
      = original.metadata->>'document_url'
  AND duplicate.period_end=original.period_end
  AND duplicate.published_on=original.published_on
  AND duplicate.report_scope=original.report_scope
  AND duplicate.observed_at>=timestamptz '2026-10-24 00:00:00+07';
