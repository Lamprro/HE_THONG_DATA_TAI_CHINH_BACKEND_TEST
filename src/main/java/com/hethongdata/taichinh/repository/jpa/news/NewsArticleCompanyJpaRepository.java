package com.hethongdata.taichinh.repository.jpa.news;

import com.hethongdata.taichinh.entity.NewsArticleCompanyEntity;
import com.hethongdata.taichinh.entity.NewsArticleCompanyEntityId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface NewsArticleCompanyJpaRepository
        extends JpaRepository<NewsArticleCompanyEntity, NewsArticleCompanyEntityId> {
    boolean existsByNewsArticleIdAndCompanyId(UUID newsArticleId, UUID companyId);
}
