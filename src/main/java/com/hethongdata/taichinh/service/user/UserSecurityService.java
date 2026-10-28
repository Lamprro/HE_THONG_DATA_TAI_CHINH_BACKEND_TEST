package com.hethongdata.taichinh.service.user;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

@Service
@Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserSecurityService {
  private final UserReadSupport read;
  public UserSecurityService(UserReadSupport read) {this.read=read;}
  static final String FIELDS="""
    s.id as "id",s.symbol,s.exchange,s.security_type as "securityType",s.currency,
    s.is_active as "active",s.is_primary as "primary",s.listed_date as "listedDate",s.delisted_date as "delistedDate",
    s.shares_outstanding as "sharesOutstanding",s.par_value as "parValue",s.company_id as "companyId",
    c.legal_name as "companyName",c.short_name as "shortName",c.industry_name as "industryName",c.sector_name as "sectorName"
    """;
  static final String JOIN=" from securities s join companies c on c.id=s.company_id ";
  public Page<Map<String,Object>> securities(String search,String exchange,boolean activeOnly,int page,int size) {
    var args=new HashMap<String,Object>();args.put("search",search(search));args.put("activeOnly",activeOnly);
    String where=JOIN+"where (:activeOnly=false or (s.is_active and c.is_active)) and (s.symbol ilike :search or c.legal_name ilike :search or c.short_name ilike :search)";
    if(exchange!=null&&!exchange.isBlank()) {args.put("exchange",code(exchange));where+=" and s.exchange=:exchange";}
    return read.page("select "+FIELDS,where,"order by s.symbol,s.exchange,s.id",args,page,size,100,"Stored master data; listing and shares may be incomplete or historical.");
  }
  public Map<String,Object> resolve(String symbol,String exchange) {
    var args=new HashMap<String,Object>();args.put("symbol",code(symbol));String where="where s.symbol=:symbol";
    if(exchange!=null&&!exchange.isBlank()) {where+=" and s.exchange=:exchange";args.put("exchange",code(exchange));}
    return read.one("select "+FIELDS+JOIN+where,args,"Security");
  }
  public Data<Map<String,Object>> detail(String symbol,String exchange) {
    var security=new LinkedHashMap<>(resolve(symbol,exchange));
    security.put("company",companyRow(UUID.fromString(security.get("companyId").toString())));
    return data(security,"Master profile is a stored snapshot, not a live provider request.");
  }
  private Map<String,Object> companyRow(UUID id) {
    return read.one("""
      select id,company_code as "companyCode",legal_name as "legalName",short_name as "shortName",
      english_name as "englishName",industry_code as "industryCode",industry_name as "industryName",sector_name as "sectorName",
      website,headquarters,founded_date as "foundedDate",listing_status as "listingStatus",description,is_active as "active",updated_at as "updatedAt"
      from companies where id=:id
      """,Map.of("id",id),"Company");
  }
  public Data<Map<String,Object>> company(UUID id) {
    var company=new LinkedHashMap<>(companyRow(id));
    company.put("securities",read.rows("select "+FIELDS+JOIN+"where c.id=:id order by s.symbol,s.exchange,s.id",Map.of("id",id)));
    return data(company,"Company profile and listing metadata may be incomplete.");
  }
  public Page<Map<String,Object>> companies(String search,boolean activeOnly,int page,int size) {
    return read.page("select c.id,c.company_code as \"companyCode\",c.legal_name as \"legalName\",c.short_name as \"shortName\",c.industry_name as \"industryName\",c.sector_name as \"sectorName\",c.is_active as active",
      "from companies c where (:activeOnly=false or c.is_active) and (c.legal_name ilike :search or c.short_name ilike :search or c.company_code ilike :search)",
      "order by c.legal_name,c.id",Map.of("search",search(search),"activeOnly",activeOnly),page,size,100,"Company UUIDs are distinct from security UUIDs. One company can have multiple listings.");
  }
}
