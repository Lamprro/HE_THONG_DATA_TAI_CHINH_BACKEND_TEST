package com.hethongdata.taichinh.application.port;

import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;

public interface ExternalFinancialDataPort {

    ExternalFetchResponse fetch(ExternalFetchRequest request);
}
