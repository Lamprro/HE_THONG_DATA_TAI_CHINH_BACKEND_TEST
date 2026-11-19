package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.repository.ingestion.DataSourceRepository;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketIndexJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lớp mồi (seed) danh mục chỉ số thị trường (VNINDEX, VN30, HNXINDEX) và cấu hình các Job thu thập
 * và build cho chỉ số vào database; không thuộc luồng thu thập runtime chính.
 */
@Service
public class IndexJobProvisioningService {
    private final MarketIndexJpaRepository indices;
    private final DataSourceRepository sources;
    private final IngestionJobRepository jobs;
    private final ObjectMapper mapper;

    public IndexJobProvisioningService(MarketIndexJpaRepository indices,
            DataSourceRepository sources, IngestionJobRepository jobs, ObjectMapper mapper) {
        this.indices = indices;
        this.sources = sources;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @Transactional
    public int seed() {
        sources.upsert("VNSTOCK", "VnStock", "API", null, "vnstock", false, "UNKNOWN", true);
        seedIndices();
        for (String code : List.of("VNINDEX", "VN30", "HNXINDEX")) {
            collection("VNSTOCK_" + code + "_INDEX_OHLCV_DAILY", code, "INDEX_OHLCV",
                    "0 15 9 * * MON-FRI", 120);
            collection("VNSTOCK_" + code + "_INDEX_MEMBERS_DAILY", code, "INDEX_MEMBERS",
                    "0 20 9 * * MON-FRI", null);
        }
        workflow("INDEX_PRICE_BUILD", "0 */15 * * * *");
        workflow("INDEX_MEMBERSHIP_BUILD", "0 */15 * * * *");
        return 8;
    }

    private void seedIndices() {
        List<IndexDefinition> definitions = List.of(
                new IndexDefinition("VNINDEX", "VN-Index", "HOSE", "Broad HOSE market index", true),
                new IndexDefinition("VN30", "VN30", "HOSE", "HOSE large-cap basket", false),
                new IndexDefinition("HNXINDEX", "HNX-Index", "HNX", "Broad HNX market index", true));
        for (IndexDefinition definition : definitions) {
            indices.findByCodeIgnoreCase(definition.code()).ifPresentOrElse(
                    existing -> existing.update(definition.name(), definition.exchange(), "VND",
                            definition.description(), definition.benchmark(), true),
                    () -> indices.save(MarketIndexEntity.create(definition.code(),
                            definition.name(), definition.exchange(), "VND",
                            definition.description(), definition.benchmark(), true)));
        }
    }

    private void collection(String jobCode, String indexCode, String operation,
                            String cron, Integer lookbackDays) {
        ObjectNode parameters = mapper.createObjectNode()
                .put("operation", operation).put("provider", "vnstock")
                .put("indexCode", indexCode);
        if (lookbackDays != null) {
            parameters.put("lookbackDays", lookbackDays);
            parameters.put("interval", "1D");
        }
        parameters.putObject("parameters");
        upsert(jobCode, "VnStock " + indexCode + " " + operation, cron, parameters);
    }

    private void workflow(String code, String cron) {
        ObjectNode parameters = mapper.createObjectNode().put("workflow", code);
        parameters.putObject("parameters");
        upsert(code, "Build validated market index data: " + code, cron, parameters);
    }

    private void upsert(String code, String name, String cron, ObjectNode parameters) {
        jobs.upsert("VNSTOCK", code, name, "MARKET_INDEX", cron, parameters,
                AppParams.DEFAULT_MAX_RETRIES, AppParams.DEFAULT_INGESTION_TIMEOUT_SECONDS, true);
    }

    private record IndexDefinition(String code, String name, String exchange,
                                   String description, boolean benchmark) {}
}
