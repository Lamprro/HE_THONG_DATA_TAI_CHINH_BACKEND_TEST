package com.hethongdata.taichinh.repository.jpa.admin;

import com.hethongdata.taichinh.entity.admin.AdminOperationEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AdminAuditJpaRepository extends JpaRepository<AdminOperationEventEntity, UUID> {
    List<AdminOperationEventEntity> findByTargetIdOrderByCreatedAtDescIdDesc(UUID targetId);

    @Modifying
    @Transactional
    @Query(value = """
        insert into admin_operation_events (id,event_type,http_method,request_path,http_status,principal)
        values (:id,'HTTP_MUTATION',:method,:requestUri,:status,:principal)
        """, nativeQuery = true)
    void recordHttpMutation(UUID id, String method, String requestUri, int status, String principal);

    @Modifying
    @Transactional
    @Query(value = """
        insert into admin_operation_events (id,event_type,target_id,reason,principal)
        values (:id,:eventType,:targetId,:reason,:principal)
        """, nativeQuery = true)
    void recordEvent(UUID id, String eventType, UUID targetId, String reason, String principal);

    @Modifying
    @Transactional
    @Query(value = """
        insert into admin_operation_events (id,event_type,command_id,reason,principal)
        values (:id,:type,:commandId,:reason,:principal)
        """, nativeQuery = true)
    void recordCommandEvent(UUID id, String type, UUID commandId, String reason, String principal);
}
