package com.hethongdata.taichinh.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
@NoArgsConstructor
public class WatchlistItemEntityId implements Serializable {

    private UUID watchlistId;

    private UUID securityId;

}
