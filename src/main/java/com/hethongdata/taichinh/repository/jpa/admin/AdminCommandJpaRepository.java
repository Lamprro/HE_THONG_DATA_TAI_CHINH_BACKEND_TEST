package com.hethongdata.taichinh.repository.jpa.admin;

import com.hethongdata.taichinh.entity.admin.AdminCommandEntity;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface AdminCommandJpaRepository extends JpaRepository<AdminCommandEntity, UUID> {
    interface AiHistoryRow {
        UUID getId();
        String getAction();
        String getResourceKey();
        String getStatus();
        String getOutcome();
        String getErrorMessage();
        Instant getCreatedAt();
    }

    @Query(value = """
        SELECT id,action,resource_key AS "resourceKey",status,error_message AS "errorMessage",
               created_at AS "createdAt",
               CASE result_json->>'eligible'
                 WHEN 'true' THEN 'READY_FOR_EXECUTION'
                 WHEN 'false' THEN 'INPUT_NOT_ELIGIBLE'
                 ELSE result_json->>'status'
               END AS outcome
        FROM admin_commands WHERE action IN (:actions)
        ORDER BY created_at DESC,id DESC
        """, countQuery = "SELECT count(*) FROM admin_commands WHERE action IN (:actions)", nativeQuery = true)
    Page<AiHistoryRow> findAiHistory(Set<String> actions, Pageable pageable);

    interface PreviousCommand {
        UUID getId();
        UUID getParentCommandId();
        String getRequestHash();
    }

    interface ReplayParams {
        String getRequestJson();
        String getSourceCode();
    }

    Optional<PreviousCommand> findProjectedByIdempotencyKey(UUID idempotencyKey);

    @Modifying
    @Transactional
    @Query(value = """
        insert into admin_commands
            (id,idempotency_key,request_hash,action,resource_key,reason,status,request_json,parent_command_id,principal)
        values (:id,:idempotencyKey,:hash,:action,:resource,:reason,'QUEUED',cast(:inputJson as jsonb),:parent,:principal)
        on conflict do nothing
        """, nativeQuery = true)
    int insertQueued(UUID id, UUID idempotencyKey, String hash, String action, String resource,
            String reason, String inputJson, UUID parent, String principal);

    @Query(value = """
        select cast(r.request_query_params as text) as "requestJson", s.code as "sourceCode"
        from ingestion_runs r join data_sources s on s.id=r.data_source_id where r.id=:runId
        """, nativeQuery = true)
    Optional<ReplayParams> getReplayParams(UUID runId);

    @Modifying
    @Transactional
    @Query(value = """
        update admin_commands set status='ACKNOWLEDGED', acknowledgement_note=:note
        where id=:id and status='UNKNOWN'
        """, nativeQuery = true)
    int acknowledge(UUID id, String note);

    @Modifying
    @Transactional
    @Query(value = """
        update admin_commands set status='CANCELLED', finished_at=now(), acknowledgement_note=:reason
        where id=:id and status='QUEUED'
        """, nativeQuery = true)
    int cancelQueued(UUID id, String reason);

    // The caller updates the claimed row before committing the same transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
        select * from admin_commands where status='QUEUED' and action in (:actions)
        order by created_at,id limit 1 for update skip locked
        """, nativeQuery = true)
    Optional<AdminCommandEntity> lockNext(Set<String> actions);

    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
        update admin_commands set status='RUNNING', owner_id=:ownerId, started_at=now(),
            lease_until=now()+interval '90 seconds' where id=:id
        """, nativeQuery = true)
    void markRunning(UUID id, UUID ownerId);

    @Modifying
    @Transactional
    @Query(value = """
        update admin_commands set status='SUCCEEDED', result_json=cast(:resultJson as jsonb), finished_at=now()
        where id=:id and status='RUNNING' and owner_id=:ownerId
        """, nativeQuery = true)
    void recordSuccess(UUID id, UUID ownerId, String resultJson);

    @Modifying
    @Transactional
    @Query(value = """
        update admin_commands set status='FAILED', error_message=:errorMessage,
            result_json=cast(:resultJson as jsonb), finished_at=now()
        where id=:id and status='RUNNING' and owner_id=:ownerId
        """, nativeQuery = true)
    void recordFailure(UUID id, UUID ownerId, String errorMessage, String resultJson);

    @Modifying
    @Transactional
    @Query(value = """
        update admin_commands set progress_json=cast(:progressJson as jsonb)
        where id=:id and owner_id=:ownerId and status='RUNNING'
        """, nativeQuery = true)
    void updateProgress(UUID id, UUID ownerId, String progressJson);

    @Modifying
    @Transactional
    @Query(value = """
        update admin_commands set lease_until=now()+interval '90 seconds'
        where id in (:ids) and status='RUNNING' and owner_id=:ownerId
        """, nativeQuery = true)
    void renewLeases(Set<UUID> ids, UUID ownerId);

    @Modifying
    @Transactional
    @Query(value = """
        update admin_commands set status='UNKNOWN', finished_at=now(),
            error_message='Worker lease expired. Check original native runs before retrying.'
        where status='RUNNING' and lease_until<now()
        """, nativeQuery = true)
    void expireLeases();
}
