ALTER TABLE financial_disclosure_documents DROP CONSTRAINT IF EXISTS disclosure_registered_issuer;
ALTER TABLE financial_disclosure_documents ADD CONSTRAINT disclosure_registered_issuer CHECK
 ((source_code,symbol) IN (('FPT_IR','FPT'),('HPG_IR','HPG'),('VNM_IR','VNM'),('ACB_IR','ACB')));
INSERT INTO data_sources(code,name,source_type,base_url,provider,priority,is_official,license_status,is_active,created_at,updated_at)
VALUES ('ACB_IR','ACB investor disclosures','API','https://acb.com.vn','acb-ir',100,true,'UNKNOWN',true,now(),now())
ON CONFLICT(code) DO NOTHING;
