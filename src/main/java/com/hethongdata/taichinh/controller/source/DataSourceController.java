package com.hethongdata.taichinh.controller.source;

import com.hethongdata.taichinh.dto.source.DataSourceRequest;
import com.hethongdata.taichinh.dto.source.DataSourceResponse;
import com.hethongdata.taichinh.service.source.DataSourceService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping
    public List<DataSourceResponse> list() {
        return dataSourceService.list();
    }

    @PostMapping
    public ResponseEntity<DataSourceResponse> upsert(
            @Valid @RequestBody DataSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dataSourceService.upsert(request));
    }
}
