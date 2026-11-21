package com.hethongdata.taichinh.service.localai;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Exact market15 formulas from the trained Java feature pipeline, with no future inputs. */
public final class QuarterlyMarketFeatures {
  public static final List<String> NAMES=List.of("return_1q","return_2q","return_4q","volatility_1q","downside_volatility_1q","log_avg_volume_1q","volume_growth_1q","price_to_ma20","price_to_ma50","price_to_ma200","max_drawdown_1q","close_range_1q","positive_day_fraction","return_last5","return_last20");
  public record Price(String sourceId,UUID securityId,String ticker,String provider,LocalDate date,double open,double high,double low,double rawClose,double close,double volume,String dataVersion) {}
  public record Origin(UUID securityId,String ticker,String provider,LocalDate asOf,double close,Map<String,Double> features,List<Price> lineage) {}
  public record RealizedReturn(double value,Price originOnEvaluationBasis) {}
  /** Corporate actions can revise adjusted history: both endpoints must use the same evaluation vintage/provider. */
  public static RealizedReturn realizedReturn(Origin target,List<Price> evaluationRows,LocalDate originDate,String expectedProvider){
    if(!target.provider().equals(expectedProvider)||!target.asOf().isAfter(originDate))throw new IllegalArgumentException("PROVIDER_OR_PERIOD_BASIS_CHANGED");
    var origins=evaluationRows.stream().filter(p->p.ticker().equals(target.ticker())&&p.provider().equals(expectedProvider)&&p.date().equals(originDate)).toList();
    if(origins.size()!=1||!(origins.getFirst().close()>0)||!Double.isFinite(origins.getFirst().close()))throw new IllegalArgumentException("ORIGIN_PRICE_ON_EVALUATION_BASIS_UNAVAILABLE");
    double value=target.close()/origins.getFirst().close()-1;if(!Double.isFinite(value))throw new IllegalArgumentException("INVALID_REALIZED_RETURN");
    return new RealizedReturn(value,origins.getFirst());
  }
  static LocalDate start(LocalDate d){return LocalDate.of(d.getYear(),((d.getMonthValue()-1)/3)*3+1,1);}
  public static LocalDate end(LocalDate d){return start(d).plusMonths(3).minusDays(1);}
  public static String period(LocalDate d){return d.getYear()+"Q"+((d.getMonthValue()-1)/3+1);}
  static double ret(double a,double b){return Double.isFinite(a)&&Double.isFinite(b)&&b>0?a/b-1:Double.NaN;}
  static double stdev(List<Double>a){if(a.size()<2)return Double.NaN;double m=a.stream().mapToDouble(v->v).average().orElseThrow();return Math.sqrt(a.stream().mapToDouble(v->Math.pow(v-m,2)).sum()/(a.size()-1));}
  static boolean complete(List<Price>q,LocalDate quarter,SortedSet<LocalDate>calendar){
    var expected=calendar.subSet(quarter,end(quarter).plusDays(1));
    return q!=null&&!q.isEmpty()&&!expected.isEmpty()&&q.getLast().date().equals(expected.last())&&q.size()>=Math.ceil(expected.size()*.95);
  }
  static double qclose(Map<LocalDate,List<Price>>qs,LocalDate q,SortedSet<LocalDate>cal){var rows=qs.get(q);return complete(rows,q,cal)?rows.getLast().close():Double.NaN;}
  static double volume(List<Price>q){return q.stream().mapToDouble(Price::volume).average().orElseThrow();}
  public static Origin build(List<Price>all,String ticker,LocalDate quarterEnd,int universeSize){
    if(!quarterEnd.equals(end(quarterEnd)))throw new IllegalArgumentException("QUARTER_END_REQUIRED");
    var votes=new TreeMap<LocalDate,Set<String>>();
    for(var p:all){if(p.date().isAfter(quarterEnd))throw new IllegalArgumentException("FUTURE_SOURCE_ROW");votes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());}
    var calendar=new TreeSet<LocalDate>();votes.forEach((d,v)->{if(v.size()>=Math.max(2,universeSize/2))calendar.add(d);});
    return buildWithCalendar(all,ticker,quarterEnd,calendar,List.of("VNDIRECT","CAFEF"));
  }
  static Origin buildWithCalendar(List<Price> all,String ticker,LocalDate quarterEnd,SortedSet<LocalDate> calendar,List<String> providers){
    LocalDate q=start(quarterEnd);
    for(String provider:providers) {
      var days=all.stream().filter(p->p.ticker().equals(ticker)&&p.provider().equals(provider)).sorted(Comparator.comparing(Price::date)).toList();
      if(days.isEmpty())continue;
      var seen=new HashSet<LocalDate>();var qs=new TreeMap<LocalDate,List<Price>>();
      for(var d:days){
        if(!seen.add(d.date()))throw new IllegalArgumentException("DUPLICATE_DAILY_SOURCE");
        if(!(d.close()>0)||!Double.isFinite(d.close())||!(d.rawClose()>0)||!Double.isFinite(d.volume())||d.volume()<0||d.low()<=0||d.high()<Math.max(Math.max(d.open(),d.rawClose()),d.low())||d.low()>Math.min(Math.min(d.open(),d.rawClose()),d.high()))throw new IllegalArgumentException("INVALID_MARKET_SOURCE");
        qs.computeIfAbsent(start(d.date()),k->new ArrayList<>()).add(d);
      }
      var current=qs.get(q);if(!complete(current,q,calendar))continue;
      double last=current.getLast().close();LocalDate asOf=current.getLast().date();
      var historyAll=days.stream().filter(d->!d.date().isAfter(asOf)).toList();int from=historyAll.size()-1;
      while(from>0&&ChronoUnit.DAYS.between(historyAll.get(from-1).date(),historyAll.get(from).date())<=15)from--;
      var history=historyAll.subList(from,historyAll.size());var returns=new ArrayList<Double>();
      for(int i=1;i<historyAll.size();i++)if(!historyAll.get(i).date().isBefore(q)&&ChronoUnit.DAYS.between(historyAll.get(i-1).date(),historyAll.get(i).date())<=15)returns.add(ret(historyAll.get(i).close(),historyAll.get(i-1).close()));
      var f=new LinkedHashMap<String,Double>();for(int n:List.of(1,2,4))f.put("return_"+n+"q",ret(last,qclose(qs,q.minusMonths(3L*n),calendar)));
      f.put("volatility_1q",stdev(returns));f.put("downside_volatility_1q",stdev(returns.stream().filter(v->v<0).toList()));
      f.put("log_avg_volume_1q",Math.log1p(volume(current)));var previous=qs.get(q.minusMonths(3));f.put("volume_growth_1q",complete(previous,q.minusMonths(3),calendar)?ret(volume(current),volume(previous)):Double.NaN);
      for(int n:List.of(20,50,200))f.put("price_to_ma"+n,history.size()<n?Double.NaN:ret(last,history.subList(history.size()-n,history.size()).stream().mapToDouble(Price::close).average().orElseThrow()));
      double peak=current.getFirst().close(),drawdown=0;for(var d:current){peak=Math.max(peak,d.close());drawdown=Math.min(drawdown,ret(d.close(),peak));}
      f.put("max_drawdown_1q",drawdown);f.put("close_range_1q",(current.stream().mapToDouble(Price::close).max().orElseThrow()-current.stream().mapToDouble(Price::close).min().orElseThrow())/last);
      f.put("positive_day_fraction",returns.isEmpty()?Double.NaN:returns.stream().filter(v->v>0).count()/(double)returns.size());
      for(int n:List.of(5,20))f.put("return_last"+n,history.size()>n?ret(last,history.get(history.size()-n-1).close()):Double.NaN);
      // Training chooses a complete provider first, then rejects missing mandatory history.
      for(String required:List.of("return_1q","price_to_ma50","volatility_1q"))if(!Double.isFinite(f.get(required)))throw new IllegalArgumentException("INSUFFICIENT_CONTIGUOUS_MARKET_HISTORY");
      f.replaceAll((k,v)->Double.isFinite(v)?v:null);
      return new Origin(days.getFirst().securityId(),ticker,provider,asOf,last,Collections.unmodifiableMap(f),List.copyOf(historyAll));
    }
    throw new IllegalArgumentException("NO_COMPLETE_SOURCE_CONSISTENT_QUARTER");
  }
}
