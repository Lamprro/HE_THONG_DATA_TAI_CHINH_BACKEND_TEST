package com.hethongdata.taichinh.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
@NoArgsConstructor
public class NewsArticleCompanyEntityId implements Serializable {

    private UUID newsArticleId;

    private UUID companyId;

}
