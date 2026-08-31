package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "watchlist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(WatchlistItemEntityId.class)
public class WatchlistItemEntity {

    @Id
    @Column(name = "watchlist_id")
    private UUID watchlistId;

    @Id
    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "note")
    private String note;

    @Column(name = "added_at")
    private Instant addedAt;

}
