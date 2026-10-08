package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.SourcePoint;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.DerivedMetric;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic ratios from a single canonical actual statement; never calls a model. */
@Service
public class HistoricalFinancialMetricService {
    private final JdbcTemplate db;
    private final FinancialRatioCalculator balance;
    private final ObjectMapper mapper;
    private final ChecksumService hashes;
    public HistoricalFinancialMetricService(JdbcTemplate db,FinancialRatioCalculator balance,
            ObjectMapper mapper,ChecksumService hashes) {
        this.db=db;this.balance=balance;this.mapper=mapper;this.hashes=hashes;
    }
    public record Request(String symbol,LocalDate startDate,LocalDate endDate) {}
    public record Result(int sourceStatements,int inserted,int unchanged) {}
    private record Actual(UUID statement,UUID company,UUID security,UUID period,Long source,
            UUID raw,UUID version,String type,String scope,SourcePoint point) {}

    @Transactional
    public Result calculate(Request request) {
        if (request.symbol()==null || request.symbol().isBlank() || request.startDate()==null
                || request.endDate()==null || request.startDate().isAfter(request.endDate())
                || request.endDate().isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))))
            throw new IllegalArgumentException("Choose a security and a valid actual-data date range");
        var securities=db.queryForList("SELECT id,company_id FROM securities WHERE upper(symbol)=upper(?)",request.symbol());
        if (securities.size()!=1) throw new IllegalArgumentException("Unknown security");
        UUID security=(UUID)securities.getFirst().get("id");
        UUID company=(UUID)securities.getFirst().get("company_id");
        db.queryForList("SELECT id FROM companies WHERE id=? FOR UPDATE",company);
        var rows=db.query("""
SELECT s.id,s.company_id,s.security_id,s.financial_period_id,s.data_source_id,s.raw_payload_id,
    s.data_version_id,s.statement_type,s.report_scope,i.id,i.item_code,i.value*s.unit_scale AS amount,
    s.currency,p.start_date,p.end_date,p.period_type,coalesce(s.published_at,r.fetched_at,s.created_at)::text available
FROM financial_statements s JOIN financial_periods p ON p.id=s.financial_period_id
JOIN financial_statement_items i ON i.financial_statement_id=s.id
JOIN raw_payloads r ON r.id=s.raw_payload_id
JOIN data_versions v ON v.id=s.data_version_id AND v.ingestion_run_id=r.ingestion_run_id
WHERE s.security_id=? AND s.is_current AND s.is_canonical AND s.unit_scale>0
    AND v.data_domain='FINANCIAL_STATEMENT' AND v.status='ACTIVATED'
    AND s.currency='VND' AND i.unit='VND' AND i.value IS NOT NULL
    AND s.statement_type IN ('BALANCE_SHEET','INCOME_STATEMENT')
    AND p.end_date BETWEEN ? AND ? AND p.period_type IN ('Q1','Q2','Q3','Q4')
ORDER BY p.end_date,s.statement_type,s.report_scope,i.item_code,i.id
""",(r,n)->new Actual((UUID)r.getObject(1),(UUID)r.getObject(2),(UUID)r.getObject(3),
                (UUID)r.getObject(4),r.getLong(5),(UUID)r.getObject(6),(UUID)r.getObject(7),r.getString(8),r.getString(9),
                new SourcePoint("FSI:"+r.getString(10),r.getString(8),r.getString(11),r.getBigDecimal(12),
                        r.getString(13).trim(),r.getDate(14).toLocalDate(),r.getDate(15).toLocalDate(),
                        r.getString(16),r.getString(9),r.getString(17))),security,request.startDate(),request.endDate());
        var groups=new LinkedHashMap<UUID,List<Actual>>();
        for (var row:rows) groups.computeIfAbsent(row.statement(),ignored->new ArrayList<>()).add(row);
        int inserted=0,unchanged=0;
        for (var group:groups.values()) {
            Actual source=group.getFirst();
            var points=group.stream().map(Actual::point).toList();
            var metrics="BALANCE_SHEET".equals(source.type()) ? balance.calculate(points) : incomeRatios(points);
            for (var metric:metrics) {
                // The canonical index is company/date based, so ambiguous multiple report scopes
                // cannot compete silently for a single derived ratio slot.
                long scopes=groups.values().stream().map(List::getFirst).filter(s->s.type().equals(source.type())
                        && s.point().periodEnd().equals(source.point().periodEnd())).count();
                if (scopes!=1) continue;
                var inputs=points.stream().filter(p->metric.sourcePointIds().contains(p.id())).toList();
                var snapshot=mapper.createObjectNode().put("calculationVersion","historical-actual-ratios-v1")
                        .put("statementId",source.statement().toString()).put("sourceRawId",source.raw().toString())
                        .put("formula",metric.formula()).put("outputUnit",metric.unit())
                        .put("reportScope",source.scope())
                        .put("limitation","Provider report scope/publication/standalone-vs-cumulative basis may be unknown; ratios use one statement only. Not point-in-time backtest data.");
                snapshot.set("inputs",mapper.valueToTree(inputs));
                String key=hashes.sha256("historical-actual-ratios-v1:"+source.statement()+":"+metric.code()+":"+snapshot);
                Long definition=db.queryForObject("SELECT id FROM metric_definitions WHERE code=?",Long.class,metric.code());
                int count=db.update("""
INSERT INTO financial_metrics(company_id,security_id,financial_period_id,metric_definition_id,as_of_date,
    value,data_source_id,raw_payload_id,data_version_id,is_derived,is_canonical,calculation_version,
    quality_status,input_snapshot,calculation_key)
VALUES (?,?,?,?,?,?,?,?,?,true,false,'historical-actual-ratios-v1','VALID',?::jsonb,?)
ON CONFLICT (calculation_key) WHERE calculation_key IS NOT NULL DO NOTHING
""",source.company(),source.security(),source.period(),definition,source.point().periodEnd(),metric.value(),
                        source.source(),source.raw(),source.version(),snapshot.toString(),key);
                inserted+=count;unchanged+=count==0?1:0;
                FinancialMetricBuildPersistenceService.reconcile(db,company,definition,source.point().periodEnd());
            }
        }
        return new Result(groups.size(),inserted,unchanged);
    }
    static List<DerivedMetric> incomeRatios(List<SourcePoint> points) {
        var byCode=new HashMap<String,SourcePoint>();var ambiguous=new HashSet<String>();
        for (var p:points) if (byCode.putIfAbsent(p.code(),p)!=null) ambiguous.add(p.code());
        ambiguous.forEach(byCode::remove);
        var out=new ArrayList<DerivedMetric>();
        for (var spec:List.of(List.of("NET_MARGIN","NET_PROFIT_AFTER_TAX"),
                List.of("GROSS_MARGIN","GROSS_PROFIT"),List.of("OPERATING_MARGIN","NET_PROFIT_FROM_OPERATING_ACTIVITIES"))) {
            var a=byCode.get(spec.get(1));var b=byCode.get("NET_SALES");
            if (a==null || b==null || a.value()==null || b.value()==null || b.value().signum()<=0
                    || !a.unit().equals(b.unit()) || !a.periodEnd().equals(b.periodEnd())
                    || !a.scope().equals(b.scope())) continue;
            out.add(new DerivedMetric(spec.getFirst(),a.value().multiply(new BigDecimal("100"))
                    .divide(b.value(),8,RoundingMode.HALF_UP),"%",spec.get(1)+" / NET_SALES * 100",List.of(a.id(),b.id())));
        }
        return List.copyOf(out);
    }
}
