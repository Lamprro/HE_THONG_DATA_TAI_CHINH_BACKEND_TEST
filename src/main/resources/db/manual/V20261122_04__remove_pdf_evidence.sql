-- Apply after the Java API and Admin UI have stopped using PDF evidence.
BEGIN;
SET LOCAL lock_timeout = '5s';

-- RESTRICT aborts the transaction if another consumer still depends on these objects.
DROP VIEW IF EXISTS public.vw_financial_item_disclosure_review, public.vw_financial_value_bindings RESTRICT;
DROP TABLE IF EXISTS
    public.financial_item_disclosure_review,
    public.financial_value_bindings,
    public.financial_reconciliation_evidence,
    public.financial_disclosure_facts,
    public.financial_disclosure_documents
    RESTRICT;

COMMIT;
