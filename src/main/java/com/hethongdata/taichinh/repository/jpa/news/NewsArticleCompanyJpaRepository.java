package com.hethongdata.taichinh.repository.jpa.news;

import com.hethongdata.taichinh.entity.NewsArticleCompanyEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface NewsArticleCompanyJpaRepository extends JpaRepository<NewsArticleCompanyEntity, UUID> {
    @Modifying
    @Query("delete from NewsArticleCompanyEntity link where link.newsArticleId = :articleId and link.matchMethod = 'TEXT_MATCH'")
    int deleteTextMatchesByArticleId(@Param("articleId") UUID articleId);

    boolean existsByNewsArticleIdAndCompanyIdAndSecurityId(
            UUID newsArticleId, UUID companyId, UUID securityId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM news_articles article
                JOIN news_article_companies link ON link.news_article_id = article.id
                LEFT JOIN raw_payloads raw ON raw.id = article.raw_payload_id
                WHERE (article.url_hash = :urlHash
                       OR raw.payload->>'requested_url' = :requestedUrl
                       OR raw.payload->>'final_url' = :requestedUrl)
                  AND (
                    (:securityId IS NOT NULL AND link.security_id = :securityId)
                    OR EXISTS (
                        SELECT 1 FROM securities security
                        WHERE security.symbol = :sourceSymbol
                          AND security.company_id = link.company_id
                          AND (link.security_id = security.id OR link.security_id IS NULL)
                    )
                  )
            )
            """, nativeQuery = true)
    boolean existsSourceLinkByArticleUrl(@Param("urlHash") String urlHash,
            @Param("requestedUrl") String requestedUrl,
            @Param("securityId") UUID securityId, @Param("sourceSymbol") String sourceSymbol);

    @Modifying
    @Query(value = """
            INSERT INTO news_article_companies
                (id, news_article_id, company_id, security_id, relevance_score,
                 match_method, match_evidence, created_at)
            VALUES (gen_random_uuid(), :articleId, :companyId, :securityId, :score,
                    :method, CAST(:evidence AS jsonb), now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertMatchIfAbsent(@Param("articleId") UUID articleId,
            @Param("companyId") UUID companyId,
            @Param("securityId") UUID securityId,
            @Param("score") java.math.BigDecimal score,
            @Param("method") String method,
            @Param("evidence") String evidence);
}
