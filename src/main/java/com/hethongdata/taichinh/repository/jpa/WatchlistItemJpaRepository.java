package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.WatchlistItemEntity;
import com.hethongdata.taichinh.entity.WatchlistItemEntityId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WatchlistItemJpaRepository
        extends JpaRepository<WatchlistItemEntity, WatchlistItemEntityId> {}
