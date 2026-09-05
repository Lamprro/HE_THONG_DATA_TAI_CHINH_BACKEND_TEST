package com.hethongdata.taichinh.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Getter
@EqualsAndHashCode
@NoArgsConstructor
public class NewsArticleCompanyEntityId implements Serializable {

    private UUID newsArticleId;

    private UUID companyId;
}
