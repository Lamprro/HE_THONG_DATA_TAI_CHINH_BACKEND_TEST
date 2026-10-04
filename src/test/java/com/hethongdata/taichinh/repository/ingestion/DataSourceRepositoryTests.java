package com.hethongdata.taichinh.repository.ingestion;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceRepositoryTests {
    @Test
    void exactSourceCodeWinsOverOtherRowsSharingProvider() {
        DataSourceJpaRepository jpa = mock(DataSourceJpaRepository.class);
        DataSourceEntity api = source("VNSTOCK", "API");
        DataSourceEntity library = source("VNSTOCK_LIBRARY", "LIBRARY");
        when(jpa.findActiveByProvider("VNSTOCK")).thenReturn(List.of(api, library));
        when(jpa.findByCodeIgnoreCase("VNSTOCK")).thenReturn(java.util.Optional.of(api));

        DataSourceEntity resolved = new DataSourceRepository(jpa)
                .findEntityActiveByCode("VNSTOCK").orElseThrow();

        assertEquals("VNSTOCK", resolved.getCode());
        assertEquals("API", resolved.getSourceType());
    }

    @Test
    void ambiguousProviderDoesNotSilentlyChooseFirstSource() {
        DataSourceJpaRepository jpa = mock(DataSourceJpaRepository.class);
        when(jpa.findActiveByProvider("vnstock"))
                .thenReturn(List.of(source("VNSTOCK", "API"), source("VNSTOCK_LIBRARY", "LIBRARY")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DataSourceRepository(jpa).findEntityActiveByProvider("vnstock"));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("VNSTOCK_LIBRARY"));
    }

    @Test
    void providerLookupIsAmbiguousEvenWhenOneMatchingCodeLooksFamiliar() {
        DataSourceJpaRepository jpa = mock(DataSourceJpaRepository.class);
        when(jpa.findActiveByProvider("vnstock"))
                .thenReturn(List.of(source("VNSTOCK", "API"), source("VNSTOCK_LIBRARY", "LIBRARY")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DataSourceRepository(jpa).findEntityActiveByProvider("vnstock"));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("specify the exact data source code"));
    }

    @Test
    void upsertRefreshesSourceTypeInsteadOfKeepingStaleLibraryClassification() {
        DataSourceJpaRepository jpa = mock(DataSourceJpaRepository.class);
        DataSourceEntity existing = source("VNSTOCK", "LIBRARY");
        when(jpa.findByCodeIgnoreCase("VNSTOCK")).thenReturn(java.util.Optional.of(existing));
        when(jpa.save(existing)).thenReturn(existing);

        DataSourceEntity updated = new DataSourceRepository(jpa).upsert(
                "VNSTOCK", "VnStock API", "API", null, "vnstock", false, "UNKNOWN", true);

        assertEquals("API", updated.getSourceType());
    }

    private DataSourceEntity source(String code, String type) {
        return DataSourceEntity.create(code, code, type, null, "vnstock", false, "UNKNOWN");
    }
}
