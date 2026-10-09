package com.hethongdata.taichinh.service.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** One atomic commit per validated raw batch; immutable raw/version audit survives corrections. */
@Service
public class MacroWorkflowPersistenceService {
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunJpaRepository runs;
    private final JdbcTemplate db;
    private final MacroPayloadParser parser;
    private final ObjectMapper mapper;

    public MacroWorkflowPersistenceService(
            DataVersionJpaRepository versions,
            RawPayloadJpaRepository rawPayloads,
            IngestionRunJpaRepository runs,
            JdbcTemplate db,
            MacroPayloadParser parser,
            ObjectMapper mapper) {
        this.versions = versions;
        this.rawPayloads = rawPayloads;
        this.runs = runs;
        this.db = db;
        this.parser = parser;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Integer> build(UUID versionId, IngestionRunEntity run) {
        var version = versions.findByIdForUpdate(versionId).orElseThrow();
        if (!"MACRO".equals(version.getDataDomain()) || !"ACTIVE".equals(version.getStatus()))
            throw new IllegalStateException("Macro version is not ACTIVE");
        var payloads =
                rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                        version.getIngestionRunId(), "MACRO_OBSERVATIONS");
        if (payloads.size() != 1
                || rawPayloads.countByIngestionRunId(version.getIngestionRunId()) != 1)
            throw new IllegalArgumentException("Expected one validated macro batch");
        var raw = payloads.getFirst();
        Integer approved =
                db.queryForObject(
                        "SELECT count(*) FROM validation_results WHERE raw_payload_id=? AND"
                            + " rule_code='MACRO_PAYLOAD_VALID' AND result_status='PASS'",
                        Integer.class,
                        raw.getId());
        if (approved == null || approved == 0)
            throw new IllegalStateException(
                    "Macro contract validation audit is required before materialization");
        var batch = parser.parse(raw.getPayload(), raw.getDataSource().getProvider());
        Long sourceId = raw.getDataSource().getId();
        // Serializes first inserts and corrections for one source across different raw versions.
        db.queryForObject(
                "SELECT id FROM data_sources WHERE id=? FOR UPDATE", Long.class, sourceId);
        int inserted = 0, updated = 0, unchanged = 0, stale = 0;
        for (var series : batch.series()) {
            db.update(
                    "INSERT INTO"
                        + " macro_series(id,data_source_id,code,name,country_code,frequency,unit,description,is_active)"
                        + " VALUES (?,?,?,?,?,?,?,?,true) ON CONFLICT(data_source_id,code) DO"
                        + " NOTHING",
                    UUID.randomUUID(),
                    sourceId,
                    series.code(),
                    series.name(),
                    "VNM",
                    series.frequency(),
                    series.unit(),
                    series.description());
            var definition =
                    db.queryForMap(
                            "SELECT id,country_code,frequency,unit FROM macro_series WHERE"
                                + " data_source_id=? AND code=? FOR UPDATE",
                            sourceId,
                            series.code());
            if (!"VNM".equals(definition.get("country_code"))
                    || !series.frequency().equals(definition.get("frequency"))
                    || !series.unit().equals(definition.get("unit")))
                throw new IllegalStateException(
                        "Existing macro definition has incompatible country/frequency/unit");
            UUID seriesId = (UUID) definition.get("id");
            for (var observation : series.observations()) {
                var existing =
                        db.query(
                                "SELECT o.value, r.fetched_at FROM macro_observations o LEFT JOIN"
                                    + " raw_payloads r ON r.id=o.raw_payload_id WHERE"
                                    + " o.macro_series_id=? AND o.observation_date=? FOR UPDATE OF"
                                    + " o",
                                (r, n) ->
                                        new Previous(
                                                r.getBigDecimal(1),
                                                r.getTimestamp(2) == null
                                                        ? null
                                                        : r.getTimestamp(2).toInstant()),
                                seriesId,
                                observation.date());
                if (existing.isEmpty()) {
                    db.update(
                            "INSERT INTO"
                                + " macro_observations(macro_series_id,observation_date,value,raw_payload_id,data_version_id)"
                                + " VALUES (?,?,?,?,?)",
                            seriesId,
                            observation.date(),
                            observation.value(),
                            raw.getId(),
                            versionId);
                    inserted++;
                } else if (existing.getFirst().fetchedAt() != null
                        && existing.getFirst().fetchedAt().isAfter(raw.getFetchedAt())) stale++;
                else if (existing.getFirst().value() != null
                        && existing.getFirst().value().compareTo(observation.value()) == 0)
                    unchanged++;
                else {
                    db.update(
                            "UPDATE macro_observations SET"
                                + " value=?,raw_payload_id=?,data_version_id=?,updated_at=now()"
                                + " WHERE macro_series_id=? AND observation_date=?",
                            observation.value(),
                            raw.getId(),
                            versionId,
                            seriesId,
                            observation.date());
                    updated++;
                }
            }
        }
        var counts =
                Map.of(
                        "observations",
                        batch.count(),
                        "inserted",
                        inserted,
                        "updated",
                        updated,
                        "unchanged",
                        unchanged,
                        "stale",
                        stale);
        run.markWorkflowSuccess(
                mapper.valueToTree(
                        Map.of(
                                "workflow",
                                "MACRO_BUILD",
                                "sourceVersionId",
                                versionId,
                                "counts",
                                counts)),
                Instant.now(),
                batch.count(),
                inserted,
                updated);
        runs.save(run);
        if (inserted + updated == 0)
            version.markRejected(
                    "Macro batch contributed no new/corrected observations; duplicate or stale"
                        + " replay");
        else version.markActivated();
        versions.save(version);
        return counts;
    }

    private record Previous(java.math.BigDecimal value, Instant fetchedAt) {}
}
