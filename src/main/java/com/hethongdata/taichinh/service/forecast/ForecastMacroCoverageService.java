package com.hethongdata.taichinh.service.forecast;

import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Coverage describes source availability; it does not manufacture observations or release dates. */
@Service
public class ForecastMacroCoverageService {
    private final JdbcTemplate db;
    private final int yearlyMonths, quarterlyMonths, monthlyMonths, dailyDays;
    public ForecastMacroCoverageService(JdbcTemplate db,
            @Value("${financial.forecast.macro.max-age.yearly-months:24}") int yearlyMonths,
            @Value("${financial.forecast.macro.max-age.quarterly-months:9}") int quarterlyMonths,
            @Value("${financial.forecast.macro.max-age.monthly-months:4}") int monthlyMonths,
            @Value("${financial.forecast.macro.max-age.daily-days:15}") int dailyDays) {
        if (yearlyMonths<1 || quarterlyMonths<1 || monthlyMonths<1 || dailyDays<1)
            throw new IllegalArgumentException("Macro freshness thresholds must be positive");
        this.db=db;this.yearlyMonths=yearlyMonths;this.quarterlyMonths=quarterlyMonths;
        this.monthlyMonths=monthlyMonths;this.dailyDays=dailyDays;
    }

    public List<MacroSeriesCoverage> build(LocalDate asOf, java.sql.Timestamp cutoff, List<SourcePoint> points) {
        return db.query("""
            SELECT s.code,s.frequency,max(o.observation_date) latest_date,
                count(o.id) FILTER (WHERE o.observation_date>=?) window_count
            FROM macro_series s LEFT JOIN macro_observations o ON o.macro_series_id=s.id
                AND o.value IS NOT NULL AND o.observation_date<=? AND o.created_at<?
            WHERE s.is_active AND s.country_code='VNM'
            GROUP BY s.id,s.code,s.frequency ORDER BY s.code
            """, (r,n)-> {
                LocalDate latest=r.getDate("latest_date")==null?null:r.getDate("latest_date").toLocalDate();
                String frequency=r.getString("frequency"), code=r.getString("code");
                long count=r.getLong("window_count");
                long sent=points.stream().filter(p->p.code().equals(code)).count();
                LocalDate oldest= switch(frequency) {
                    case "YEARLY" -> asOf.minusMonths(yearlyMonths);
                    case "QUARTERLY" -> asOf.minusMonths(quarterlyMonths);
                    case "MONTHLY" -> asOf.minusMonths(monthlyMonths);
                    case "DAILY" -> asOf.minusDays(dailyDays);
                    default -> null;
                };
                String status=latest==null?"MISSING":oldest==null?"UNSUPPORTED_FREQUENCY":
                    latest.isBefore(oldest)?"STALE":count==0?"OUTSIDE_WINDOW":sent<count?"TRUNCATED_CONTEXT":"AVAILABLE";
                return new MacroSeriesCoverage(code,frequency,latest,count,sent,status,
                        latest==null,latest!=null && oldest!=null && latest.isBefore(oldest));
            },asOf.minusMonths(12),asOf,cutoff);
    }
}
