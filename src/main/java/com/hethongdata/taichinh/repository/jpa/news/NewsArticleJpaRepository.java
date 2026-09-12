package com.hethongdata.taichinh.repository.jpa.news;

import com.hethongdata.taichinh.entity.NewsArticleEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NewsArticleJpaRepository extends JpaRepository<NewsArticleEntity, UUID> {
    Optional<NewsArticleEntity> findByRawPayloadId(UUID rawPayloadId);

    Optional<NewsArticleEntity> findByUrlHash(String urlHash);
}
