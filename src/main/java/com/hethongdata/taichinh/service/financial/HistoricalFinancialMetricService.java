package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.DerivedMetric;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.SourcePoint;
import com.hethongdata.taichinh.repository.jdbc.financial.FinancialMetricJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Derives and stores historical point-in-time financial ratios for actual reported periods. */
@Service
public class HistoricalFinancialMetricService {
    public record Request(String symbol,LocalDate startDate,LocalDate endDate) {}
    public record Result(int sourceStatements,int inserted,int unchanged) {}
    private record Actual(UUID statement,UUID company,UUID security,UUID period,Long source,
            UUID raw,UUID version,String type,String scope,SourcePoint point) {}

    private final FinancialMetricJdbcRepository metricRepo;
    private final FinancialRatioCalculator balance;
    private final ObjectMapper mapper;
    private final ChecksumService hashes;

    public HistoricalFinancialMetricService(FinancialMetricJdbcRepository metricRepo,FinancialRatioCalculator balance,
            ObjectMapper mapper,ChecksumService hashes) {
        this.metricRepo=metricRepo;this.balance=balance;this.mapper=mapper;this.hashes=hashes;
    }

    @Transactional
    public Result calculate(Request request) {
        if (request.symbol()==null || request.symbol().isBlank() || request.startDate()==null
                || request.endDate()==null || request.startDate().isAfter(request.endDate())
                || request.endDate().isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))))
            throw new IllegalArgumentException("Choose a security and a valid actual-data date range");
        var securities=metricRepo.findSecurityCompanyBySymbol(request.symbol());
        if (securities.size()!=1) throw new IllegalArgumentException("Unknown security");
        UUID security=(UUID)securities.getFirst().get("id");
        UUID company=(UUID)securities.getFirst().get("company_id");
        metricRepo.lockCompanyForUpdate(company);
        var actualRows=metricRepo.findStatementItemActuals(security,request.startDate(),request.endDate());
        var rows=actualRows.stream().map(r->new Actual(
                (UUID)r.get("id"),(UUID)r.get("company_id"),(UUID)r.get("security_id"),
                (UUID)r.get("financial_period_id"),(Long)r.get("data_source_id"),(UUID)r.get("raw_payload_id"),(UUID)r.get("data_version_id"),
                (String)r.get("statement_type"),(String)r.get("report_scope"),
                new SourcePoint("FSI:"+r.get("item_id"), (String)r.get("statement_type"), (String)r.get("item_code"),
                        (BigDecimal)r.get("amount"), ((String)r.get("currency")).trim(),
                        ((java.sql.Date)r.get("start_date")).toLocalDate(), ((java.sql.Date)r.get("end_date")).toLocalDate(),
                        (String)r.get("period_type"), (String)r.get("report_scope"), (String)r.get("available"))
        )).toList();
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
                Long definition=metricRepo.findMetricDefinitionIdByCode(metric.code());
                int count=metricRepo.insertHistoricalActualRatio(source.company(),source.security(),source.period(),definition,
                        source.point().periodEnd(),metric.value(),source.source(),source.raw(),source.version(),snapshot.toString(),key);
                inserted+=count;unchanged+=count==0?1:0;
                metricRepo.reconcile(company,definition,source.point().periodEnd());
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
