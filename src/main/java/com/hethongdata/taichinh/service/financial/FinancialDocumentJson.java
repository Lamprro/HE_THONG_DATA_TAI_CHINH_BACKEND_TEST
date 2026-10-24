package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A 25 MiB PDF needs up to 33.34 MiB in the internal base64 envelope. */
public final class FinancialDocumentJson {
    public static final int MAX_ENCODED_STRING = 35 * 1024 * 1024;

    public static ObjectMapper copyForStorage(ObjectMapper source) {
        ObjectMapper copy = source.copy();
        copy.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(MAX_ENCODED_STRING).build());
        return copy;
    }

    private FinancialDocumentJson() {}
}
