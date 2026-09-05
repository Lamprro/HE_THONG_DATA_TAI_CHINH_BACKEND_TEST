package com.hethongdata.taichinh.application.port;

import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;

import java.net.URI;

/**
 * Contract used by ingestion services to resolve a source URL and fetch external data. The Python
 * adapter implements HTTP calls; this interface does not access the database.
 */
public interface ExternalFinancialDataPort {

    URI resolveUri(ExternalFetchRequest request);

    ExternalFetchResponse fetch(ExternalFetchRequest request);
}
