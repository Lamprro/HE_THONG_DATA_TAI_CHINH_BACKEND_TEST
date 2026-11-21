package com.hethongdata.taichinh.service.localai;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RankMarketFeaturesTests {
    @Test
    void ranksUseAverageTiesAndIgnoreMissingFeatureValues() {
        var values = Map.of("AAA", Map.of("x", 8d), "BBB", Map.of("x", 8d),
                            "CCC", Map.of("x", 2d), "DDD", Map.<String,Double>of());
        var ranks = RankMarketFeatures.percentiles(values, List.of("x"));
        assertEquals(1.0 / 3, ranks.get("CCC").get("x_rk"));
        assertEquals(2.5 / 3, ranks.get("AAA").get("x_rk"));
        assertEquals(ranks.get("AAA"), ranks.get("BBB"));
        assertNull(ranks.get("DDD").get("x_rk"));
    }

    @Test
    void rejectsFutureSourceBeforeComputingRanks() {
        var cutoff = LocalDate.of(2026, 9, 30);
        var future = new QuarterlyMarketFeatures.Price("1", UUID.randomUUID(), "AAA", "VNDIRECT",
                cutoff.plusDays(1), 10, 10, 10, 10, 10, 100, "v1");
        var error = assertThrows(IllegalArgumentException.class,
                () -> RankMarketFeatures.build(List.of(future), cutoff, "quarter"));
        assertEquals("FUTURE_SOURCE_ROW", error.getMessage());
    }
}
