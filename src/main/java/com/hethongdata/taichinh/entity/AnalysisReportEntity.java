package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReportEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "report_type")
    private String reportType;

    @Column(name = "title")
    private String title;

    @Column(name = "content_markdown")
    private String contentMarkdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_output")
    private JsonNode structuredOutput;

    @Column(name = "model_version_id")
    private UUID modelVersionId;

    @Column(name = "llm_run_id")
    private UUID llmRunId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "disclaimer")
    private String disclaimer;

    @Column(name = "created_at")
    private Instant createdAt;

}
