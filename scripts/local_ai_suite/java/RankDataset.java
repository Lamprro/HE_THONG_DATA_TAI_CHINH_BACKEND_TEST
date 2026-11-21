import com.hethongdata.taichinh.service.localai.*;
import static com.hethongdata.taichinh.service.localai.QuarterlyMarketFeatures.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Independent v3 dataset; v1 files/contracts are never overwritten. */
public class RankDataset {
  record IndexPoint(String id,String source,double close) {}
  static Map<LocalDate,IndexPoint> benchmark(Path snap)throws Exception{
    var points=new HashMap<LocalDate,IndexPoint>();
    var ambiguous=new HashSet<LocalDate>();
    for(var row:LocalAiCsv.read(snap.resolve("03_indices.csv"))){
      if(!"VNINDEX".equals(row.get("ticker"))||!"t".equals(row.get("is_canonical"))
          ||!"ACTIVATED".equals(row.get("version_status")))continue;
      var day=LocalDate.parse(row.get("trading_date"));
      try{
        double open=ReturnDataset.num(row,"open"),high=ReturnDataset.num(row,"high"),
            low=ReturnDataset.num(row,"low"),close=ReturnDataset.num(row,"close");
        if(!(low>0&&low<=Math.min(open,close)&&high>=Math.max(open,close)
            &&Double.isFinite(high)&&Double.isFinite(close)))continue;
        if(points.putIfAbsent(day,new IndexPoint(row.get("source_id"),row.get("data_source_id"),close))!=null)ambiguous.add(day);
      }catch(NumberFormatException ignored){/* A missing benchmark does not change the universe label. */}
    }
    ambiguous.forEach(points::remove);
    return points;
  }
  public static void main(String[] args)throws Exception{
    Path snap=Path.of(args[0]),out=Path.of(args[1]);LocalDate business=LocalDate.parse(args[2]);String frequency=args[3];Files.createDirectories(out);
    var loaded=ReturnDataset.load(snap);
    List<Price> all=loaded;
    if(args.length==5){
      var roster=new TreeSet<>(Files.readAllLines(Path.of(args[4])));
      if(roster.isEmpty()||roster.stream().anyMatch(t->!t.matches("[A-Z0-9]{2,12}")))
        throw new IllegalArgumentException("INVALID_LOCKED_UNIVERSE");
      all=loaded.stream().filter(p->roster.contains(p.ticker())).toList();
      if(!all.stream().map(Price::ticker).collect(java.util.stream.Collectors.toSet()).equals(roster))
        throw new IllegalArgumentException("LOCKED_UNIVERSE_MISSING_PRICES");
    }
    var first=all.stream().map(Price::date).min(LocalDate::compareTo).orElseThrow();
    var vnindex=benchmark(snap);
    var calendar=new TreeSet<LocalDate>();var votes=new TreeMap<LocalDate,Set<String>>();
    for(var p:all)if(!p.date().isAfter(business))votes.computeIfAbsent(p.date(),k->new HashSet<>()).add(p.ticker());
    votes.forEach((d,s)->{if(s.size()>=2)calendar.add(d);});var dates=new ArrayList<>(calendar);
    var grouped=new HashMap<String,NavigableMap<LocalDate,Price>>();for(var p:all)grouped.computeIfAbsent(p.ticker()+":"+p.provider(),k->new TreeMap<>()).put(p.date(),p);
    try(var w=Files.newBufferedWriter(out.resolve("samples.csv"));var ex=Files.newBufferedWriter(out.resolve("exclusions.csv"))){
      var header=new ArrayList<>(List.of("sample_id","ticker","cutoff","origin_price_date","provider","source_hash","universe_hash","universe_size","target_end","label_available_at","actual_return","label_absolute","excess_return_vs_universe","label_relative","label_status","feature_version","label_source_hash","target_source_id","excess_return_vs_vnindex","vnindex_source_ids"));header.addAll(RankMarketFeatures.RAW);header.addAll(RankMarketFeatures.names());ReturnDataset.row(w,header);ReturnDataset.row(ex,List.of("cutoff","ticker","reason"));
      LocalDate cutoff=frequency.equals("quarter")?end(first):first.withDayOfMonth(first.lengthOfMonth());
      while(!cutoff.isAfter(business)){
        final LocalDate now=cutoff;var past=all.stream().filter(p->!p.date().isAfter(now)).toList();
        try{
          var panel=RankMarketFeatures.build(past,cutoff,frequency);for(var e:panel.exclusions().entrySet())ReturnDataset.row(ex,List.of(cutoff,e.getKey(),e.getValue()));
          if(panel.origins().size()<10)throw new IllegalArgumentException("UNIVERSE_BELOW_10");
          String universeHash=ReturnDataset.hash(String.join("|",panel.origins().keySet()));
          LocalDate target=frequency.equals("quarter")?end(cutoff.plusDays(1)):null;
          if(target==null){int i=Collections.binarySearch(dates,panel.origins().values().iterator().next().asOf());if(i>=0&&i+63<dates.size())target=dates.get(i+63);}
          var actuals=new TreeMap<String,Double>();
          var targetRows=new TreeMap<String,Price>();
          if(target!=null&&!target.isAfter(business))for(var o:panel.origins().values()){
            var prices=grouped.get(o.ticker()+":"+o.provider());var end=prices.floorEntry(target);var expected=calendar.floor(target);
            if(end!=null&&expected!=null&&end.getKey().equals(expected)&&ChronoUnit.DAYS.between(expected,target)<=7&&end.getKey().isAfter(o.asOf())){
              var needed=calendar.subSet(o.asOf(),false,expected,true);var available=prices.subMap(o.asOf(),false,expected,true);
              double realized=end.getValue().close()/o.close()-1;
              if(available.size()>=Math.ceil(needed.size()*.95)&&Double.isFinite(realized)&&realized>-1){
                actuals.put(o.ticker(),realized);
                targetRows.put(o.ticker(),end.getValue());
              }
            }
          }
          boolean complete=actuals.size()==panel.origins().size();double median=Double.NaN;
          if(complete){var a=actuals.values().stream().mapToDouble(v->v).sorted().toArray();median=(a[(a.length-1)/2]+a[a.length/2])/2;}
          for(var o:panel.origins().values()){
            Double r=actuals.get(o.ticker());String status=complete?"MATURED_CURRENT_VINTAGE":target==null||target.isAfter(business)?"TARGET_NOT_MATURED":"INCOMPLETE_ORIGIN_COHORT";
            var values=new ArrayList<Object>(Arrays.asList(o.ticker()+":"+cutoff,o.ticker(),cutoff,o.asOf(),o.provider(),ReturnDataset.hash(ReturnDataset.sourceText(o.lineage())),universeHash,panel.origins().size(),target,r==null?null:target,r,r==null?null:r>0?1:0,complete?r-median:null,complete?r>median?1:0:null,status,RankMarketFeatures.VERSION+"-"+frequency));
            var endpoint=targetRows.get(o.ticker());
            values.add(endpoint==null?null:ReturnDataset.hash(ReturnDataset.sourceText(List.of(o.lineage().getLast(),endpoint))));
            values.add(endpoint==null?null:endpoint.sourceId());
            var indexOrigin=vnindex.get(o.asOf());
            var indexTarget=endpoint==null?null:vnindex.get(endpoint.date());
            boolean sameIndexSource=indexOrigin!=null&&indexTarget!=null&&indexOrigin.source().equals(indexTarget.source());
            values.add(sameIndexSource?r-(indexTarget.close()/indexOrigin.close()-1):null);
            values.add(sameIndexSource?indexOrigin.id()+";"+indexTarget.id():null);
            for(String f:RankMarketFeatures.RAW)values.add(o.features().get(f));for(String f:RankMarketFeatures.names())values.add(panel.ranks().get(o.ticker()).get(f));ReturnDataset.row(w,values);
          }
        }catch(IllegalArgumentException e){ReturnDataset.row(ex,List.of(cutoff,"*",e.getMessage()));}
        cutoff=frequency.equals("quarter")?end(cutoff.plusDays(1)):cutoff.plusDays(1).withDayOfMonth(cutoff.plusDays(1).lengthOfMonth());
      }
    }System.out.println("RANK_DATASET_EXPORTED "+frequency);
  }
}
