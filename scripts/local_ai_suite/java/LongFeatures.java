import com.hethongdata.taichinh.service.localai.LocalAiCsv;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Longer momentum and market context, using only source rows at/before cutoff. */
public class LongFeatures {
 static double n(Map<String,String>r,String k){return EnrichFeatures.n(r,k);}
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);var rows=LocalAiCsv.read(root.resolve("data/features/samples.csv"));var prices=ReturnDataset.load(root.resolve("data/snapshot"));
  var index=new ArrayList<EnrichFeatures.IndexPrice>();for(var r:LocalAiCsv.read(root.resolve("data/snapshot/03_indices.csv")))index.add(new EnrichFeatures.IndexPrice(r.get("source_id"),r.get("ticker"),r.get("data_source_id"),LocalDate.parse(r.get("trading_date")),n(r,"close"),r.get("data_version_id")));
  var lookup=new HashMap<String,Map<String,String>>();for(var r:rows)lookup.put(r.get("sample_id"),r);
  var added=new ArrayList<String>(List.of("return_8q","prior_return_2q","acceleration_2q","log_momentum_2q","log_momentum_4q","trend_2q_minus_4q","risk_scaled_return_2q","risk_scaled_return_4q"));
  for(int q:List.of(2,4))for(String x:List.of("peer_median_return_","relative_peer_return_","peer_up_fraction_"))added.add(x+q+"q");
  for(String code:EnrichFeatures.INDEX_CODES)for(int q:List.of(2,4))added.add(code.toLowerCase(Locale.ROOT)+"_return_"+q+"q");
  var header=new ArrayList<>(rows.getFirst().keySet());header.addAll(added);header.add("enriched_input_hash");Path out=root.resolve("data/long-features");Files.createDirectories(out);
  try(var w=Files.newBufferedWriter(out.resolve("samples.csv"))){
   ReturnDataset.row(w,header);
   for(var r:rows){
    LocalDate cutoff=LocalDate.parse(r.get("cutoff"));var f=new LinkedHashMap<String,Double>();double r2=n(r,"return_2q"),r4=n(r,"return_4q"),vol=n(r,"volatility_1q");
    var past8=lookup.get(r.get("ticker")+":"+QuarterlyMarketFeatures.period(cutoff.minusMonths(24)));f.put("return_8q",past8!=null&&past8.get("provider").equals(r.get("provider"))?n(r,"origin_close")/n(past8,"origin_close")-1:Double.NaN);
    double prev=(1+r4)/(1+r2)-1;f.put("prior_return_2q",prev);f.put("acceleration_2q",r2-prev);f.put("log_momentum_2q",Math.log1p(r2)/2);f.put("log_momentum_4q",Math.log1p(r4)/4);f.put("trend_2q_minus_4q",Math.log1p(r2)/2-Math.log1p(r4)/4);f.put("risk_scaled_return_2q",vol>0?Math.log1p(r2)/(vol*Math.sqrt(126)):Double.NaN);f.put("risk_scaled_return_4q",vol>0?Math.log1p(r4)/(vol*Math.sqrt(252)):Double.NaN);
    var peers=rows.stream().filter(p->p.get("cutoff").equals(r.get("cutoff"))&&!p.get("ticker").equals(r.get("ticker"))).toList();
    for(int q:List.of(2,4)){var ps=peers.stream().map(p->n(p,"return_"+q+"q")).filter(Double::isFinite).toList();double med=EnrichFeatures.median(ps);f.put("peer_median_return_"+q+"q",med);f.put("relative_peer_return_"+q+"q",n(r,"return_"+q+"q")-med);f.put("peer_up_fraction_"+q+"q",ps.isEmpty()?Double.NaN:ps.stream().filter(v->v>0).count()/(double)ps.size());}
    var votes=new TreeMap<LocalDate,Set<String>>();for(var p:prices)if(!p.date().isAfter(cutoff))votes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());var cal=new TreeSet<LocalDate>();votes.forEach((d,v)->{if(v.size()>=12)cal.add(d);});
    for(String code:EnrichFeatures.INDEX_CODES){var ctx=EnrichFeatures.indexContext(index,code,cutoff,cal);for(int q:List.of(2,4)){
     double value=Double.NaN;if(ctx!=null){LocalDate start=EnrichFeatures.start(cutoff).minusMonths(3L*q);var previous=ctx.lineage().stream().filter(p->!p.date().isBefore(start)&&!p.date().isAfter(QuarterlyMarketFeatures.end(start))).toList();var current=ctx.lineage().stream().filter(p->!p.date().isBefore(EnrichFeatures.start(cutoff))).toList();if(EnrichFeatures.complete(previous,start,cal)&&EnrichFeatures.complete(current,EnrichFeatures.start(cutoff),cal))value=ctx.lineage().getLast().close()/previous.getLast().close()-1;}f.put(code.toLowerCase(Locale.ROOT)+"_return_"+q+"q",value);
    }}
    var record=new LinkedHashMap<String,Object>();r.forEach(record::put);for(var e:f.entrySet())record.put(e.getKey(),Double.isFinite(e.getValue())?e.getValue():null);record.put("enriched_input_hash",r.get("input_hash"));record.put("feature_version","direction-long-java-v4");record.put("input_hash",ReturnDataset.hash("direction-long-java-v4|"+r.get("input_hash")+"|"+f));ReturnDataset.row(w,header.stream().map(record::get).toList());
   }
  }
  System.out.println("LONG_FEATURES_COMPLETED rows="+rows.size()+" added="+added.size());
 }
}
