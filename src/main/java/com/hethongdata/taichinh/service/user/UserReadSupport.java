package com.hethongdata.taichinh.service.user;

import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Explicit public projections only. Never accepts a table, SQL fragment or sort from a caller. */
@Component
public class UserReadSupport {
  public record Meta(String timezone,String numberEncoding,Instant generatedAt,List<String> notes) {}
  public record Data<T>(T data,Meta meta) {}
  public record Page<T>(List<T> items,long total,int page,int size,boolean hasNext,Meta meta) {}
  public record Window(LocalDate from,LocalDate to) {
    public Instant start() {return from.atStartOfDay(ZONE).toInstant();}
    public Instant until() {return to.plusDays(1).atStartOfDay(ZONE).toInstant();}
  }
  public static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json;
  public UserReadSupport(JdbcTemplate source,ObjectMapper json) {
    var bounded=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));bounded.setQueryTimeout(10);
    this.db=new NamedParameterJdbcTemplate(bounded);this.json=json;
  }
  public static Meta meta(String... notes) {return new Meta(ZONE.getId(),"DECIMAL_STRING",Instant.now(),List.of(notes));}
  public static <T> Data<T> data(T value,String... notes) {return new Data<>(value,meta(notes));}
  public static void paging(int page,int size,int max) {
    if(page<0||size<1||size>max||(long)page*size>10000) throw new IllegalArgumentException("page >= 0, size 1.."+max+", offset <= 10000 required");
  }
  public static String code(String value) {
    if(value==null||!value.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) throw new IllegalArgumentException("Invalid code or symbol");
    return value.trim().toUpperCase(Locale.ROOT);
  }
  public static String search(String value) {
    String text=value==null?"":value.trim();if(text.length()>100) throw new IllegalArgumentException("Search is limited to 100 characters");
    return "%"+text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
  }
  public static String option(String value,Set<String> allowed) {
    if(value==null||value.isBlank()) return null;String normalized=value.trim().toUpperCase(Locale.ROOT);
    if(!allowed.contains(normalized)) throw new IllegalArgumentException("Expected one of "+new TreeSet<>(allowed));return normalized;
  }
  public static Window window(LocalDate from,LocalDate to,int defaultYears,int maxYears) {
    LocalDate end=to==null?LocalDate.now(ZONE):to;LocalDate start=from==null?end.minusYears(defaultYears):from;
    if(start.isAfter(end)||start.isBefore(end.minusYears(maxYears))||start.getYear()<1900||end.getYear()>2200)
      throw new IllegalArgumentException("Invalid date range; maximum "+maxYears+" calendar years, years 1900..2200");
    return new Window(start,end);
  }
  public List<Map<String,Object>> rows(String sql,Map<String,?> params) {
    return db.query(sql,params,(rs,n)->{
      var row=new LinkedHashMap<String,Object>();var columns=rs.getMetaData();
      for(int i=1;i<=columns.getColumnCount();i++) row.put(columns.getColumnLabel(i),value(rs.getObject(i)));return row;
    });
  }
  public Map<String,Object> one(String sql,Map<String,?> params,String label) {
    var rows=rows(sql,params);if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,label+" not found");
    if(rows.size()>1) throw new ResponseStatusException(HttpStatus.CONFLICT,label+" is ambiguous; specify exchange");return rows.getFirst();
  }
  public Page<Map<String,Object>> page(String select,String fromWhere,String order,Map<String,?> params,int page,int size,int max,String... notes) {
    paging(page,size,max);var args=new HashMap<String,Object>(params);args.put("limit",size);args.put("offset",(long)page*size);
    long total=Objects.requireNonNull(db.queryForObject("select count(*) "+fromWhere,params,Long.class));
    return new Page<>(rows(select+" "+fromWhere+" "+order+" limit :limit offset :offset",args),total,page,size,((long)page+1)*size<total,meta(notes));
  }
  private Object value(Object value) {
    if(value instanceof BigDecimal n) return n.toPlainString();
    if(value instanceof BigInteger||value instanceof Long||value instanceof UUID) return value.toString();
    if(value instanceof Timestamp t) return t.toInstant().toString();
    if(value instanceof java.sql.Date d) return d.toLocalDate().toString();
    if(value instanceof OffsetDateTime t) return t.toInstant().toString();return value;
  }
  public JsonNode publicJson(JsonNode node) {
    if(node.isNumber()) return json.getNodeFactory().textNode(node.asText());
    if(node.isObject()) {var out=json.createObjectNode();node.fields().forEachRemaining(e->out.set(e.getKey(),publicJson(e.getValue())));return out;}
    if(node.isArray()) {var out=json.createArrayNode();node.forEach(n->out.add(publicJson(n)));return out;}return node.deepCopy();
  }
}
