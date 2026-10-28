package com.hethongdata.taichinh.service.user;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;
@Service @Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserSupplementaryService {
  private final UserReadSupport read;private final UserSecurityService securities;
  public UserSupplementaryService(UserReadSupport read,UserSecurityService securities){this.read=read;this.securities=securities;}
  private Map<String,Object> args(String symbol,String exchange,LocalDate from,LocalDate to){
    var range=window(from,to,10,30);var args=new HashMap<String,Object>();args.put("security",UUID.fromString(securities.resolve(symbol,exchange).get("id").toString()));
    args.put("from",range.from());args.put("to",range.to());args.put("start",range.start());args.put("until",range.until());return args;
  }
  public Page<Map<String,Object>> foreign(String symbol,String exchange,LocalDate from,LocalDate to,int page,int size){
    return read.page("""
      select f.id,f.trading_date as "tradingDate",f.buy_volume as "buyVolume",f.sell_volume as "sellVolume",
      'SHARES' as "volumeUnit",f.source_observed_at as "sourceObservedAt",f.created_at as "storedAt",d.code as "sourceCode",f.data_version_id as "versionId"
      ""","from security_foreign_observations f join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED' where f.security_id=:security and f.is_canonical and f.trading_date between :from and :to",
      "order by f.trading_date,f.id",args(symbol,exchange,from,to),page,size,100,"Activated canonical source observations only. Source volume contract is SHARES. Missing buy/sell volume stays null; absence of a day does not mean zero.");
  }
  public Page<Map<String,Object>> reference(String symbol,String exchange,LocalDate from,LocalDate to,int page,int size){
    return read.page("""
      select r.id,r.observed_at as "observedAt",r.source_effective_at as "sourceEffectiveAt",r.industry_name as "industryName",
      r.outstanding_shares as "outstandingShares",r.current_venue_listing_date as "currentVenueListingDate",r.delisted_date as "delistedDate",
      r.listing_status as "listingStatus",d.code as "sourceCode",r.data_version_id as "versionId"
      ""","from security_reference_observations r join data_sources d on d.id=r.data_source_id join data_versions v on v.id=r.data_version_id and v.status='ACTIVATED' where r.security_id=:security and r.observed_at>=:start and r.observed_at<:until",
      "order by r.observed_at desc,r.id",args(symbol,exchange,from,to),page,size,100,"Observed reference snapshots, not reconstructed historical master data. Filter uses observedAt in Asia/Ho_Chi_Minh. Current venue listing date is not necessarily the original listing date.");
  }
}
