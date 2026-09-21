package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.entity.MetricDefinitionEntity;
import com.hethongdata.taichinh.repository.jpa.financial.MetricDefinitionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** The reviewed metric catalog. Formula strings are descriptive only, never executed. */
@Service
public class FinancialMetricCatalogService {
    private final MetricDefinitionJpaRepository definitions;
    public FinancialMetricCatalogService(MetricDefinitionJpaRepository definitions) { this.definitions = definitions; }

    @Transactional
    public int seed() {
        definitions().forEach(definition -> definitions.findByCode(definition.code())
                .ifPresentOrElse(entity -> entity.refresh(definition.name(), definition.category(), definition.description(),
                        definition.formula(), definition.unit(), definition.higherIsBetter()),
                        () -> definitions.save(MetricDefinitionEntity.create(definition.code(), definition.name(),
                                definition.category(), definition.description(), definition.formula(), definition.unit(),
                                definition.higherIsBetter()))));
        return definitions().size();
    }

    private static List<Definition> definitions() {
        return List.of(
                d("PRICE_TO_EARNINGS", "Price to earnings", "VALUATION", "P/E provider metric", "Provider supplied", "x", false),
                d("PRICE_TO_BOOK", "Price to book", "VALUATION", "P/B provider metric", "Provider supplied", "x", false),
                d("EPS_TR", "EPS trailing", "PER_SHARE", "Trailing EPS provider metric", "Provider supplied", "VND", true),
                d("ROAE_TR_AVG5Q", "ROAE trailing average 5 quarters", "PROFITABILITY", "Provider trailing average metric", "Provider supplied", "%", true),
                d("ROAA_TR_AVG5Q", "ROAA trailing average 5 quarters", "PROFITABILITY", "Provider trailing average metric", "Provider supplied", "%", true),
                d("DIVIDEND_YIELD", "Dividend yield", "MARKET", "Provider dividend yield", "Provider supplied", "%", true),
                d("GROSS_MARGIN", "Gross margin", "PROFITABILITY", "Gross profit over net sales", "GROSS_PROFIT / NET_SALES * 100", "%", true),
                d("OPERATING_MARGIN", "Operating margin", "PROFITABILITY", "Operating profit over net sales", "NET_PROFIT_FROM_OPERATING_ACTIVITIES / NET_SALES * 100", "%", true),
                d("NET_MARGIN", "Net margin", "PROFITABILITY", "Net profit after tax over net sales", "NET_PROFIT_AFTER_TAX / NET_SALES * 100", "%", true),
                d("CURRENT_RATIO", "Current ratio", "LIQUIDITY", "Current assets over short term liabilities", "CURRENT_ASSETS / SHORT_TERM_LIABILITIES", "x", true),
                d("CASH_RATIO", "Cash ratio", "LIQUIDITY", "Cash and equivalents over short term liabilities", "CASH_AND_CASH_EQUIVALENTS / SHORT_TERM_LIABILITIES", "x", true),
                d("LIABILITIES_TO_EQUITY", "Liabilities to equity", "LEVERAGE", "Liabilities over owners equity", "LIABILITIES / OWNERS_EQUITY", "x", false),
                d("OPERATING_CASH_FLOW_MARGIN", "Operating cash flow margin", "QUALITY", "Operating cash flow over net sales", "NET_CASHFLOW_FROM_OPERATING_ACTIVITIES / NET_SALES * 100", "%", true),
                d("CASH_FLOW_TO_NET_PROFIT", "Cash flow to net profit", "QUALITY", "Operating cash flow over net profit", "NET_CASHFLOW_FROM_OPERATING_ACTIVITIES / NET_PROFIT_AFTER_TAX", "x", true));
    }
    private static Definition d(String code, String name, String category, String description, String formula, String unit, boolean higher) {
        return new Definition(code, name, category, description, formula, unit, higher);
    }
    private record Definition(String code, String name, String category, String description, String formula, String unit, boolean higherIsBetter) {}
}
