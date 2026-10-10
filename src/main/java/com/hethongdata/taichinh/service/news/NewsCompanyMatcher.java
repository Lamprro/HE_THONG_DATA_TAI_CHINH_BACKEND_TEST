package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.master.CompanyAliasJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches only companies and securities already present in active master data. */
@Service
public class NewsCompanyMatcher {
    private static final String BOUNDARY = "[\\p{L}\\p{N}]";
    private final CompanyJpaRepository companies;
    private final CompanyAliasJpaRepository aliases;
    private final SecurityJpaRepository securities;
    private final ObjectMapper json;

    public NewsCompanyMatcher(CompanyJpaRepository companies, CompanyAliasJpaRepository aliases,
            SecurityJpaRepository securities, ObjectMapper json) {
        this.companies = companies;
        this.aliases = aliases;
        this.securities = securities;
        this.json = json;
    }

    public Catalog loadCatalog() {
        Set<UUID> activeCompanies = new HashSet<>();
        List<Term> terms = new ArrayList<>();
        for (CompanyEntity company : companies.findAll()) {
            if (!Boolean.TRUE.equals(company.getIsActive())) continue;
            activeCompanies.add(company.getId());
            addName(terms, company.getId(), company.getLegalName());
            addName(terms, company.getId(), company.getShortName());
        }
        for (CompanyAliasEntity alias : aliases.findAll()) {
            if (activeCompanies.contains(alias.getCompanyId()))
                addName(terms, alias.getCompanyId(), alias.getAlias());
        }
        for (SecurityEntity security : securities.findAll()) {
            if (Boolean.TRUE.equals(security.getIsActive())
                    && activeCompanies.contains(security.getCompanyId())) {
                String symbol = security.getSymbol();
                if (symbol != null && symbol.matches("[A-Z0-9]{3,10}")) {
                    terms.add(new Term(security.getCompanyId(), security.getId(), symbol,
                            "ticker", Pattern.compile("(?<!" + BOUNDARY + ")" + Pattern.quote(symbol)
                                    + "(?!" + BOUNDARY + ")")));
                }
            }
        }
        return new Catalog(List.copyOf(terms));
    }

    private void addName(List<Term> terms, UUID companyId, String raw) {
        if (raw == null) return;
        String name = raw.trim().replaceAll("\\s+", " ");
        // A short or one-word alias is too ambiguous; uppercase tickers are handled separately.
        if (name.length() < 8 || !name.contains(" ")) return;
        terms.add(new Term(companyId, null, name, "company_name",
                Pattern.compile("(?<!" + BOUNDARY + ")" + Pattern.quote(name)
                        + "(?!" + BOUNDARY + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
    }

    public List<Match> match(NewsArticleDraft article, Catalog catalog) {
        Map<UUID, Match> bestNames = new HashMap<>();
        Map<UUID, Map<UUID, Match>> tickerMatches = new HashMap<>();
        for (Term term : catalog.terms()) {
            Match found = find(article, term);
            if (found == null) continue;
            if (term.securityId() == null) {
                bestNames.merge(term.companyId(), found, NewsCompanyMatcher::better);
            } else {
                tickerMatches.computeIfAbsent(term.companyId(), ignored -> new HashMap<>())
                        .merge(term.securityId(), found, NewsCompanyMatcher::better);
            }
        }
        List<Match> result = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Match>> entry : tickerMatches.entrySet())
            result.addAll(entry.getValue().values());
        for (Map.Entry<UUID, Match> entry : bestNames.entrySet())
            if (!tickerMatches.containsKey(entry.getKey())) result.add(entry.getValue());
        result.sort(Comparator.comparing((Match match) -> match.companyId().toString())
                .thenComparing(match -> match.securityId() == null ? "" : match.securityId().toString()));
        return result;
    }

    private static Match better(Match left, Match right) {
        return left.score().compareTo(right.score()) >= 0 ? left : right;
    }

    private Match find(NewsArticleDraft article, Term term) {
        String[] fields = {article.title(), article.sapo(), article.contentText()};
        String[] names = {"title", "sapo", "content_text"};
        double[] tickerScores = {1.0, 0.90, 0.72};
        double[] nameScores = {0.92, 0.82, 0.62};
        for (int index = 0; index < fields.length; index++) {
            String value = fields[index];
            if (value == null || value.isBlank()) continue;
            Matcher match = term.pattern().matcher(value);
            if (!match.find()) continue;
            ObjectNode evidence = json.createObjectNode();
            evidence.put("field", names[index]);
            evidence.put("term", term.value());
            evidence.put("kind", term.kind());
            evidence.put("excerpt", value.substring(Math.max(0, match.start() - 60),
                    Math.min(value.length(), match.end() + 60)));
            double score = term.securityId() == null ? nameScores[index] : tickerScores[index];
            return new Match(term.companyId(), term.securityId(), BigDecimal.valueOf(score), evidence);
        }
        return null;
    }

    private record Term(UUID companyId, UUID securityId, String value, String kind, Pattern pattern) {}

    public record Catalog(List<Term> terms) {}

    public record Match(UUID companyId, UUID securityId, BigDecimal score, JsonNode evidence) {}
}
