package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Source contracts for foreign volumes and observed reference data; no zero/date defaults. */
public final class SupplementaryPayloadParser {
    public record ForeignDay(LocalDate date,BigDecimal buy,BigDecimal sell,JsonNode source) {}
    public static List<ForeignDay> foreign(JsonNode root,String symbol) {
        require("foreign_trades.v1".equals(root.path("schema_version").asText())
            && "cafef".equals(root.path("provider").asText()) && "SHARES".equals(root.path("volume_unit").asText()),"Invalid foreign source contract");
        envelope(root,symbol);
        URI url=URI.create(root.path("source_url").asText());
        require("https".equals(url.getScheme()) && "cafef.vn".equals(url.getHost())
            && url.getPath().toLowerCase(Locale.ROOT).endsWith("gdkhoingoai.ashx"),"Invalid foreign source URL");
        LocalDate start=LocalDate.parse(root.path("start_date").asText()),end=LocalDate.parse(root.path("end_date").asText());
        require(!start.isAfter(end) && !end.isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))),"Invalid foreign window");
        Set<LocalDate> seen=new HashSet<>();List<ForeignDay> result=new ArrayList<>();
        for(JsonNode row:root.path("data")) {
            LocalDate day=LocalDate.parse(row.path("Ngay").asText(),DateTimeFormatter.ofPattern("dd/MM/uuuu"));
            require(symbol.equalsIgnoreCase(row.path("Symbol").asText()) && !day.isBefore(start)
                && !day.isAfter(end) && seen.add(day),"Foreign symbol/date mismatch or duplicate");
            result.add(new ForeignDay(day,volume(row.get("KLMua")),volume(row.get("KLBan")),row));
        }
        require(result.stream().anyMatch(r->r.buy()!=null || r.sell()!=null),"No known source foreign volumes");
        return List.copyOf(result);
    }
    public static JsonNode reference(JsonNode root,String symbol) {
        envelope(root,symbol);
        require("vndirect".equals(root.path("provider").asText()) && "company".equals(root.path("dataset").asText())
            && root.path("data").size()==1,"Reference requires one source company row");
        JsonNode row=root.path("data").get(0);
        require(symbol.equalsIgnoreCase(row.path("code").asText()),"Reference symbol mismatch");
        if(row.hasNonNull("outstandingShares")) volume(row.get("outstandingShares"));
        return row;
    }
    private static void envelope(JsonNode root,String symbol) {
        require(root!=null && root.isObject() && root.path("data").isArray() && !root.path("data").isEmpty()
            && root.path("count").isIntegralNumber() && root.path("count").asInt()==root.path("data").size()
            && symbol!=null && symbol.equalsIgnoreCase(root.path("symbol").asText()),"Invalid supplementary envelope");
        require(!Instant.parse(root.path("retrieved_at").asText()).isAfter(Instant.now().plusSeconds(60)),"Future retrieval time");
    }
    private static BigDecimal volume(JsonNode node) {
        if(node==null || node.isNull()) return null;
        require(node.isNumber(),"Foreign/share count must be numeric or null");
        BigDecimal value=node.decimalValue();
        require(value.signum()>=0 && value.stripTrailingZeros().scale()<=0,"Invalid nonnegative share count");
        return value;
    }
    private static void require(boolean ok,String reason) {if(!ok) throw new IllegalArgumentException(reason);}
}
