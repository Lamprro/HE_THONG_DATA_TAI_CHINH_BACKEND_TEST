package com.hethongdata.taichinh.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Getter
@EqualsAndHashCode
@NoArgsConstructor
public class WatchlistItemEntityId implements Serializable {

    private UUID watchlistId;

    private UUID securityId;
}
