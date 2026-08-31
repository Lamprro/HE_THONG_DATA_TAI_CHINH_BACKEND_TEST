package com.hethongdata.taichinh.application.port;

import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import java.net.URI;

public interface ExternalFinancialDataPort {

    URI resolveUri(ExternalFetchRequest request);

    ExternalFetchResponse fetch(ExternalFetchRequest request);
}
