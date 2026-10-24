package com.hethongdata.taichinh.service.financial;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Reviewed issuer origins. Adding an issuer requires a tested provider, not a user URL. */
public final class OfficialFinancialSources {
    public record Source(String symbol, String code, String provider, String listing,
                         String documentOrigin, String documentPrefix) {
        public boolean acceptsDocument(String url) {
            try {
                URI uri = URI.create(url);
                return url.startsWith(documentOrigin + documentPrefix)
                        && (uri.getScheme() + "://" + uri.getRawAuthority()).equals(documentOrigin)
                        && uri.getPath().endsWith(".pdf") && uri.getQuery() == null && uri.getFragment() == null
                        && !uri.getRawPath().replace("%20", "").contains("%") && !uri.getPath().contains("..")
                        && !url.contains("\\");
            } catch (IllegalArgumentException e) { return false; }
        }
        public boolean acceptsListing(String url) {
            if (listing.equals(url)) return true;
            if (symbol.equals("ACB")) return url.matches(java.util.regex.Pattern.quote(listing) + "-20[0-9]{2}");
            if (symbol.equals("HPG")) return url.matches(java.util.regex.Pattern.quote(listing) + "\\?page=([1-9][0-9]?|100)");
            if (symbol.equals("VNM")) return url.matches(java.util.regex.Pattern.quote(listing) + "\\?year=20[0-9]{2}");
            return false;
        }
    }
    private static final Map<String, Source> SOURCES = Map.of(
            "ACB", new Source("ACB", "ACB_IR", "acb-ir", "https://acb.com.vn/nha-dau-tu/bao-cao-tai-chinh", "https://acb.com.vn", "/acbwebsite/"),
            "FPT", new Source("FPT", "FPT_IR", "fpt-ir", "https://fpt.com/vi/nha-dau-tu/thong-tin-cong-bo", "https://fpt.com", "/api/media/"),
            "HPG", new Source("HPG", "HPG_IR", "hpg-ir", "https://www.hoaphat.com.vn/quan-he-co-dong/bao-cao-tai-chinh", "https://file.hoaphat.com.vn", "/hoaphat-com-vn/"),
            "VNM", new Source("VNM", "VNM_IR", "vnm-ir", "https://www.vinamilk.com.vn/investor/reports/financial", "https://d8um25gjecm9v.cloudfront.net", "/cms/"));
    public static Source forSymbol(String symbol) {
        Source source = symbol == null ? null : SOURCES.get(symbol);
        if (source == null) throw new IllegalArgumentException("Official issuer not supported: " + symbol);
        return source;
    }
    public static Set<String> symbols() { return SOURCES.keySet(); }
    public static boolean isProvider(String provider) {
        return SOURCES.values().stream().anyMatch(s -> s.provider().equals(provider));
    }
    private OfficialFinancialSources() {}
}
