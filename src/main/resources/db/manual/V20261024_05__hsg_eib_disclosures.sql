ALTER TABLE financial_disclosure_documents DROP CONSTRAINT IF EXISTS disclosure_registered_issuer;
ALTER TABLE financial_disclosure_documents ADD CONSTRAINT disclosure_registered_issuer CHECK
 ((source_code,symbol) IN (('FPT_IR','FPT'),('HPG_IR','HPG'),('VNM_IR','VNM'),('ACB_IR','ACB'),('HSG_IR','HSG'),('EIB_IR','EIB')));
INSERT INTO data_sources(code,name,source_type,base_url,provider,priority,is_official,license_status,is_active,created_at,updated_at)
VALUES ('HSG_IR','Hoa Sen investor disclosures','API','https://hoasengroup.vn','hsg-ir',100,true,'UNKNOWN',true,now(),now()),
       ('EIB_IR','Eximbank investor disclosures','API','https://media.eximbank.com.vn','eib-ir',100,true,'UNKNOWN',true,now(),now())
ON CONFLICT(code) DO NOTHING;
