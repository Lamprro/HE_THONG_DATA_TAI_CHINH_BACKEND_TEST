import com.hethongdata.taichinh.service.localai.LocalAiCsv;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures.Price;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Exact full-session close-path risk labels; previous EOD close is the boundary observation. */
public class RiskDataset {
  static final List<String> RISK_FEATURES=List.of("lagged_drawdown","lagged_volatility","lagged_downside_rms","rolling60_volatility","rolling120_volatility","absolute_return_last20","volatility_short_long_ratio");
  record Stats(double drawdown,double volatility,double downside,int sessions){}
  static double number(Map<String,String>r,String k){String x=r.get(k);return x==null||x.isBlank()?Double.NaN:Double.parseDouble(x);}
  static LocalDate start(LocalDate d){return LocalDate.of(d.getYear(),(d.getMonthValue()-1)/3*3+1,1);}
  public static Stats stats(List<Price>path){
    if(path.size()<3)throw new IllegalArgumentException("TOO_SHORT_PATH");double peak=path.getFirst().close(),dd=0;var logReturns=new ArrayList<Double>();
    for(int i=0;i<path.size();i++){
      var p=path.get(i);if(!(p.close()>0)||!Double.isFinite(p.close()))throw new IllegalArgumentException("INVALID_RISK_PRICE");
      if(i>0){var previous=path.get(i-1);if(!p.date().isAfter(previous.date())||!p.provider().equals(previous.provider())||!p.securityId().equals(previous.securityId()))throw new IllegalArgumentException("RISK_PATH_BASIS_OR_ORDER_MISMATCH");logReturns.add(Math.log(p.close()/previous.close()));}
      peak=Math.max(peak,p.close());dd=Math.max(dd,1-p.close()/peak);
    }
    double mean=logReturns.stream().mapToDouble(x->x).average().orElseThrow();double variance=logReturns.stream().mapToDouble(x->(x-mean)*(x-mean)).sum()/(logReturns.size()-1);
    double downside=Math.sqrt(logReturns.stream().mapToDouble(x->Math.pow(Math.min(x,0),2)).average().orElseThrow())*Math.sqrt(252);
    return new Stats(dd,Math.sqrt(variance*252),downside,path.size()-1);
  }
  static List<Price> fullPath(Map<LocalDate,Price>own,SortedSet<LocalDate>calendar,LocalDate q){
    LocalDate end=QuarterlyMarketFeatures.end(q),entry=calendar.headSet(q).isEmpty()?null:calendar.headSet(q).last();var expected=calendar.subSet(q,end.plusDays(1));
    if(entry==null||expected.size()<40||!own.containsKey(entry))throw new IllegalArgumentException("BOUNDARY_OR_CALENDAR_UNAVAILABLE");
    var actual=own.keySet().stream().filter(d->!d.isBefore(q)&&!d.isAfter(end)).collect(java.util.stream.Collectors.toSet());
    if(!actual.equals(expected))throw new IllegalArgumentException("FULL_SESSION_COVERAGE_REQUIRED");
    var path=new ArrayList<Price>();path.add(own.get(entry));for(var date:expected)path.add(own.get(date));return path;
  }
  static double rolling(List<Price>past,int n){
    if(past.size()<=n)return Double.NaN;var tail=past.subList(past.size()-n-1,past.size());
    for(int i=1;i<tail.size();i++)if(ChronoUnit.DAYS.between(tail.get(i-1).date(),tail.get(i).date())>15)return Double.NaN;
    return stats(tail).volatility();
  }
  public static void main(String[]args)throws Exception{
    Path root=Path.of(args[0]);LocalDate businessDate=LocalDate.parse(args[1]);var base=LocalAiCsv.read(root.resolve("data/features/samples.csv"));var all=ReturnDataset.load(root.resolve("data/snapshot"));
    var votes=new TreeMap<LocalDate,Set<String>>();for(var p:all)votes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());var calendar=new TreeSet<LocalDate>();votes.forEach((d,s)->{if(s.size()>=12)calendar.add(d);});
    var bySource=new HashMap<String,TreeMap<LocalDate,Price>>();for(var p:all)bySource.computeIfAbsent(p.ticker()+":"+p.provider(),k->new TreeMap<>()).put(p.date(),p);
    var remove=Set.of("actual_return","label_origin_close","target_close","label_origin_source_id","target_source_id","label_source_hash");var headers=new ArrayList<String>();for(String key:base.getFirst().keySet())if(!remove.contains(key))headers.add(key);
    headers.addAll(RISK_FEATURES);headers.addAll(List.of("actual_drawdown","actual_volatility_annualized","label_entry_close","label_last_close","label_sessions","label_path_hash","label_entry_source_id","target_source_id","risk_origin_sessions","risk_past_path_hash"));
    Path out=root.resolve("data/risk-features");Files.createDirectories(out);int accepted=0,matured=0;var exclusions=new ArrayList<List<?>>();
    try(var w=Files.newBufferedWriter(out.resolve("samples.csv"));var lw=Files.newBufferedWriter(out.resolve("risk_lineage.csv"))){
      ReturnDataset.row(w,headers);ReturnDataset.row(lw,List.of("sample_id","past_risk_source_ids","label_source_ids","label_path_hash"));
      for(var r:base){
        LocalDate cutoff=LocalDate.parse(r.get("cutoff")),q=start(cutoff),targetStart=q.plusMonths(3),targetEnd=QuarterlyMarketFeatures.end(targetStart);var own=bySource.get(r.get("ticker")+":"+r.get("provider"));List<Price>pastPath;
        try{pastPath=fullPath(own,calendar,q);}catch(IllegalArgumentException e){exclusions.add(List.of(r.get("sample_id"),"ORIGIN",e.getMessage()));continue;}
        var pastStats=stats(pastPath);var history=new ArrayList<>(own.headMap(cutoff,true).values());double v60=rolling(history,60),v120=rolling(history,120);
        var f=new LinkedHashMap<String,Double>();f.put("lagged_drawdown",pastStats.drawdown());f.put("lagged_volatility",pastStats.volatility());f.put("lagged_downside_rms",pastStats.downside());f.put("rolling60_volatility",v60);f.put("rolling120_volatility",v120);f.put("absolute_return_last20",Math.abs(number(r,"return_last20")));f.put("volatility_short_long_ratio",v120>0?pastStats.volatility()/v120:Double.NaN);
        String pastHash=ReturnDataset.hash(ReturnDataset.sourceText(pastPath));String inputHash=ReturnDataset.hash("quarterly-risk-java-v1|"+r.get("input_hash")+"|"+pastHash+"|"+f);String sourceHash=ReturnDataset.hash(r.get("source_hash")+"|"+pastHash);
        Stats label=null;List<Price>labelPath=List.of();String labelHash="",labelStatus="TARGET_NOT_MATURED";
        if(!targetEnd.isAfter(businessDate))try{labelPath=fullPath(own,calendar,targetStart);if(!labelPath.getFirst().date().equals(LocalDate.parse(r.get("origin_price_date"))))throw new IllegalArgumentException("LABEL_ENTRY_DATE_MISMATCH");label=stats(labelPath);labelHash=ReturnDataset.hash(ReturnDataset.sourceText(labelPath));labelStatus="MATURED_FULL_SESSION_CURRENT_VINTAGE";matured++;}catch(IllegalArgumentException e){labelStatus=e.getMessage();exclusions.add(List.of(r.get("sample_id"),"LABEL",labelStatus));}
        var record=new LinkedHashMap<String,Object>();for(String key:headers)record.put(key,r.get(key));f.forEach((key,value)->record.put(key,Double.isFinite(value)?value:null));record.put("feature_version","quarterly-risk-java-v1");record.put("input_hash",inputHash);record.put("source_hash",sourceHash);record.put("label_status",labelStatus);record.put("label_available_at",label==null?null:targetEnd);record.put("actual_drawdown",label==null?null:label.drawdown());record.put("actual_volatility_annualized",label==null?null:label.volatility());record.put("label_entry_close",label==null?null:labelPath.getFirst().close());record.put("label_last_close",label==null?null:labelPath.getLast().close());record.put("label_sessions",label==null?null:label.sessions());record.put("label_path_hash",labelHash);record.put("label_entry_source_id",label==null?null:labelPath.getFirst().sourceId());record.put("target_source_id",label==null?null:labelPath.getLast().sourceId());record.put("risk_origin_sessions",pastStats.sessions());record.put("risk_past_path_hash",pastHash);
        ReturnDataset.row(w,headers.stream().map(record::get).toList());ReturnDataset.row(lw,List.of(r.get("sample_id"),String.join(";",pastPath.stream().map(Price::sourceId).toList()),String.join(";",labelPath.stream().map(Price::sourceId).toList()),labelHash));accepted++;
      }
    }
    try(var w=Files.newBufferedWriter(out.resolve("exclusions.csv"))){ReturnDataset.row(w,List.of("sample_id","stage","reason"));for(var r:exclusions)ReturnDataset.row(w,r);}
    System.out.println("RISK_LABELS_COMPLETED origins="+accepted+" matured="+matured+" min_sessions=40 coverage=100pct_consensus_calendar annualization=252");
  }
}
