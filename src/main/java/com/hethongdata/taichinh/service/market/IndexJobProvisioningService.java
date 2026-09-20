package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.repository.ingestion.DataSourceRepository;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Provisions index jobs without creating security collection jobs. Cron is evaluated in UTC. */
@Service
public class IndexJobProvisioningService {
    private final MarketIndexCatalogService indices;
    private final DataSourceRepository sources;
    private final IngestionJobRepository jobs;
    private final ObjectMapper mapper;

    public IndexJobProvisioningService(MarketIndexCatalogService indices,
            DataSourceRepository sources, IngestionJobRepository jobs, ObjectMapper mapper) {
        this.indices = indices;
        this.sources = sources;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @Transactional
    public int seed() {
        sources.upsert("VNSTOCK", "VnStock", "API", null, "vnstock", false, "UNKNOWN", true);
        indices.seed();
        for (String code : List.of("VNINDEX", "VN30", "HNXINDEX")) {
            collection("VNSTOCK_" + code + "_INDEX_OHLCV_DAILY", code, "INDEX_OHLCV",
                    "0 15 9 * * MON-FRI", 7);
            collection("VNSTOCK_" + code + "_INDEX_MEMBERS_DAILY", code, "INDEX_MEMBERS",
                    "0 20 9 * * MON-FRI", null);
        }
        workflow("INDEX_PRICE_BUILD", "0 */15 * * * *");
        workflow("INDEX_MEMBERSHIP_BUILD", "0 */15 * * * *");
        return 8;
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
}
