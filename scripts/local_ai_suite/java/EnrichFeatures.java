import com.hethongdata.taichinh.service.localai.LocalAiCsv;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures;
import com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures.Price;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.io.*;

/** Same-cutoff cross-sectional and index features. No financial/news date assumptions. */
public class EnrichFeatures {
  static final List<String> CONTEXT=List.of("peer_median_return_1q","peer_up_fraction_1q","peer_return_dispersion_1q","relative_peer_return_1q","peer_return_rank","peer_volatility_rank","relative_peer_log_volume","risk_adjusted_momentum","momentum_acceleration","quarter_sin","quarter_cos");
  static final List<String> INDEX_CODES=List.of("VNINDEX","VN30","HNXINDEX");
  static final List<String> INDEX_SUFFIX=List.of("return_1q","volatility_1q","return_last20","price_to_ma50","age_days","beta_1q","relative_return_1q");
  record IndexPrice(String id,String code,String provider,LocalDate date,double close,String version){}
  record IndexContext(String provider,List<IndexPrice>lineage,Map<String,Double>features,Map<LocalDate,Double>returns){}
  static double n(Map<String,String>r,String key){String x=r.get(key);return x==null||x.isBlank()?Double.NaN:Double.parseDouble(x);}
  static double median(List<Double>vs){var a=vs.stream().filter(Double::isFinite).sorted().toList();int size=a.size();return size==0?Double.NaN:size%2==1?a.get(size/2):(a.get(size/2-1)+a.get(size/2))/2;}
  static double mean(List<Double>a){return a.stream().mapToDouble(x->x).average().orElse(Double.NaN);}
  static double sd(List<Double>a){if(a.size()<2)return Double.NaN;double m=mean(a);return Math.sqrt(a.stream().mapToDouble(v->(v-m)*(v-m)).sum()/(a.size()-1));}
  static double rank(double v,List<Double>peers){double less=peers.stream().filter(x->x<v).count(),ties=peers.stream().filter(x->x==v).count();return (less+.5*ties)/peers.size();}
  static LocalDate start(LocalDate d){return LocalDate.of(d.getYear(),(d.getMonthValue()-1)/3*3+1,1);}
  static boolean complete(List<IndexPrice>rows,LocalDate q,SortedSet<LocalDate>cal){var expected=cal.subSet(q,QuarterlyMarketFeatures.end(q).plusDays(1));return !rows.isEmpty()&&!expected.isEmpty()&&rows.getLast().date().equals(expected.last())&&rows.size()>=Math.ceil(expected.size()*.95);}
  static IndexContext indexContext(List<IndexPrice>all,String code,LocalDate cutoff,SortedSet<LocalDate>calendar){
    var candidates=new ArrayList<IndexContext>();
    for(String provider:all.stream().filter(p->p.code().equals(code)).map(IndexPrice::provider).distinct().sorted().toList()){
      var past=all.stream().filter(p->p.code().equals(code)&&p.provider().equals(provider)&&!p.date().isAfter(cutoff)).sorted(Comparator.comparing(IndexPrice::date)).toList();if(past.isEmpty())continue;
      double age=ChronoUnit.DAYS.between(past.getLast().date(),cutoff);if(age>7)continue;
      int from=past.size()-1;while(from>0&&ChronoUnit.DAYS.between(past.get(from-1).date(),past.get(from).date())<=15)from--;
      var continuous=past.subList(from,past.size());if(continuous.size()<50)continue;
      LocalDate q=start(cutoff),prev=q.minusMonths(3);var current=past.stream().filter(p->!p.date().isBefore(q)).toList();var previous=past.stream().filter(p->!p.date().isBefore(prev)&&p.date().isBefore(q)).toList();
      var returns=new TreeMap<LocalDate,Double>();
      for(int i=1;i<continuous.size();i++)if(!continuous.get(i).date().isBefore(q))returns.put(continuous.get(i).date(),continuous.get(i).close()/continuous.get(i-1).close()-1);
      double last=continuous.getLast().close();var f=new LinkedHashMap<String,Double>();
      f.put("return_1q",complete(current,q,calendar)&&complete(previous,prev,calendar)?last/previous.getLast().close()-1:Double.NaN);
      f.put("volatility_1q",complete(current,q,calendar)?sd(new ArrayList<>(returns.values())):Double.NaN);
      f.put("return_last20",last/continuous.get(continuous.size()-21).close()-1);
      f.put("price_to_ma50",last/continuous.subList(continuous.size()-50,continuous.size()).stream().mapToDouble(IndexPrice::close).average().orElseThrow()-1);f.put("age_days",age);
      candidates.add(new IndexContext(provider,past,f,returns));
    }
    candidates.sort(Comparator.comparingDouble((IndexContext c)->c.features().get("age_days")).thenComparing(IndexContext::provider));return candidates.isEmpty()?null:candidates.getFirst();
  }
  public static void main(String[]args)throws Exception{
    Path root=Path.of(args[0]);var base=LocalAiCsv.read(root.resolve("data/base-features/samples.csv"));var prices=ReturnDataset.load(root.resolve("data/snapshot"));var rawIndex=LocalAiCsv.read(root.resolve("data/snapshot/03_indices.csv"));var indexes=new ArrayList<IndexPrice>();var seen=new HashSet<String>();
    for(var r:rawIndex){double close=n(r,"close");if(!(close>0)||!Double.isFinite(close)||(!r.get("is_canonical").equals("t")&&!r.get("data_source_id").equals("20"))||!r.get("version_status").equals("ACTIVATED"))throw new IllegalArgumentException("INVALID_INDEX_SOURCE");String key=r.get("ticker")+":"+r.get("data_source_id")+":"+r.get("trading_date");if(!seen.add(key))throw new IllegalArgumentException("DUPLICATE_INDEX_PROVIDER_DATE");indexes.add(new IndexPrice(r.get("source_id"),r.get("ticker"),r.get("data_source_id"),LocalDate.parse(r.get("trading_date")),close,r.get("data_version_id")));}
    var groups=new TreeMap<String,List<Map<String,String>>>();for(var r:base)groups.computeIfAbsent(r.get("cutoff"),k->new ArrayList<>()).add(r);
    var names=new ArrayList<String>(QuarterlyMarketFeatures.NAMES);names.addAll(CONTEXT);for(String code:INDEX_CODES)for(String suffix:INDEX_SUFFIX)names.add(code.toLowerCase(Locale.ROOT)+"_"+suffix);
    Path out=root.resolve("data/features");Files.createDirectories(out);
    try(var w=Files.newBufferedWriter(out.resolve("samples.csv"));var lineage=Files.newBufferedWriter(out.resolve("enrichment_lineage.csv"))){
      var headers=new ArrayList<String>(base.getFirst().keySet());headers.addAll(CONTEXT);for(String code:INDEX_CODES)for(String suffix:INDEX_SUFFIX)headers.add(code.toLowerCase(Locale.ROOT)+"_"+suffix);headers.addAll(List.of("base_input_hash","base_source_hash","feature_version","peer_count"));ReturnDataset.row(w,headers);
      ReturnDataset.row(lineage,List.of("sample_id","peer_sample_ids","index_source_ids","index_last_dates","index_providers","source_hash"));
      for(var group:groups.entrySet()){
        LocalDate cutoff=LocalDate.parse(group.getKey());var dateVotes=new TreeMap<LocalDate,Set<String>>();for(var p:prices)if(!p.date().isAfter(cutoff))dateVotes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());var cal=new TreeSet<LocalDate>();dateVotes.forEach((d,s)->{if(s.size()>=12)cal.add(d);});
        var indexContexts=new TreeMap<String,IndexContext>();for(String code:INDEX_CODES)indexContexts.put(code,indexContext(indexes,code,cutoff,cal));
        for(var r:group.getValue()){
          var peers=group.getValue().stream().filter(p->!p.get("ticker").equals(r.get("ticker"))).toList();if(peers.size()<12)throw new IllegalArgumentException("INSUFFICIENT_PEERS");
          var f=new LinkedHashMap<String,Double>();for(String key:QuarterlyMarketFeatures.NAMES)f.put(key,n(r,key));var peerReturns=peers.stream().map(p->n(p,"return_1q")).toList();var peerVols=peers.stream().map(p->n(p,"volatility_1q")).toList();double ret=n(r,"return_1q"),vol=n(r,"volatility_1q");
          double peerMedian=median(peerReturns);f.put("peer_median_return_1q",peerMedian);f.put("peer_up_fraction_1q",peerReturns.stream().filter(v->v>0).count()/(double)peers.size());f.put("peer_return_dispersion_1q",sd(peerReturns));f.put("relative_peer_return_1q",ret-peerMedian);f.put("peer_return_rank",rank(ret,peerReturns));f.put("peer_volatility_rank",rank(vol,peerVols));f.put("relative_peer_log_volume",n(r,"log_avg_volume_1q")-median(peers.stream().map(p->n(p,"log_avg_volume_1q")).toList()));f.put("risk_adjusted_momentum",vol>0?ret/vol:Double.NaN);double ret2=n(r,"return_2q");f.put("momentum_acceleration",Double.isFinite(ret2)?ret-((1+ret2)/(1+ret)-1):Double.NaN);int quarter=(cutoff.getMonthValue()-1)/3;f.put("quarter_sin",Math.sin(2*Math.PI*quarter/4));f.put("quarter_cos",Math.cos(2*Math.PI*quarter/4));
          var stock=prices.stream().filter(p->p.ticker().equals(r.get("ticker"))&&p.provider().equals(r.get("provider"))&&!p.date().isAfter(cutoff)).sorted(Comparator.comparing(Price::date)).toList();var stockReturns=new TreeMap<LocalDate,Double>();
          for(int i=1;i<stock.size();i++)if(!stock.get(i).date().isBefore(start(cutoff))&&ChronoUnit.DAYS.between(stock.get(i-1).date(),stock.get(i).date())<=15)stockReturns.put(stock.get(i).date(),stock.get(i).close()/stock.get(i-1).close()-1);
          var indexIds=new ArrayList<String>();var indexDates=new ArrayList<String>();var indexProviders=new ArrayList<String>();var indexSourceText=new StringBuilder();
          for(String code:INDEX_CODES){String prefix=code.toLowerCase(Locale.ROOT)+"_";IndexContext ctx=indexContexts.get(code);for(String suffix:INDEX_SUFFIX)f.put(prefix+suffix,Double.NaN);if(ctx==null)continue;
            ctx.features().forEach((key,value)->f.put(prefix+key,value));f.put(prefix+"relative_return_1q",ret-ctx.features().get("return_1q"));var pairs=ctx.returns().entrySet().stream().filter(e->stockReturns.containsKey(e.getKey())).toList();
            if(pairs.size()>=40){double ixMean=mean(pairs.stream().map(Map.Entry::getValue).toList()),stockMean=mean(pairs.stream().map(e->stockReturns.get(e.getKey())).toList());double den=0,num=0;for(var e:pairs){double dx=e.getValue()-ixMean;den+=dx*dx;num+=dx*(stockReturns.get(e.getKey())-stockMean);}f.put(prefix+"beta_1q",den>1e-15?num/den:Double.NaN);}
            for(var p:ctx.lineage()){indexIds.add(p.id());indexSourceText.append(p.id()).append('|').append(p.code()).append('|').append(p.provider()).append('|').append(p.date()).append('|').append(p.close()).append('|').append(p.version()).append('\n');}indexDates.add(code+":"+ctx.lineage().getLast().date());indexProviders.add(code+":"+ctx.provider());
          }
          String peerHashes=String.join("|",peers.stream().map(p->p.get("sample_id")+":"+p.get("source_hash")).sorted().toList());String sourceHash=ReturnDataset.hash(r.get("source_hash")+"|"+peerHashes+"|"+indexSourceText);String inputHash=ReturnDataset.hash("return-h1-enriched-java-v2-index-source|"+r.get("sample_id")+"|"+sourceHash+"|"+f);
          var record=new LinkedHashMap<String,Object>();for(String key:base.getFirst().keySet())record.put(key,r.get(key));for(String key:names)record.put(key,Double.isFinite(f.get(key))?f.get(key):null);record.put("base_input_hash",r.get("input_hash"));record.put("base_source_hash",r.get("source_hash"));record.put("input_hash",inputHash);record.put("source_hash",sourceHash);record.put("feature_version","return-h1-enriched-java-v2-index-source");record.put("peer_count",peers.size());ReturnDataset.row(w,headers.stream().map(record::get).toList());
          ReturnDataset.row(lineage,List.of(r.get("sample_id"),String.join(";",peers.stream().map(p->p.get("sample_id")).sorted().toList()),String.join(";",indexIds),String.join(";",indexDates),String.join(";",indexProviders),sourceHash));
        }
      }
    }
    System.out.println("ENRICHED_JAVA_COMPLETED rows="+base.size()+" feature_count="+names.size()+" index_source_rows="+indexes.size());
  }
}
