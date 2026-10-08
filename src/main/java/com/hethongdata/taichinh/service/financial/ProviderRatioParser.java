package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/** Explicit VNDirect ratio contract; no magnitude-based guessing and no forecast inputs. */
@Component
public class ProviderRatioParser {
    private static final Set<String> CODES = Set.of("PRICE_TO_EARNINGS", "PRICE_TO_BOOK", "EPS_TR",
            "ROAE_TR_AVG5Q", "ROAA_TR_AVG5Q", "DIVIDEND_YIELD");
    private static final Set<String> FRACTIONS = Set.of("ROAE_TR_AVG5Q", "ROAA_TR_AVG5Q", "DIVIDEND_YIELD");
    public record Ratio(String code, LocalDate date, BigDecimal value, BigDecimal sourceValue,
                        String transformation, JsonNode sourceRow) {}

    public List<Ratio> parse(JsonNode payload, String sourceSymbol) {
        if (!"vndirect".equalsIgnoreCase(payload.path("provider").asText())
                || !"ratios".equalsIgnoreCase(payload.path("dataset").asText()))
            throw new IllegalArgumentException("Only the reviewed VNDirect ratios contract is supported");
        if (!sourceSymbol.equalsIgnoreCase(payload.path("symbol").asText())
                || !payload.path("data").isArray() || payload.path("data").isEmpty()
                || payload.path("count").asInt(-1) != payload.path("data").size())
            throw new IllegalArgumentException("Invalid ratio symbol/count/data envelope");
        var out = new ArrayList<Ratio>();
        var identities = new HashSet<String>();
        for (JsonNode row : payload.path("data")) {
            String code = row.path("ratioCode").asText();
            if (!CODES.contains(code) || !sourceSymbol.equalsIgnoreCase(row.path("code").asText()))
                throw new IllegalArgumentException("Unknown ratio code or mismatched security");
            LocalDate date = LocalDate.parse(row.path("reportDate").asText());
            if (date.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))))
                throw new IllegalArgumentException("Ratio report date is in the future");
            if (!row.path("value").isNumber()) throw new IllegalArgumentException("Missing numeric ratio value");
            if (!identities.add(code + ":" + date)) throw new IllegalArgumentException("Duplicate ratio/date in batch");
            BigDecimal original = row.path("value").decimalValue();
            boolean fraction = FRACTIONS.contains(code);
            out.add(new Ratio(code, date, fraction ? original.multiply(new BigDecimal("100")) : original,
                    original, fraction ? "fraction_to_percent:*100" : "identity", row.deepCopy()));
        }
        return List.copyOf(out);
    }
}
