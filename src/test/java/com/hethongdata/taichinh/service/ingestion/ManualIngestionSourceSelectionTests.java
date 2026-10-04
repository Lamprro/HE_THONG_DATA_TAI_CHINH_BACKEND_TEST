package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.repository.ingestion.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualIngestionSourceSelectionTests {
    final ExternalFinancialDataPort port = mock(ExternalFinancialDataPort.class);
    final DataSourceRepository sources = mock(DataSourceRepository.class);
    final IngestionRunRepository runs = mock(IngestionRunRepository.class);
    final IngestionService service = new IngestionService(port, sources, runs,
            mock(RawPayloadRepository.class), mock(IngestionCompletionService.class),
            new ChecksumService(), new ObjectMapper());

    ManualIngestionRequest request(String code) {
        var request = new ManualIngestionRequest();
        request.setOperation(ExternalOperation.QUOTE);
        request.setProvider("vnstock");
        request.setSymbol("FPT");
        request.setDataSourceCode(code);
        return request;
    }

    @Test void librarySourceIsRejectedBeforeNetworkOrPersistence() {
        when(sources.findEntityActiveByCode("VNSTOCK_LIBRARY")).thenReturn(Optional.of(
                DataSourceEntity.create("VNSTOCK_LIBRARY", "Library", "LIBRARY", null, "vnstock", false, "UNKNOWN")));
        assertThatThrownBy(() -> service.ingest(request("VNSTOCK_LIBRARY")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires an API data source");
        verifyNoInteractions(port, runs);
    }

    @Test void explicitSourceCodeDoesNotFallBackToProviderLookup() {
        when(sources.findEntityActiveByCode("VNSTOCK_API")).thenReturn(Optional.of(
                DataSourceEntity.create("VNSTOCK_API", "API", "API", null, "vnstock", false, "UNKNOWN")));
        // Stop at the network boundary: this test verifies routing, not a provider response.
        var boundary = new IllegalStateException("TEST_NETWORK_BOUNDARY");
        when(port.resolveUri(any())).thenThrow(boundary);
        assertThatThrownBy(() -> service.ingest(request(" VNSTOCK_API "))).isSameAs(boundary);
        verify(sources).findEntityActiveByCode("VNSTOCK_API");
        verify(sources, never()).findEntityActiveByProvider(any());
        verifyNoInteractions(runs);
    }

    @Test void unavailableExplicitSourceIsNotSilentlySubstituted() {
        when(sources.findEntityActiveByCode("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ingest(request("MISSING")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No active data source");
        verify(sources, never()).findEntityActiveByProvider(any());
        verifyNoInteractions(port, runs);
    }
}
