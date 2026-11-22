package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jdbc.market.SupplementaryDataJdbcRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplementaryDataPersistenceService {
    private final SupplementaryDataJdbcRepository repo;
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository raws;
    private final IngestionRunJpaRepository runs;
    private final ObjectMapper mapper;

    public SupplementaryDataPersistenceService(SupplementaryDataJdbcRepository repo, DataVersionJpaRepository versions,
            RawPayloadJpaRepository raws, IngestionRunJpaRepository runs, ObjectMapper mapper) {
        this.repo = repo;
        this.versions = versions;
        this.raws = raws;
        this.runs = runs;
        this.mapper = mapper;
    }

    @Transactional
    public int build(UUID id, String domain, IngestionRunEntity run) {
        if (!Set.of("MARKET_FOREIGN", "SECURITY_REFERENCE").contains(domain))
            throw new IllegalArgumentException("Unsupported supplementary domain");
        var version = versions.findByIdForUpdate(id).orElseThrow();
        if (!"ACTIVE".equals(version.getStatus()) || !domain.equals(version.getDataDomain()))
            throw new IllegalStateException("Inactive supplementary version");
        var payloads = raws.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId());
        if (payloads.isEmpty()) throw new IllegalArgumentException("Missing supplementary payloads");
        int inserted = 0;
        for (var raw : payloads) {
            String rule = "MARKET_FOREIGN".equals(domain) ? "FOREIGN_PAYLOAD_VALID" : "REFERENCE_PAYLOAD_VALID";
            if (repo.countPassedValidationResults(raw.getId(), rule) < 1)
                throw new IllegalArgumentException("Missing supplementary contract validation evidence");
            if (!raw.getPayload().path("provider").asText().equalsIgnoreCase(raw.getDataSource().getProvider()))
                throw new IllegalArgumentException("Supplementary source/provider mismatch");
            String symbol = raw.getSourceSymbol();
            UUID security = repo.findSecurityIdBySymbolForUpdate(symbol);
            if (raw.getSecurityId() != null && !raw.getSecurityId().equals(security))
                throw new IllegalArgumentException("Raw security mismatch");
            if ("MARKET_FOREIGN".equals(domain)) {
                if (!"FOREIGN_TRADES".equals(raw.getEntityType()))
                    throw new IllegalArgumentException("Mixed foreign batch");
                var days = SupplementaryPayloadParser.foreign(raw.getPayload(), symbol);
                var args = new ArrayList<Object[]>();
                for (var day : days)
                    args.add(new Object[]{UUID.randomUUID(), security, day.date(), raw.getDataSource().getId(),
                        raw.getId(), id, day.buy(), day.sell(), Timestamp.from(raw.getFetchedAt()), day.source().toString()});
                int before = repo.countForeignObservationsByRawId(raw.getId());
                repo.batchInsertForeignObservations(args);
                inserted += repo.countForeignObservationsByRawId(raw.getId()) - before;
                repo.reconcileForeignObservations(security, raw.getId());
                repo.syncMarketPriceForeignVolumes(security);
            } else {
                if (!"COMPANY".equals(raw.getEntityType()))
                    throw new IllegalArgumentException("Mixed reference batch");
                var row = SupplementaryPayloadParser.reference(raw.getPayload(), symbol);
                inserted += repo.insertReferenceObservation(
                    UUID.randomUUID(), security, raw.getDataSource().getId(), raw.getId(), id,
                    Timestamp.from(raw.getFetchedAt()),
                    row.hasNonNull("industryName") ? row.path("industryName").asText() : null,
                    row.hasNonNull("outstandingShares") ? row.path("outstandingShares").decimalValue() : null,
                    sourceDate(row, "listedDate"), sourceDate(row, "delistedDate"), row.path("status").asText(null), row.toString());
            }
        }
        run.markBatchSuccess(mapper.valueToTree(Map.of("domain", domain, "inserted", inserted)), Instant.now(), inserted);
        runs.save(run);
        version.markActivated();
        versions.save(version);
        return inserted;
    }

    private static LocalDate sourceDate(com.fasterxml.jackson.databind.JsonNode row, String name) {
        return row.hasNonNull(name) && !row.path(name).asText().isBlank()
            ? LocalDate.parse(row.path(name).asText().substring(0, 10)) : null;
    }

    @Transactional
    public void noWork(IngestionRunEntity run) {
        run.markBatchSuccess(mapper.createObjectNode().put("noWork", true), Instant.now(), 0);
        runs.save(run);
    }
}
