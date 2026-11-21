package com.hethongdata.taichinh.service.localai;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures.*;

/** Versioned, cross-sectional features. Membership never depends on future labels. */
public final class RankMarketFeatures {
  public static final String VERSION="market-rank-java-v3";
  public static final List<String> RAW=List.of("return_1q","return_2q","return_4q","ret120","volatility_1q","downside_volatility_1q","log_avg_volume_1q","price_to_ma50","price_to_ma200","max_drawdown_1q");
  public static List<String> names(){return RAW.stream().map(n->n+"_rk").toList();}
  public record Panel(Map<String,Origin> origins, Map<String,Map<String,Double>> ranks, Map<String,String> exclusions) {}
  public static Panel build(List<Price> rows,LocalDate cutoff,String frequency){
    if(!Set.of("quarter","month").contains(frequency))throw new IllegalArgumentException("FREQUENCY");
    if(frequency.equals("quarter")&&!cutoff.equals(end(cutoff)) || frequency.equals("month")&&cutoff.getDayOfMonth()!=cutoff.lengthOfMonth())throw new IllegalArgumentException("PERIOD_END_REQUIRED");
    var groups=new TreeMap<String,Map<String,List<Price>>>();var votes=new TreeMap<LocalDate,Set<String>>();
    for(Price p:rows){
      if(p.date().isAfter(cutoff))throw new IllegalArgumentException("FUTURE_SOURCE_ROW");
      groups.computeIfAbsent(p.ticker(),k->new TreeMap<>()).computeIfAbsent(p.provider(),k->new ArrayList<>()).add(p);
      votes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());
    }
    // Two observed symbols define an observed session; later IPOs do not erase old sessions.
    var calendar=new TreeSet<LocalDate>();votes.forEach((d,s)->{if(s.size()>=2)calendar.add(d);});
    if(calendar.isEmpty()||ChronoUnit.DAYS.between(calendar.last(),cutoff)>7)throw new IllegalArgumentException("STALE_MARKET_CALENDAR");
    var origins=new TreeMap<String,Origin>();var excluded=new TreeMap<String,String>();
    for(var entry:groups.entrySet()){
      String error="NO_SOURCE_WITH_HISTORY";
      for(String provider:List.of("VNDIRECT","CAFEF"))try{
        var data=entry.getValue().get(provider);if(data==null)continue;
        data.sort(Comparator.comparing(Price::date));
        var dates=new HashSet<LocalDate>();
        for(var p:data){
          if(!dates.add(p.date()))throw new IllegalArgumentException("DUPLICATE_DAILY_SOURCE");
          if(!(p.close()>0)||!Double.isFinite(p.close())||!(p.rawClose()>0)||!(p.low()>0)||!Double.isFinite(p.open())||!Double.isFinite(p.high())||!Double.isFinite(p.volume())||p.volume()<0||p.high()<Math.max(p.open(),p.rawClose())||p.low()>Math.min(p.open(),p.rawClose()))throw new IllegalArgumentException("INVALID_MARKET_SOURCE");
        }
        if(data.size()<253||!data.getLast().date().equals(calendar.last()))throw new IllegalArgumentException("SHORT_OR_STALE_HISTORY");
        var history=data.subList(data.size()-253,data.size());
        var expected=calendar.subSet(history.getFirst().date(),true,calendar.last(),true);
        if(history.size()<Math.ceil(expected.size()*.95))throw new IllegalArgumentException("HISTORY_GAPS");
        for(int i=1;i<history.size();i++)if(ChronoUnit.DAYS.between(history.get(i-1).date(),history.get(i).date())>15)throw new IllegalArgumentException("HISTORY_GAPS");
        Origin o;
        if(frequency.equals("quarter")){
          // Reuse exact existing market15 formulas with a calendar-sized past-only universe.
          var q=QuarterlyMarketFeatures.buildWithCalendar(data,entry.getKey(),cutoff,calendar,List.of(provider));
          var f=new LinkedHashMap<>(q.features());f.put("ret120",ratio(data.getLast().close(),data.get(data.size()-121).close()));
          o=new Origin(q.securityId(),q.ticker(),q.provider(),q.asOf(),q.close(),f,q.lineage());
        }else{
          double last=data.getLast().close();var f=new LinkedHashMap<String,Double>();
          for(int n:List.of(1,2,4))f.put("return_"+n+"q",ratio(last,data.get(data.size()-63*n-1).close()));
          f.put("ret120",ratio(last,data.get(data.size()-121).close()));
          var rets=new ArrayList<Double>();for(int i=data.size()-63;i<data.size();i++)rets.add(ratio(data.get(i).close(),data.get(i-1).close()));
          f.put("volatility_1q",sd(rets));f.put("downside_volatility_1q",sd(rets.stream().filter(v->v<0).toList()));
          var tail=data.subList(data.size()-63,data.size());
          f.put("log_avg_volume_1q",Math.log1p(tail.stream().mapToDouble(Price::volume).average().orElseThrow()));
          for(int n:List.of(50,200))f.put("price_to_ma"+n,ratio(last,data.subList(data.size()-n,data.size()).stream().mapToDouble(Price::close).average().orElseThrow()));
          double peak=tail.getFirst().close(),dd=0;for(var p:tail){peak=Math.max(peak,p.close());dd=Math.min(dd,ratio(p.close(),peak));}f.put("max_drawdown_1q",dd);
          o=new Origin(data.getFirst().securityId(),entry.getKey(),provider,data.getLast().date(),last,f,List.copyOf(data));
        }
        origins.put(entry.getKey(),o);break;
      }catch(IllegalArgumentException e){error=e.getMessage();}
      if(!origins.containsKey(entry.getKey()))excluded.put(entry.getKey(),error);
    }
    var raw=new TreeMap<String,Map<String,Double>>();origins.forEach((s,o)->raw.put(s,o.features()));
    return new Panel(origins,percentiles(raw,RAW),excluded);
  }
  public static Map<String,Map<String,Double>> percentiles(Map<String,Map<String,Double>> rows,List<String> names){
    var out=new TreeMap<String,Map<String,Double>>();rows.keySet().forEach(k->out.put(k,new LinkedHashMap<>()));
    for(String name:names){
      String rankedName=name+"_rk";
      out.values().forEach(features->features.put(rankedName,null));
      var valid=rows.entrySet().stream()
          .filter(e->e.getValue().get(name)!=null&&Double.isFinite(e.getValue().get(name)))
          .sorted(Comparator.comparingDouble(e->e.getValue().get(name))).toList();
      for(int from=0;from<valid.size();){
        double value=valid.get(from).getValue().get(name);
        int to=from+1;
        while(to<valid.size()&&valid.get(to).getValue().get(name)==value)to++;
        double rank=(from+1.0+to)/(2*valid.size());
        for(int i=from;i<to;i++)out.get(valid.get(i).getKey()).put(rankedName,rank);
        from=to;
      }
    }return out;
  }
  static double ratio(double a,double b){return a/b-1;}
  static Double sd(List<Double> x){if(x.size()<2)return null;double m=x.stream().mapToDouble(v->v).average().orElseThrow();return Math.sqrt(x.stream().mapToDouble(v->(v-m)*(v-m)).sum()/(x.size()-1));}
}
