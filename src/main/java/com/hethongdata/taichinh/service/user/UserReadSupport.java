package com.hethongdata.taichinh.service.user;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.repository.jdbc.user.UserReadJdbcRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Explicit public projections only. Never accepts a table, SQL fragment or sort from a caller. */
@Component
public class UserReadSupport {
  public record Meta(String timezone, String numberEncoding, Instant generatedAt, List<String> notes) {}
  public record Data<T>(T data, Meta meta) {}
  public record Page<T>(List<T> items, long total, int page, int size, boolean hasNext, Meta meta) {}
  public record Window(LocalDate from, LocalDate to) {
    public Instant start() { return from.atStartOfDay(ZONE).toInstant(); }
    public Instant until() { return to.plusDays(1).atStartOfDay(ZONE).toInstant(); }
  }
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final UserReadJdbcRepository repo;
  private final ObjectMapper json;

  public UserReadSupport(UserReadJdbcRepository repo, ObjectMapper json) {
    this.repo = repo;
    this.json = json;
  }

  public Map<String, Object> resolve(String symbol, String exchange) {
    return repo.resolve(symbol, exchange);
  }

  public static Meta meta(String... notes) {
    return new Meta(ZONE.getId(), "DECIMAL_STRING", Instant.now(), List.of(notes));
  }

  public static <T> Data<T> data(T value, String... notes) {
    return new Data<>(value, meta(notes));
  }

  public static void paging(int page, int size, int max) {
    if (page < 0 || size < 1 || size > max || (long) page * size > 10000)
      throw new IllegalArgumentException("page >= 0, size 1.." + max + ", offset <= 10000 required");
  }

  public static String code(String value) {
    if (value == null || !value.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}"))
      throw new IllegalArgumentException("Invalid code or symbol");
    return value.trim().toUpperCase(Locale.ROOT);
  }

  public static String search(String value) {
    String text = value == null ? "" : value.trim();
    if (text.length() > 100) throw new IllegalArgumentException("Search is limited to 100 characters");
    return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  public static String option(String value, Set<String> allowed) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized))
      throw new IllegalArgumentException("Expected one of " + new TreeSet<>(allowed));
    return normalized;
  }

  public static List<String> codes(String csv, int max) {
    if (csv == null || csv.isBlank()) throw new IllegalArgumentException("A comma-separated code list is required");
    if (csv.length() > max * 41) throw new IllegalArgumentException("Code list is too long");
    var values = Arrays.stream(csv.split(",", -1)).map(UserReadSupport::code).distinct().toList();
    if (values.size() > max) throw new IllegalArgumentException("At most " + max + " distinct codes");
    return values;
  }

  public static String publicSourceUrl(Object value) {
    if (value == null) return null;
    try {
      var uri = java.net.URI.create(value.toString());
      return Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
          && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getFragment() == null
          ? uri.toASCIIString() : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public static Window window(LocalDate from, LocalDate to, int defaultYears, int maxYears) {
    LocalDate end = to == null ? LocalDate.now(ZONE) : to;
    LocalDate start = from == null ? end.minusYears(defaultYears) : from;
    if (start.isAfter(end) || start.isBefore(end.minusYears(maxYears)) || start.getYear() < 1900 || end.getYear() > 2200)
      throw new IllegalArgumentException("Invalid date range; maximum " + maxYears + " calendar years, years 1900..2200");
    return new Window(start, end);
  }

  public JsonNode publicJson(JsonNode node) {
    if (node.isNumber()) return json.getNodeFactory().textNode(node.asText());
    if (node.isObject()) {
      var out = json.createObjectNode();
      node.fields().forEachRemaining(e -> out.set(e.getKey(), publicJson(e.getValue())));
      return out;
    }
    if (node.isArray()) {
      var out = json.createArrayNode();
      node.forEach(n -> out.add(publicJson(n)));
      return out;
    }
    return node.deepCopy();
  }
}
