package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.IndexPriceEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IndexPriceJpaRepository extends JpaRepository<IndexPriceEntity, Long> {}
