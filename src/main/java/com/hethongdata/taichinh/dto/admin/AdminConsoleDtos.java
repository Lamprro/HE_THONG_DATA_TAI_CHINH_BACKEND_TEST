package com.hethongdata.taichinh.dto.admin;

import java.util.List;
import java.util.Map;

public final class AdminConsoleDtos {
    private AdminConsoleDtos() {}

    public record Session(boolean authenticated, String principal, String email, List<String> authorities) {}

    public record RuntimeStatus(String database, Map<String, SchedulerStatus> schedulers, String timezone,
            WorkerStatus adminWorkers) {}

    public record SchedulerStatus(boolean enabled) {}

    public record WorkerStatus(boolean commandsEnabled, boolean scheduleEnabled) {}
}
