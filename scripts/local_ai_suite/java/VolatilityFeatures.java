import com.hethongdata.taichinh.service.localai.LocalAiCsv;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures.Price;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Causal multiscale closing-return features; not intraday HAR realized variance. */
public class VolatilityFeatures {
 static final List<String> NAMES=List.of("rolling5_volatility","rolling20_volatility","rolling252_volatility","ewma94_volatility","ewma97_volatility","downside20_rms","rms20_annualized","max_abs_log_return20","variance_concentration20","mean_abs_log_return5");
 static Map<String,Double> calculate(List<Price> history,SortedSet<LocalDate> calendar){
  var f=new LinkedHashMap<String,Double>();for(String n:NAMES)f.put(n,Double.NaN);
  if(history.size()<21)return f;
  int count=Math.min(253,history.size());var path=history.subList(history.size()-count,history.size());
  var dates=new TreeSet<LocalDate>();for(var p:path)dates.add(p.date());
  // A gap far in the past must not invalidate a shorter, complete recent window.
  // Keep only the contiguous suffix; never synthesize a return across missing sessions.
  int suffixStart=0;
  for(int i=1;i<path.size();i++)if(calendar.subSet(path.get(i-1).date(),path.get(i).date()).size()!=1)suffixStart=i;
  path=path.subList(suffixStart,path.size());
  if(path.size()<21)return f;
  var returns=new ArrayList<Double>();for(int i=1;i<path.size();i++){double x=Math.log(path.get(i).close()/path.get(i-1).close());if(!Double.isFinite(x))throw new IllegalArgumentException("INVALID_LOG_RETURN");returns.add(x);}
  for(int n:new int[]{5,20,252})if(returns.size()>=n)f.put("rolling"+n+"_volatility",RiskDataset.stats(path.subList(path.size()-n-1,path.size())).volatility());
  for(double lambda:new double[]{.94,.97})if(returns.size()>=120){double variance=returns.subList(0,20).stream().mapToDouble(x->x*x).average().orElseThrow();for(int i=20;i<returns.size();i++)variance=lambda*variance+(1-lambda)*returns.get(i)*returns.get(i);f.put(lambda==.94?"ewma94_volatility":"ewma97_volatility",Math.sqrt(252*variance));}
  var tail=returns.subList(returns.size()-20,returns.size());double square=tail.stream().mapToDouble(x->x*x).sum();double max=tail.stream().mapToDouble(Math::abs).max().orElseThrow();
  f.put("downside20_rms",Math.sqrt(tail.stream().mapToDouble(x->Math.pow(Math.min(x,0),2)).average().orElseThrow()*252));
  f.put("rms20_annualized",Math.sqrt(square/20*252));f.put("max_abs_log_return20",max);f.put("variance_concentration20",square>0?max*max/square:0.);f.put("mean_abs_log_return5",returns.subList(returns.size()-5,returns.size()).stream().mapToDouble(Math::abs).average().orElseThrow());return f;
 }
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);var rows=LocalAiCsv.read(root.resolve("data/risk-features/samples.csv"));var prices=ReturnDataset.load(root.resolve("data/snapshot"));
  var votes=new TreeMap<LocalDate,Set<String>>();var grouped=new HashMap<String,TreeMap<LocalDate,Price>>();for(var p:prices){votes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());grouped.computeIfAbsent(p.ticker()+":"+p.provider(),k->new TreeMap<>()).put(p.date(),p);}
  var calendar=new TreeSet<LocalDate>();votes.forEach((d,s)->{if(s.size()>=12)calendar.add(d);});
  var headers=new ArrayList<>(rows.getFirst().keySet());headers.addAll(NAMES);var lines=new ArrayList<List<?>>();var lineage=new ArrayList<List<?>>();
  for(var row:rows){var cutoff=LocalDate.parse(row.get("cutoff"));var history=new ArrayList<>(grouped.get(row.get("ticker")+":"+row.get("provider")).headMap(cutoff,true).values());var f=calculate(history,calendar);
   var used=history.subList(Math.max(0,history.size()-253),history.size());String source=ReturnDataset.hash(ReturnDataset.sourceText(used));var record=new LinkedHashMap<String,Object>(row);f.forEach((k,v)->record.put(k,Double.isFinite(v)?v:null));record.put("feature_version","quarterly-volatility-java-v3");record.put("input_hash",ReturnDataset.hash("quarterly-volatility-java-v3|"+row.get("input_hash")+"|"+source+"|"+f));record.put("source_hash",ReturnDataset.hash(row.get("source_hash")+"|"+source));lines.add(headers.stream().map(record::get).toList());lineage.add(List.of(row.get("sample_id"),String.join(";",used.stream().map(Price::sourceId).toList()),source));}
  try(var w=Files.newBufferedWriter(root.resolve("data/risk-features/samples.csv"))){ReturnDataset.row(w,headers);for(var l:lines)ReturnDataset.row(w,l);}
  try(var w=Files.newBufferedWriter(root.resolve("data/risk-features/volatility_lineage.csv"))){ReturnDataset.row(w,List.of("sample_id","past_volatility_source_ids","past_volatility_path_hash"));for(var l:lineage)ReturnDataset.row(w,l);}
  System.out.println("MULTISCALE_VOLATILITY_FEATURES rows="+lines.size()+" added="+NAMES.size()+" no_future_sources=true");
 }
}
