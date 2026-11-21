package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.service.localai.LocalAiTrainerService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LocalAiPublicController {
    private final LocalAiTrainerService trainer;

    public LocalAiPublicController(LocalAiTrainerService trainer) {
        this.trainer = trainer;
    }

    @GetMapping("/local-ai/rank")
    public Object getUniverseRankings(@RequestParam(required = false) String bucket) {
        return trainer.getRankings(null, bucket);
    }

    @GetMapping("/securities/{symbol}/local-ai/rank")
    public Object getSecurityRanking(@PathVariable String symbol) {
        return trainer.getRankings(symbol, null);
    }
}
