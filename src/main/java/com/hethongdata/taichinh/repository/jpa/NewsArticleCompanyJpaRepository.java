package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.NewsArticleCompanyEntity;
import com.hethongdata.taichinh.entity.NewsArticleCompanyEntityId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NewsArticleCompanyJpaRepository
        extends JpaRepository<NewsArticleCompanyEntity, NewsArticleCompanyEntityId> {}
