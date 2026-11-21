import com.hethongdata.taichinh.service.localai.LocalAiCsv;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures.Price;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Leave-one-out research basket. Not an official index or historical membership reconstruction. */
public class RegimeFeatures {
 public static final List<String> NAMES=List.of("market_return20","market_return60","market_return120","market_vol20","market_vol60","market_vol120","market_downside20","market_drawdown60","market_vol_ratio","market_stress_fraction60","market_breadth_ma50","market_breadth_ma200","market_breadth_return1q","market_dispersion1q","market_daily_dispersion20","market_volume_growth","market_trend_strength","market_corr60","market_beta60","market_shock","market_rebound","market_state_stress","market_state_bull","market_state_bear","market_state_neutral","market_state_unknown","market_peer_count","market_sessions120");
 record Day(LocalDate date,double value,double dispersion,int count){}
 static double n(Map<String,String> r,String key){String x=r.get(key);return x==null||x.isBlank()?Double.NaN:Double.parseDouble(x);}
 static double mean(List<Double> a){return a.stream().mapToDouble(x->x).average().orElse(Double.NaN);}
 static double sd(List<Double>a){double m=mean(a);return a.size()<2?Double.NaN:Math.sqrt(a.stream().mapToDouble(x->(x-m)*(x-m)).sum()/(a.size()-1));}
 static List<Day> tail(List<Day>a,int k){return a.size()<k?List.of():a.subList(a.size()-k,a.size());}
 static double compound(List<Day>a,int k){var t=tail(a,k);if(t.isEmpty())return Double.NaN;double v=1;for(var p:t)v*=1+p.value();return v-1;}
 static double vol(List<Day>a,int k){var t=tail(a,k);return t.isEmpty()?Double.NaN:sd(t.stream().map(p->Math.log1p(p.value())).toList())*Math.sqrt(252);}
 static double breadth(List<Map<String,String>> peers,String field){var values=peers.stream().map(p->n(p,field)).filter(Double::isFinite).toList();return values.size()<12?Double.NaN:values.stream().filter(x->x>0).count()/(double)values.size();}
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);var rows=LocalAiCsv.read(root.resolve("data/long-features/samples.csv"));var prices=ReturnDataset.load(root.resolve("data/snapshot"));
  var allBySource=new HashMap<String,TreeMap<LocalDate,Price>>();for(var p:prices)allBySource.computeIfAbsent(p.ticker()+":"+p.provider(),k->new TreeMap<>()).put(p.date(),p);
  var groups=new TreeMap<String,List<Map<String,String>>>();for(var r:rows)groups.computeIfAbsent(r.get("cutoff"),k->new ArrayList<>()).add(r);
  Path out=root.resolve("data/regime-features");Files.createDirectories(out);var enriched=new HashMap<String,Map<String,Object>>();
  try(var w=Files.newBufferedWriter(out.resolve("samples.csv"));var lw=Files.newBufferedWriter(out.resolve("lineage.csv"))){
   var header=new ArrayList<>(rows.getFirst().keySet());header.addAll(NAMES);ReturnDataset.row(w,header);ReturnDataset.row(lw,List.of("sample_id","cutoff","peer_tickers","source_ids","max_source_date","source_count","basket_days","peer_min_daily","source_hash","definition"));
   for(var entry:groups.entrySet()){
    LocalDate cutoff=LocalDate.parse(entry.getKey());var votes=new TreeMap<LocalDate,Set<String>>();for(var p:prices)if(!p.date().isAfter(cutoff))votes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());var cal=new TreeSet<LocalDate>();votes.forEach((date,tickers)->{if(tickers.size()>=12)cal.add(date);});
    var pastByTicker=new HashMap<String,List<Price>>();var returns=new HashMap<String,Map<LocalDate,Double>>();
    for(var member:entry.getValue()){
     var all=new ArrayList<>(allBySource.get(member.get("ticker")+":"+member.get("provider")).headMap(cutoff,true).values());var past=all.subList(Math.max(0,all.size()-253),all.size());pastByTicker.put(member.get("ticker"),past);var rs=new TreeMap<LocalDate,Double>();
     for(int i=1;i<past.size();i++)if(cal.subSet(past.get(i-1).date(),past.get(i).date()).size()==1)rs.put(past.get(i).date(),past.get(i).close()/past.get(i-1).close()-1);returns.put(member.get("ticker"),rs);
    }
    for(var r:entry.getValue()){
     String ticker=r.get("ticker");var peers=entry.getValue().stream().filter(p->!p.get("ticker").equals(ticker)).toList();if(peers.size()<12)throw new IllegalArgumentException("INSUFFICIENT_REGIME_PEERS");var daily=new TreeMap<LocalDate,List<Double>>();var lineage=new ArrayList<Price>();
     for(var p:entry.getValue()){
      lineage.addAll(pastByTicker.get(p.get("ticker")));if(p.get("ticker").equals(ticker))continue;
      for(var x:returns.get(p.get("ticker")).entrySet())daily.computeIfAbsent(x.getKey(),k->new ArrayList<>()).add(x.getValue());
     }
     var basket=new ArrayList<Day>();for(var day:daily.entrySet())if(day.getValue().size()>=12)basket.add(new Day(day.getKey(),mean(day.getValue()),sd(day.getValue()),day.getValue().size()));
     int start=0;for(int i=1;i<basket.size();i++)if(cal.subSet(basket.get(i-1).date(),basket.get(i).date()).size()!=1)start=i;basket=new ArrayList<>(basket.subList(start,basket.size()));
     if(!basket.isEmpty()&&(!basket.getLast().date().equals(cal.last())||ChronoUnit.DAYS.between(basket.getLast().date(),cutoff)>7))basket.clear();
     var f=new LinkedHashMap<String,Double>();for(String name:NAMES)f.put(name,Double.NaN);
     for(int k:List.of(20,60,120)){f.put("market_return"+k,compound(basket,k));f.put("market_vol"+k,vol(basket,k));}
     var twenty=tail(basket,20);var sixty=tail(basket,60);double dd=Double.NaN;
     if(!sixty.isEmpty()){double value=1,peak=1;dd=0;for(var d:sixty){value*=1+d.value();peak=Math.max(peak,value);dd=Math.max(dd,1-value/peak);}f.put("market_stress_fraction60",sixty.stream().filter(d->d.value()<-.01).count()/60.);}
     f.put("market_drawdown60",dd);
     if(!twenty.isEmpty()){f.put("market_downside20",Math.sqrt(twenty.stream().mapToDouble(d->Math.pow(Math.min(Math.log1p(d.value()),0),2)).average().orElseThrow()*252));f.put("market_daily_dispersion20",twenty.stream().mapToDouble(Day::dispersion).average().orElseThrow());}
     double v120=f.get("market_vol120"),v60=f.get("market_vol60"),v20=f.get("market_vol20");f.put("market_vol_ratio",v120>0?v20/v120:Double.NaN);f.put("market_breadth_ma50",breadth(peers,"price_to_ma50"));f.put("market_breadth_ma200",breadth(peers,"price_to_ma200"));f.put("market_breadth_return1q",breadth(peers,"return_1q"));
     f.put("market_dispersion1q",sd(peers.stream().map(p->n(p,"return_1q")).filter(Double::isFinite).toList()));f.put("market_volume_growth",EnrichFeatures.median(peers.stream().map(p->n(p,"volume_growth_1q")).filter(Double::isFinite).toList()));
     double ret60=f.get("market_return60"),ret20=f.get("market_return20");f.put("market_trend_strength",v60>0?ret60/(v60*Math.sqrt(60./252)):Double.NaN);f.put("market_shock",ret20-ret60/3);f.put("market_rebound",Math.max(ret20,0)*dd);
     var own=returns.get(ticker);var pairs=sixty.stream().filter(d->own.containsKey(d.date())).toList();if(pairs.size()>=40){double ma=mean(pairs.stream().map(Day::value).toList()),mb=mean(pairs.stream().map(d->own.get(d.date())).toList()),xx=0,yy=0,xy=0;for(var d:pairs){double a=d.value()-ma,b=own.get(d.date())-mb;xx+=a*a;yy+=b*b;xy+=a*b;}f.put("market_beta60",xx>0?xy/xx:Double.NaN);f.put("market_corr60",xx>0&&yy>0?xy/Math.sqrt(xx*yy):Double.NaN);}
     String state="unknown";if(Double.isFinite(ret60)&&Double.isFinite(dd)&&Double.isFinite(ret20)&&Double.isFinite(f.get("market_vol_ratio"))){if(dd>.10||ret20<-.05||f.get("market_vol_ratio")>1.5)state="stress";else if(Double.isFinite(f.get("market_breadth_ma200"))&&Double.isFinite(f.get("market_breadth_ma50"))){if(ret60>0&&f.get("market_breadth_ma200")>.55)state="bull";else if(ret60<0&&f.get("market_breadth_ma50")<.45)state="bear";else state="neutral";}}
     for(String label:List.of("stress","bull","bear","neutral","unknown"))f.put("market_state_"+label,state.equals(label)?1.:0.);f.put("market_peer_count",(double)peers.size());f.put("market_sessions120",(double)Math.min(basket.size(),120));
     for(var d:basket)if(d.date().isAfter(cutoff)||!(d.value()>-1))throw new IllegalArgumentException("REGIME_FUTURE_OR_INVALID_RETURN");
     var sorted=lineage.stream().sorted(Comparator.comparing(Price::ticker).thenComparing(Price::date)).toList();LocalDate maxDate=sorted.stream().map(Price::date).max(LocalDate::compareTo).orElseThrow();if(maxDate.isAfter(cutoff))throw new IllegalArgumentException("REGIME_FUTURE_SOURCE");String srcHash=ReturnDataset.hash(ReturnDataset.sourceText(sorted));
     var record=new LinkedHashMap<String,Object>();r.forEach(record::put);f.forEach((key,value)->record.put(key,Double.isFinite(value)?value:null));record.put("feature_version","market-regime-java-v1");record.put("input_hash",ReturnDataset.hash("market-regime-java-v1|"+r.get("input_hash")+"|"+srcHash+"|"+f));record.put("source_hash",ReturnDataset.hash(r.get("source_hash")+"|"+srcHash));enriched.put(r.get("sample_id"),record);ReturnDataset.row(w,header.stream().map(record::get).toList());
     ReturnDataset.row(lw,List.of(r.get("sample_id"),cutoff,String.join(";",peers.stream().map(p->p.get("ticker")).sorted().toList()),String.join(";",sorted.stream().map(Price::sourceId).toList()),maxDate,sorted.size(),basket.size(),basket.stream().mapToInt(Day::count).min().orElse(0),srcHash,"LEAVE_ONE_OUT_AVAILABLE_25_SYMBOL_BASKET"));
    }
   }
  }
  Path riskOut=root.resolve("data/regime-risk");Files.createDirectories(riskOut);var risk=LocalAiCsv.read(root.resolve("data/risk-features/samples.csv"));var header=new ArrayList<>(risk.getFirst().keySet());header.addAll(NAMES);
  try(var w=Files.newBufferedWriter(riskOut.resolve("samples.csv"))){ReturnDataset.row(w,header);for(var r:risk){var context=enriched.get(r.get("sample_id"));if(context==null)throw new IllegalArgumentException("MISSING_REGIME_ORIGIN");var record=new LinkedHashMap<String,Object>();r.forEach(record::put);for(String name:NAMES)record.put(name,context.get(name));record.put("input_hash",ReturnDataset.hash("quarterly-risk-regime-java-v1|"+r.get("input_hash")+"|"+context.get("input_hash")));record.put("feature_version","quarterly-risk-regime-java-v1");ReturnDataset.row(w,header.stream().map(record::get).toList());}}
  Files.writeString(out.resolve("feature_names.json"),"["+String.join(",",NAMES.stream().map(n->"\""+n+"\"").toList())+"]");System.out.println("REGIME_FEATURES_COMPLETED origins="+rows.size()+" risk="+risk.size()+" features="+NAMES.size()+" definition=LEAVE_ONE_OUT_RESEARCH_BASKET");
 }
}
