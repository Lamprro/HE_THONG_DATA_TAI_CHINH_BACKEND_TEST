package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.dto.validation.ValidationExecutionResponse;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.QuarantinedRecordEntity;
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.QuarantinedRecordJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import com.hethongdata.taichinh.service.validation.ValidationRuleCatalogService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/validation")
public class ValidationAdminController {
    private final ValidationJobService jobs; private final ValidationRuleCatalogService rules;
    private final ValidationResultJpaRepository results; private final DataVersionJpaRepository versions; private final QuarantinedRecordJpaRepository quarantines;
    public ValidationAdminController(ValidationJobService jobs, ValidationRuleCatalogService rules, ValidationResultJpaRepository results,
            DataVersionJpaRepository versions, QuarantinedRecordJpaRepository quarantines) { this.jobs = jobs; this.rules = rules; this.results = results; this.versions = versions; this.quarantines = quarantines; }
    @PostMapping("/rules/seed") public Map<String, Integer> seedRules() { return Map.of("seeded", rules.seed()); }
    @GetMapping("/rules") public List<ValidationRuleEntity> listRules() { return rules.list(); }
    @PostMapping("/raw-payloads/{rawPayloadId}") public ValidationExecutionResponse validateRaw(@PathVariable UUID rawPayloadId) { return jobs.validate(rawPayloadId); }
    @PostMapping("/pending") public List<ValidationExecutionResponse> validatePending(@RequestParam(defaultValue = "50") int limit) { return jobs.validatePending(limit); }
    @GetMapping("/results") public List<ValidationResultEntity> results(@RequestParam(defaultValue = "50") int limit) { return results.findAllByOrderByCheckedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100)))); }
    @GetMapping("/data-versions") public List<DataVersionEntity> versions(@RequestParam(defaultValue = "50") int limit) { return versions.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100)))); }
    @GetMapping("/quarantined-records") public List<QuarantinedRecordEntity> quarantines(@RequestParam(defaultValue = "50") int limit) { return quarantines.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100)))); }
}
