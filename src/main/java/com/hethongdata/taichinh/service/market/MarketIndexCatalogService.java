package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.repository.jpa.market.MarketIndexJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Idempotent master data for the index collection jobs. */
@Service
public class MarketIndexCatalogService {
    private final MarketIndexJpaRepository indices;

    public MarketIndexCatalogService(MarketIndexJpaRepository indices) {
        this.indices = indices;
    }

    @Transactional
    public int seed() {
        List<Definition> definitions = List.of(
                new Definition("VNINDEX", "VN-Index", "HOSE", "Broad HOSE market index", true),
                new Definition("VN30", "VN30", "HOSE", "HOSE large-cap basket", false),
                new Definition("HNXINDEX", "HNX-Index", "HNX", "Broad HNX market index", true));
        for (Definition definition : definitions) {
            indices.findByCodeIgnoreCase(definition.code()).ifPresentOrElse(
                    existing -> existing.update(definition.name(), definition.exchange(), "VND",
                            definition.description(), definition.benchmark(), true),
                    () -> indices.save(MarketIndexEntity.create(definition.code(),
                            definition.name(), definition.exchange(), "VND",
                            definition.description(), definition.benchmark(), true)));
        }
        return definitions.size();
    }

    private record Definition(String code, String name, String exchange,
                              String description, boolean benchmark) {}
}
