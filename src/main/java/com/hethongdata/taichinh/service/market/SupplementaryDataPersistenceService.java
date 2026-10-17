package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplementaryDataPersistenceService {
    private final JdbcTemplate db;private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository raws;private final IngestionRunJpaRepository runs;private final ObjectMapper mapper;
    public SupplementaryDataPersistenceService(JdbcTemplate db,DataVersionJpaRepository versions,
            RawPayloadJpaRepository raws,IngestionRunJpaRepository runs,ObjectMapper mapper) {
        this.db=db;this.versions=versions;this.raws=raws;this.runs=runs;this.mapper=mapper;
    }
    @Transactional public int build(UUID id,String domain,IngestionRunEntity run) {
        if(!Set.of("MARKET_FOREIGN","SECURITY_REFERENCE").contains(domain)) throw new IllegalArgumentException("Unsupported supplementary domain");
        var version=versions.findByIdForUpdate(id).orElseThrow();
        if(!"ACTIVE".equals(version.getStatus()) || !domain.equals(version.getDataDomain())) throw new IllegalStateException("Inactive supplementary version");
        var payloads=raws.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId());
        if(payloads.isEmpty()) throw new IllegalArgumentException("Missing supplementary payloads");
        int inserted=0;
        for(var raw:payloads) {
            String rule="MARKET_FOREIGN".equals(domain)?"FOREIGN_PAYLOAD_VALID":"REFERENCE_PAYLOAD_VALID";
            if(db.queryForObject("SELECT count(*) FROM validation_results WHERE raw_payload_id=? AND rule_code=? AND result_status='PASS'",Integer.class,raw.getId(),rule)<1)
                throw new IllegalArgumentException("Missing supplementary contract validation evidence");
            if(!raw.getPayload().path("provider").asText().equalsIgnoreCase(raw.getDataSource().getProvider()))
                throw new IllegalArgumentException("Supplementary source/provider mismatch");
            String symbol=raw.getSourceSymbol();
            UUID security=db.queryForObject("SELECT id FROM securities WHERE upper(symbol)=upper(?) FOR UPDATE",UUID.class,symbol);
            if(raw.getSecurityId()!=null && !raw.getSecurityId().equals(security)) throw new IllegalArgumentException("Raw security mismatch");
            if("MARKET_FOREIGN".equals(domain)) {
                if(!"FOREIGN_TRADES".equals(raw.getEntityType())) throw new IllegalArgumentException("Mixed foreign batch");
                var days=SupplementaryPayloadParser.foreign(raw.getPayload(),symbol);
                var args=new ArrayList<Object[]>();
                for(var day:days) args.add(new Object[]{UUID.randomUUID(),security,day.date(),raw.getDataSource().getId(),
                    raw.getId(),id,day.buy(),day.sell(),Timestamp.from(raw.getFetchedAt()),day.source().toString()});
                int before=db.queryForObject("SELECT count(*) FROM security_foreign_observations WHERE raw_payload_id=?",Integer.class,raw.getId());
                db.batchUpdate("""
INSERT INTO security_foreign_observations(id,security_id,trading_date,data_source_id,raw_payload_id,data_version_id,buy_volume,sell_volume,source_observed_at,source_record)
VALUES(?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(raw_payload_id,security_id,trading_date) DO NOTHING
""",args);
                inserted+=db.queryForObject("SELECT count(*) FROM security_foreign_observations WHERE raw_payload_id=?",Integer.class,raw.getId())-before;
                db.update("""
UPDATE security_foreign_observations f SET is_canonical=false WHERE f.security_id=? AND f.is_canonical
AND EXISTS(SELECT 1 FROM security_foreign_observations n WHERE n.raw_payload_id=? AND n.trading_date=f.trading_date)
""",security,raw.getId());
                db.update("""
WITH winners AS (SELECT f.id,row_number() OVER(PARTITION BY f.trading_date ORDER BY d.is_official DESC,d.priority,d.id,f.source_observed_at DESC,f.created_at DESC,f.id) AS position
FROM security_foreign_observations f JOIN data_sources d ON d.id=f.data_source_id WHERE f.security_id=?
AND EXISTS(SELECT 1 FROM security_foreign_observations n WHERE n.raw_payload_id=? AND n.trading_date=f.trading_date))
UPDATE security_foreign_observations f SET is_canonical=true FROM winners w WHERE w.position=1 AND w.id=f.id
""",security,raw.getId());
                db.update("""
UPDATE market_prices m SET foreign_buy_volume=f.buy_volume,foreign_sell_volume=f.sell_volume,
 foreign_data_source_id=f.data_source_id,foreign_raw_payload_id=f.raw_payload_id,foreign_data_version_id=f.data_version_id
FROM security_foreign_observations f WHERE m.security_id=? AND m.security_id=f.security_id AND m.interval_code='1d'
AND f.is_canonical AND (m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date=f.trading_date
AND (m.foreign_raw_payload_id IS DISTINCT FROM f.raw_payload_id OR m.foreign_buy_volume IS DISTINCT FROM f.buy_volume OR m.foreign_sell_volume IS DISTINCT FROM f.sell_volume)
""",security);
            } else {
                if(!"COMPANY".equals(raw.getEntityType())) throw new IllegalArgumentException("Mixed reference batch");
                var row=SupplementaryPayloadParser.reference(raw.getPayload(),symbol);
                inserted+=db.update("""
INSERT INTO security_reference_observations(id,security_id,data_source_id,raw_payload_id,data_version_id,observed_at,industry_name,outstanding_shares,current_venue_listing_date,delisted_date,listing_status,source_record)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(raw_payload_id) DO NOTHING
""",UUID.randomUUID(),security,raw.getDataSource().getId(),raw.getId(),id,Timestamp.from(raw.getFetchedAt()),
                    row.hasNonNull("industryName")?row.path("industryName").asText():null,
                    row.hasNonNull("outstandingShares")?row.path("outstandingShares").decimalValue():null,
                    sourceDate(row,"listedDate"),sourceDate(row,"delistedDate"),row.path("status").asText(null),row.toString());
            }
        }
        run.markBatchSuccess(mapper.valueToTree(Map.of("domain",domain,"inserted",inserted)),Instant.now(),inserted);
        runs.save(run);version.markActivated();versions.save(version);return inserted;
    }
    private static LocalDate sourceDate(com.fasterxml.jackson.databind.JsonNode row,String name) {
        return row.hasNonNull(name) && !row.path(name).asText().isBlank()?LocalDate.parse(row.path(name).asText().substring(0,10)):null;
    }
    @Transactional public void noWork(IngestionRunEntity run) {run.markBatchSuccess(mapper.createObjectNode().put("noWork",true),Instant.now(),0);runs.save(run);}
}
