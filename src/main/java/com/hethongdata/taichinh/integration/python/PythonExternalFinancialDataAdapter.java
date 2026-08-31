package com.hethongdata.taichinh.integration.python;

import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PythonExternalFinancialDataAdapter implements ExternalFinancialDataPort {

    private static final Set<String> EQUITY_PROVIDERS = Set.of("vnstock", "vndirect", "cafef");
    private static final Set<String> FINANCIAL_PARAMETERS = Set.of("period", "fiscal_date", "report_type", "year");
    private static final Set<String> NEWS_PARAMETERS = Set.of("limit");
    private static final Set<String> NEWS_FEED_PARAMETERS = Set.of("site", "limit", "request_delay");

    private final RestClient restClient;
    private final PythonFinancialDataProperties properties;
    private final SensitiveHeaderSanitizer headerSanitizer;

    public PythonExternalFinancialDataAdapter(
            @Qualifier("pythonFinancialRestClient") RestClient restClient,
            PythonFinancialDataProperties properties,
            SensitiveHeaderSanitizer headerSanitizer) {
        this.restClient = restClient;
        this.properties = properties;
        this.headerSanitizer = headerSanitizer;
    }

    @Override
    public URI resolveUri(ExternalFetchRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(properties.getBaseUrl()).path(pathFor(request));
        if (request.operation() == ExternalOperation.OHLCV) {
            addIfPresent(builder, "start", request.startDate());
            addIfPresent(builder, "end", request.endDate());
        }
        allowedQueryParameters(request).forEach((name, value) -> builder.queryParam(name, value));
        return builder.build().encode().toUri();
    }

    @Override
    public ExternalFetchResponse fetch(ExternalFetchRequest request) {
        URI uri = resolveUri(request);
        try {
            ExternalFetchResponse response = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((httpRequest, httpResponse) -> new ExternalFetchResponse(
                            request.operation(),
                            request.provider(),
                            uri,
                            httpResponse.getStatusCode().value(),
                            httpResponse.getHeaders().getContentType() == null
                                    ? null
                                    : httpResponse.getHeaders().getContentType().toString(),
                            headerSanitizer.sanitize(httpResponse.getHeaders()),
                            readBody(httpResponse.getBody()),
                            Instant.now()));
            if (response == null) {
                throw new ExternalFetchException(
                        ExternalErrorCategory.PROTOCOL, null, "Upstream returned no response");
            }
            return response;
        } catch (ResourceAccessException exception) {
            ExternalErrorCategory category = causedByTimeout(exception)
                    ? ExternalErrorCategory.TIMEOUT
                    : ExternalErrorCategory.TRANSPORT;
            throw new ExternalFetchException(category, null, safeTransportMessage(category), exception);
        } catch (RestClientException exception) {
            throw new ExternalFetchException(
                    ExternalErrorCategory.TRANSPORT, null, "Unable to call financial data service", exception);
        }
    }

    private String pathFor(ExternalFetchRequest request) {
        String provider = request.provider();
        String symbol = request.symbol();
        return switch (request.operation()) {
            case HEALTH -> "/api/v1/health";
            case PROVIDERS -> "/api/v1/providers";
            case QUOTE -> equityPath(provider, symbol, "quote");
            case OHLCV -> equityPath(provider, symbol, "ohlcv");
            case COMPANY -> companyPath(provider, symbol);
            case FINANCIAL_STATEMENT -> financialStatementPath(provider, symbol, requiredParameter(request, "statement"));
            case RATIO -> ratioPath(provider, symbol);
            case MANAGEMENT -> cafefPath(symbol, "management");
            case SUBSIDIARIES -> cafefPath(symbol, "subsidiaries");
            case NEWS -> cafefPath(symbol, "news");
            case EVENTS -> cafefPath(symbol, "events");
            case NEWS_STATUS -> "/api/v1/vnstock-news/status";
            case NEWS_SITES -> "/api/v1/vnstock-news/sites";
            case NEWS_LATEST -> "/api/v1/vnstock-news/latest";
            case NEWS_HISTORY -> "/api/v1/vnstock-news/history";
            case NEWS_COMPANY -> "/api/v1/vnstock-news/company/" + symbol;
            case PROXY_PROVIDERS -> "/api/v1/proxy/providers";
            case RAW_PROXY -> rawProxyPath(provider, requiredParameter(request, "upstream_path"));
        };
    }

    private String equityPath(String provider, String symbol, String dataset) {
        return "/api/v1/" + equityProvider(provider) + "/equities/" + symbol + "/" + dataset;
    }

    private String companyPath(String provider, String symbol) {
        String normalizedProvider = equityProvider(provider);
        if (normalizedProvider.equals("vnstock")) {
            return "/api/v1/vnstock/companies/" + symbol;
        }
        return equityPath(normalizedProvider, symbol, "company");
    }

    private String financialStatementPath(String provider, String symbol, String statement) {
        String normalizedProvider = equityProvider(provider);
        return equityPath(normalizedProvider, symbol, "financials/" + normalizeStatement(normalizedProvider, statement));
    }

    private String ratioPath(String provider, String symbol) {
        String normalizedProvider = equityProvider(provider);
        if (normalizedProvider.equals("cafef")) {
            throw new IllegalArgumentException("CafeF does not expose a ratios route in the current API contract");
        }
        return equityPath(normalizedProvider, symbol, normalizedProvider.equals("vndirect") ? "ratios" : "ratio");
    }

    private String cafefPath(String symbol, String dataset) {
        return equityPath("cafef", symbol, dataset);
    }

    private String rawProxyPath(String provider, String upstreamPath) {
        if (!Set.of("vndirect", "cafef", "cafef-financial").contains(provider)) {
            throw new IllegalArgumentException("Unsupported raw proxy provider: " + provider);
        }
        if (upstreamPath.isBlank() || upstreamPath.startsWith("/") || upstreamPath.contains("..")) {
            throw new IllegalArgumentException("upstream_path must be a safe relative path");
        }
        return "/api/v1/proxy/" + provider + "/" + upstreamPath;
    }

    private Map<String, String> allowedQueryParameters(ExternalFetchRequest request) {
        Set<String> allowed = switch (request.operation()) {
            case FINANCIAL_STATEMENT -> FINANCIAL_PARAMETERS;
            case NEWS, EVENTS -> NEWS_PARAMETERS;
            case NEWS_LATEST, NEWS_HISTORY -> NEWS_FEED_PARAMETERS;
            case RAW_PROXY -> request.parameters().keySet();
            default -> Set.of();
        };
        return request.parameters().entrySet().stream()
                .filter(entry -> allowed.contains(entry.getKey()))
                .filter(entry -> !entry.getKey().equals("statement") && !entry.getKey().equals("upstream_path"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String equityProvider(String provider) {
        if (provider == null || !EQUITY_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Unsupported equity provider: " + provider);
        }
        return provider;
    }

    private String requiredParameter(ExternalFetchRequest request, String name) {
        String value = request.parameters().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for " + request.operation());
        }
        return value.trim();
    }

    private String normalizeStatement(String provider, String statement) {
        String normalized = statement.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!Set.of("balance_sheet", "income_statement", "cash_flow").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported statement: " + statement);
        }
        return provider.equals("vnstock") ? normalized : normalized.replace('_', '-');
    }

    private void addIfPresent(UriComponentsBuilder builder, String name, Object value) {
        if (value != null) {
            builder.queryParam(name, value);
        }
    }

    private String readBody(java.io.InputStream body) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    private boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeTransportMessage(ExternalErrorCategory category) {
        return category == ExternalErrorCategory.TIMEOUT
                ? "Financial data service timed out"
                : "Financial data service is unavailable";
    }
}
