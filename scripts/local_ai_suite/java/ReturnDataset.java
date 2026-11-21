import com.hethongdata.taichinh.service.localai.LocalAiCsv;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.io.*;

/** Frozen canonical DB export -> shared production Java market15 -> next-quarter return labels. */
public class ReturnDataset {
  static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
  static double num(Map<String,String>r,String k){return Double.parseDouble(r.get(k));}
  static String csv(Object x){return x==null?"":"\""+x.toString().replace("\"","\"\"")+"\"";}
  static void row(Writer w,List<?>values)throws Exception{for(int i=0;i<values.size();i++){if(i>0)w.write(',');w.write(csv(values.get(i)));}w.write('\n');}
  static String sourceText(List<Price>rows){var s=new StringBuilder();for(var p:rows)s.append(p.sourceId()).append('|').append(p.provider()).append('|').append(p.date()).append('|').append(p.open()).append('|').append(p.high()).append('|').append(p.low()).append('|').append(p.rawClose()).append('|').append(p.close()).append('|').append(p.volume()).append('|').append(p.dataVersion()).append('\n');return s.toString();}
  static List<Price> load(Path snap)throws Exception{
    var providers=new HashMap<String,String>();for(var r:LocalAiCsv.read(snap.resolve("01_sources.csv")))providers.put(r.get("id"),r.get("code").toUpperCase(Locale.ROOT));
    var prices=new ArrayList<Price>();var seen=new HashSet<String>();
    for(var r:LocalAiCsv.read(snap.resolve("02_prices.csv"))){
      if((!r.get("is_canonical").equals("t")&&!(Set.of("EIB","HSG","VNM").contains(r.get("ticker"))&&r.get("data_source_id").equals("12")))||!r.get("price_basis_verified").equals("t")||!r.get("version_status").equals("ACTIVATED")||!r.get("interval_code").equals("1d"))throw new IllegalArgumentException("NONCANONICAL_OR_UNVERIFIED_SOURCE");
      String provider=providers.get(r.get("data_source_id"));if(provider==null)throw new IllegalArgumentException("UNKNOWN_PROVIDER");
      String key=r.get("ticker")+":"+r.get("data_source_id")+":"+r.get("trading_date");if(!seen.add(key))throw new IllegalArgumentException("DUPLICATE_CANONICAL_SECURITY_DATE");
      prices.add(new Price(r.get("source_id"),UUID.fromString(r.get("security_id")),r.get("ticker"),provider,LocalDate.parse(r.get("trading_date")),num(r,"open"),num(r,"high"),num(r,"low"),num(r,"close"),num(r,"adjusted_close"),num(r,"volume"),r.get("data_version_id")));
    }return prices;
  }
  public static void main(String[]args)throws Exception{
    Path snap=Path.of(args[0]),out=Path.of(args[1]);LocalDate businessDate=LocalDate.parse(args[2]);int horizon=Integer.parseInt(args[3]);if(horizon<1||horizon>4)throw new IllegalArgumentException("HORIZON_1_TO_4_REQUIRED");Files.createDirectories(out);
    var all=load(snap);var symbols=LocalAiCsv.read(snap.resolve("01_securities.csv")).stream().map(r->r.get("ticker")).sorted().toList();
    LocalDate first=all.stream().map(Price::date).min(LocalDate::compareTo).orElseThrow();first=QuarterlyMarketFeatures.end(first);
    var origins=new TreeMap<LocalDate,Map<String,Origin>>();var errors=new ArrayList<List<?>>();
    for(LocalDate qe=first;!qe.isAfter(businessDate);qe=QuarterlyMarketFeatures.end(qe.plusDays(1))){
      final LocalDate cutoff=qe;var past=all.stream().filter(p->!p.date().isAfter(cutoff)).toList();var built=new TreeMap<String,Origin>();
      for(String ticker:symbols)try{built.put(ticker,QuarterlyMarketFeatures.build(past,ticker,qe,symbols.size()));}catch(IllegalArgumentException e){errors.add(List.of(ticker,qe,"ORIGIN",e.getMessage()));}
      origins.put(qe,built);
    }
    try(var w=Files.newBufferedWriter(out.resolve("samples.csv"));var lineage=Files.newBufferedWriter(out.resolve("lineage.csv"))){
      var header=new ArrayList<String>(List.of("sample_id","security_id","ticker","quarter","cutoff","origin_price_date","provider","origin_close","input_hash","source_hash","source_rows","target_period","target_end","label_available_at","actual_return","label_status","label_origin_close","target_close","label_origin_source_id","target_source_id","label_source_hash"));header.addAll(QuarterlyMarketFeatures.NAMES);row(w,header);
      row(lineage,List.of("sample_id","source_ids","data_version_ids"));
      for(var entry:origins.entrySet())for(var origin:entry.getValue().values()){
        LocalDate qe=entry.getKey(),targetEnd=QuarterlyMarketFeatures.end(qe.plusDays(1).plusMonths(3L*(horizon-1)));Origin target=origins.getOrDefault(targetEnd,Map.of()).get(origin.ticker());
        Double actual=null,labelOrigin=null,targetClose=null;String labelOriginId="",targetId="",labelHash="",status="TARGET_NOT_MATURED";
        if(!targetEnd.isAfter(businessDate)){
          if(target==null){status="TARGET_COVERAGE_UNAVAILABLE";errors.add(List.of(origin.ticker(),qe,"LABEL",status));}
          else try{
            var realized=QuarterlyMarketFeatures.realizedReturn(target,target.lineage(),origin.asOf(),origin.provider());actual=realized.value();labelOrigin=realized.originOnEvaluationBasis().close();labelOriginId=realized.originOnEvaluationBasis().sourceId();
            targetClose=target.close();targetId=target.lineage().getLast().sourceId();labelHash=hash(sourceText(List.of(realized.originOnEvaluationBasis(),target.lineage().getLast())));status="MATURED_CURRENT_VINTAGE";
            if(actual<=-1||!Double.isFinite(actual))throw new IllegalStateException("INVALID_LABEL_RETURN");
          }catch(IllegalArgumentException e){status=e.getMessage();errors.add(List.of(origin.ticker(),qe,"LABEL",status));}
        }
        String sampleId=origin.ticker()+":"+QuarterlyMarketFeatures.period(qe);String sourceHash=hash(sourceText(origin.lineage()));
        String inputHash=hash("direction-market15-v1|"+sampleId+"|"+qe+"|"+origin.provider()+"|"+sourceHash+"|"+origin.features());
        var values=new ArrayList<Object>(Arrays.asList(sampleId,origin.securityId(),origin.ticker(),QuarterlyMarketFeatures.period(qe),qe,origin.asOf(),origin.provider(),origin.close(),inputHash,sourceHash,origin.lineage().size(),QuarterlyMarketFeatures.period(targetEnd),targetEnd,actual==null?null:targetEnd,actual,status,labelOrigin,targetClose,labelOriginId,targetId,labelHash));for(String f:QuarterlyMarketFeatures.NAMES)values.add(origin.features().get(f));row(w,values);
        row(lineage,List.of(sampleId,String.join(";",origin.lineage().stream().map(Price::sourceId).toList()),String.join(";",origin.lineage().stream().map(Price::dataVersion).distinct().sorted().toList())));
      }
    }
    try(var w=Files.newBufferedWriter(out.resolve("exclusions.csv"))){row(w,List.of("ticker","quarter_end","stage","reason"));for(var r:errors)row(w,r);}
    System.out.println("JAVA_FEATURE_LABEL_EXPORT_COMPLETED origins="+origins.values().stream().mapToInt(Map::size).sum()+" source_rows="+all.size()+" completed_quarters="+origins.size());
  }
}
