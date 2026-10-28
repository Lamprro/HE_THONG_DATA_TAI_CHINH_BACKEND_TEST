package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FinancialDocumentJsonTests {
    @Test void documentEnvelopeCanExceedDefaultStringLimitWithoutChangingHttpMapper() throws Exception {
        var normal = new ObjectMapper();
        var documents = FinancialDocumentJson.copyForStorage(normal);
        String encoded = "A".repeat(21_000_000);
        String envelope = "{\"content_base64\":\"" + encoded + "\"}";
        assertThatThrownBy(() -> normal.readTree(envelope)).isInstanceOf(StreamConstraintsException.class);
        assertThat(documents.readTree(envelope).path("content_base64").textValue().length()).isEqualTo(encoded.length());
    }

    @Test void oversizedStorageStringsAreStillRejected() {
        var documents = FinancialDocumentJson.copyForStorage(new ObjectMapper());
        String oversized = "{\"content_base64\":\"" + "A".repeat(FinancialDocumentJson.MAX_ENCODED_STRING + 1) + "\"}";
        assertThatThrownBy(() -> documents.readTree(oversized)).isInstanceOf(StreamConstraintsException.class);
    }
}
