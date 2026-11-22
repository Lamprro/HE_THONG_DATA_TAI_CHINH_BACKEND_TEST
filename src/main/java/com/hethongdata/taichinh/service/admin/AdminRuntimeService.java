package com.hethongdata.taichinh.service.admin;

import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class AdminRuntimeService {
    private static final List<String> SCHEDULERS = List.of(
            "ingestion", "macro", "validation", "security-job-reconciliation", "llm", "news-recovery");
    private final AdminConsoleReadService reads;
    private final AdminCommandService commands;
    private final Environment environment;

    public AdminRuntimeService(AdminConsoleReadService reads, AdminCommandService commands,
            Environment environment) {
        this.reads = reads;
        this.commands = commands;
        this.environment = environment;
    }

    public RuntimeStatus status() {
        var schedulers = new LinkedHashMap<String, SchedulerStatus>();
        for (String name : SCHEDULERS) {
            boolean enabled = environment.getProperty("financial." + name + ".scheduler.enabled", Boolean.class, false);
            schedulers.put(name, new SchedulerStatus(enabled));
        }
        return new RuntimeStatus(reads.databaseUp() ? "UP" : "DOWN", Collections.unmodifiableMap(schedulers),
                "Asia/Ho_Chi_Minh", new WorkerStatus(commands.workerEnabled()));
    }
}
