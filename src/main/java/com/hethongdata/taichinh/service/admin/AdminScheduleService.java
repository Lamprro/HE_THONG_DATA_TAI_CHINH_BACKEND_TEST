package com.hethongdata.taichinh.service.admin;

import static com.hethongdata.taichinh.config.SecurityConfiguration.currentActor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.Schedule;
import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.ScheduleUpdate;
import com.hethongdata.taichinh.repository.jpa.admin.AdminAuditJpaRepository;
import com.hethongdata.taichinh.repository.jpa.admin.AdminScheduleJpaRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionJobService;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import jakarta.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AdminScheduleService {
  private static final String POLICY_ID = "INGESTION";
  private final AdminScheduleJpaRepository repository;
  private final AdminAuditJpaRepository auditRepository;
  private final ObjectMapper json;
  private final IngestionJobService jobs;
  private final IngestionJobRepository definitions;
  private final Environment environment;
  private final NativeJobGuard guard;
  private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"admin-scoped-schedule");t.setDaemon(true);return t;});

  public AdminScheduleService(AdminScheduleJpaRepository repository,
      AdminAuditJpaRepository auditRepository,
      ObjectMapper json,
      IngestionJobService jobs,
      IngestionJobRepository definitions,
      Environment environment,
      NativeJobGuard guard) {
    this.repository=repository;
    this.auditRepository=auditRepository;
    this.json=json;
    this.jobs=jobs;
    this.definitions=definitions;
    this.environment=environment;
    this.guard=guard;
  }

  public boolean workerEnabled() {return environment.getProperty("financial.admin.schedule.enabled",Boolean.class,true);}
  @PostConstruct void start() {if(workerEnabled()) worker.scheduleWithFixedDelay(this::tick,10,30,TimeUnit.SECONDS);}
  @PreDestroy void stop() {worker.shutdownNow();}

  public Schedule status() {
    var policy = repository.findById(POLICY_ID)
        .orElseThrow(() -> new IllegalStateException("Admin schedule policy is missing"));
    return new Schedule(policy.getId(), policy.isEnabled(), decode(policy.getJobCodes()),
        policy.getVersion(), policy.getReason(), policy.getUpdatedAt(), policy.getHeartbeatAt(),
        policy.getLastSweepStartedAt(), policy.getLastSweepFinishedAt(), policy.getLastError(),
        workerEnabled(), legacyEnabled(), "UTC cron / Asia/Ho_Chi_Minh business dates");
  }

  private boolean legacyEnabled() {return environment.getProperty("financial.ingestion.scheduler.enabled",Boolean.class,false)||environment.getProperty("financial.macro.scheduler.enabled",Boolean.class,false);}

  @Transactional public Schedule update(ScheduleUpdate request) {
    if(request.enabled()&&!workerEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Schedule worker disabled on this instance");
    if(request.reason()==null||request.reason().isBlank()||request.reason().length()>2000) throw new IllegalArgumentException("A reason (1..2000) is required");
    Set<String> codes=request.jobCodes()==null?Set.of():new TreeSet<>(request.jobCodes());
    if(codes.size()>100 || request.enabled()&&codes.isEmpty()) throw new IllegalArgumentException("Choose 1..100 explicit jobs before enabling scheduling");
    if(request.enabled()&&legacyEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Legacy scheduler is configured; disable it before enabling an independent admin schedule");
    var configured=definitions.findAllEntities();
    for(String code:codes) {
      var job=configured.stream().filter(j->j.getCode().equals(code)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown job: "+code));
      if(request.enabled()&&(!job.isActive() || !job.getDataSource().isActive() || job.getCronExpression()==null || job.getCronExpression().isBlank()))
        throw new IllegalArgumentException("Scheduled job must be active, have an active source and a cron: "+code);
    }
    int count=repository.updatePolicy(request.enabled(),json.valueToTree(codes).toString(),request.reason(),request.version());
    if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Schedule changed; reload before updating");
    auditRepository.recordEvent(UUID.randomUUID(),"SCHEDULE_UPDATED",null,request.reason(),currentActor());
    return status();
  }

  private Set<String> decode(String text) {try{return json.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){});}catch(Exception e){throw new IllegalStateException("Invalid schedule scope");}}

  private void tick() {
    if(!workerEnabled()) return;
    try {
      repository.updateHeartbeat();
      var policy=status();if(!policy.enabled()||legacyEnabled()) return;
      guard.run(UUID.nameUUIDFromBytes("admin-schedule-policy".getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->{
        var current=status();if(!current.enabled()) return null;
        repository.recordSweepStarted();
        jobs.executeDueAdminJobs(current.jobCodes());
        repository.recordSweepFinished();
        return null;
      });
    } catch(ResponseStatusException busy) { /* Another instance holds the native policy lock. */ }
    catch(Exception e) {try{repository.recordSweepError(e.getClass().getSimpleName()+": scheduling sweep failed; inspect native runs");}catch(Exception ignored){}}
  }
}
