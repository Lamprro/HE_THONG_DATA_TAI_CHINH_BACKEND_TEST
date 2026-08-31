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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PythonExternalFinancialDataAdapter implements ExternalFinancialDataPort {

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
        String path = pathFor(request);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(properties.getBaseUrl()).path(path);
        if (request.operation() == ExternalOperation.OHLCV) {
            if (request.startDate() != null) {
                builder.queryParam("start", request.startDate());
            }
            if (request.endDate() != null) {
                builder.queryParam("end", request.endDate());
            }
        }
        request.parameters().forEach(builder::queryParam);
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
        return switch (request.operation()) {
            case HEALTH -> "/api/v1/health";
            case PROVIDERS -> "/api/v1/providers";
            case QUOTE -> "/api/v1/" + supportedProvider(request.provider())
                    + "/equities/" + request.symbol() + "/quote";
            case OHLCV -> "/api/v1/" + supportedProvider(request.provider())
                    + "/equities/" + request.symbol() + "/ohlcv";
        };
    }

    private String supportedProvider(String provider) {
        String normalized = provider.toLowerCase(Locale.ROOT);
        if (!normalized.equals("vnstock") && !normalized.equals("vndirect") && !normalized.equals("cafef")) {
            throw new IllegalArgumentException("Unsupported provider for quote/OHLCV: " + provider);
        }
        return normalized;
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
