package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.localai.LocalAiTrainerService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/local-ai")
public class LocalAiAdminController {
    private final LocalAiTrainerService trainer;

    public LocalAiAdminController(LocalAiTrainerService trainer) {
        this.trainer = trainer;
    }

    @GetMapping("/readiness")
    public Object getReadiness() {
        return trainer.getReadiness();
    }

    @GetMapping("/training-runs")
    public Object listTrainingRuns(@RequestParam(defaultValue = "50") int limit) {
        return trainer.listTrainingRuns(limit);
    }

    @GetMapping("/training-runs/{id}")
    public Object getTrainingRun(@PathVariable UUID id) {
        return trainer.getTrainingRun(id);
    }

    @PostMapping("/training-runs/{id}/publish")
    public Object publish(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String variant = body != null && body.containsKey("variant") ? body.get("variant") : "V1";
        String reason = body != null && body.containsKey("reason") ? body.get("reason") : "Manual publish from admin";
        return trainer.publish(id, variant, reason);
    }

    @PostMapping("/rollback")
    public Object rollback(@RequestBody(required = false) Map<String, String> body) {
        String reason = body != null && body.containsKey("reason") ? body.get("reason") : "Manual rollback from admin";
        return trainer.rollback(reason);
    }

    @PostMapping("/retrain")
    public Object retrain(@RequestBody(required = false) Map<String, Object> body) {
        UUID key = body != null && body.containsKey("idempotencyKey") ? UUID.fromString(body.get("idempotencyKey").toString()) : UUID.randomUUID();
        String trigger = body != null && body.containsKey("trigger") ? body.get("trigger").toString() : "MANUAL";
        String requestedBy = body != null && body.containsKey("requestedBy") ? body.get("requestedBy").toString() : "admin";
        String reason = body != null && body.containsKey("reason") ? body.get("reason").toString() : "Admin requested retrain";
        int workers = body != null && body.containsKey("workers") ? Integer.parseInt(body.get("workers").toString()) : 1;
        return trainer.retrain(key, trigger, requestedBy, reason, workers);
    }
}
