package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "data_lineage_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataLineageEventEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "from_layer")
    private String fromLayer;

    @Column(name = "from_entity_type")
    private String fromEntityType;

    @Column(name = "from_entity_id")
    private String fromEntityId;

    @Column(name = "to_layer")
    private String toLayer;

    @Column(name = "to_entity_type")
    private String toEntityType;

    @Column(name = "to_entity_id")
    private String toEntityId;

    @Column(name = "transformation")
    private String transformation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private JsonNode metadata;

    @Column(name = "created_at")
    private Instant createdAt;

}
