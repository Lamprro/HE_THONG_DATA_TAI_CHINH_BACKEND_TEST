package com.hethongdata.taichinh.service.admin;

import com.hethongdata.taichinh.repository.jpa.account.RevokedAccessTokenJpaRepository;
import com.hethongdata.taichinh.support.TestJwt;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.security.SecurityConfiguration;
import com.hethongdata.taichinh.controller.admin.*;
import com.hethongdata.taichinh.service.market.*;
import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import com.hethongdata.taichinh.service.macro.MacroJobCatalogService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Characterizes the existing ten URLs across the consolidated controllers. No DB/provider calls. */
class AdminDataRoutesTests {


    @Configuration @EnableWebMvc @Import(SecurityConfiguration.class) static class Config {
        @Bean RevokedAccessTokenJpaRepository revokedTokens() {
            return org.mockito.Mockito.mock(RevokedAccessTokenJpaRepository.class);
        }
        @Bean MarketDataReadService reads(){return mock(MarketDataReadService.class);}
        @Bean IndexJobProvisioningService indexJobs(){return mock(IndexJobProvisioningService.class);}
        @Bean MacroJobCatalogService macroJobs(){return mock(MacroJobCatalogService.class);}
        @Bean com.hethongdata.taichinh.service.macro.MacroCoverageService macroCoverage(){return mock(com.hethongdata.taichinh.service.macro.MacroCoverageService.class);}
        @Bean HistoricalFinancialMetricService metrics(){return mock(HistoricalFinancialMetricService.class);}
        @Bean TrainingDataQualityService quality(){return mock(TrainingDataQualityService.class);}
        @Bean MarketDataAdminController market(MarketDataReadService r,IndexJobProvisioningService j,MacroJobCatalogService m,com.hethongdata.taichinh.service.macro.MacroCoverageService c){return new MarketDataAdminController(r,j,m,c);}
        @Bean FinancialDataAdminController financial(HistoricalFinancialMetricService m,TrainingDataQualityService q){
            return new FinancialDataAdminController(m,q,mock(com.hethongdata.taichinh.service.financial.FinancialDataQualityService.class));}
        @Bean ObjectMapper securityJson() { return new ObjectMapper().findAndRegisterModules(); }
    }
    @Test void originalRoutesKeepAuthPagingStatusAndNativeDispatch() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, TestJwt.PROPERTY);
      context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
            var reads=context.getBean(MarketDataReadService.class);
            var indexJobs=context.getBean(IndexJobProvisioningService.class);var macro=context.getBean(MacroJobCatalogService.class);
            var metrics=context.getBean(HistoricalFinancialMetricService.class);var quality=context.getBean(TrainingDataQualityService.class);
            when(reads.securityPrices("FPT",0,100,"1d")).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(reads.indices(0,50)).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(reads.indexPrices("VNINDEX",0,1)).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(reads.memberships("VNINDEX",0,50)).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(indexJobs.seed()).thenReturn(3);when(macro.seed()).thenReturn(Map.of("jobs",2));
            when(quality.coverage()).thenReturn(Map.of("fixture",true));when(quality.reviewLegacy("FPT")).thenReturn(Map.of("symbol","FPT"));
            for(String path:List.of("/market-prices/FPT","/market-indices","/market-indices/VNINDEX/prices","/market-indices/VNINDEX/memberships","/macro/coverage","/financial-data/quality/coverage"))
                mvc.perform(get("/api/admin"+path)).andExpect(status().isUnauthorized());
            for(String path:List.of("/market-indices/catalog/seed","/macro/jobs/seed","/financial-data/metrics/recalculate","/financial-data/quality/review-legacy/FPT"))
                mvc.perform(post("/api/admin"+path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
            verifyNoInteractions(reads,indexJobs,macro,metrics,quality);
            mvc.perform(get("/api/admin/market-prices/FPT").param("page","-1").param("size","101").param("interval","1d").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk());
            mvc.perform(get("/api/admin/market-indices").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk());
            mvc.perform(get("/api/admin/market-indices/VNINDEX/prices").param("size","0").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk());
            mvc.perform(get("/api/admin/market-indices/VNINDEX/memberships").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk());
            mvc.perform(get("/api/admin/macro/coverage").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk());
            mvc.perform(get("/api/admin/financial-data/quality/coverage").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk()).andExpect(jsonPath("fixture").value(true));
            mvc.perform(post("/api/admin/market-indices/catalog/seed").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isCreated()).andExpect(jsonPath("jobs").value(3));
            mvc.perform(post("/api/admin/macro/jobs/seed").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk()).andExpect(jsonPath("jobs").value(2));
            mvc.perform(post("/api/admin/financial-data/quality/review-legacy/FPT").header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk()).andExpect(jsonPath("symbol").value("FPT"));
            mvc.perform(post("/api/admin/financial-data/metrics/recalculate").header("Authorization","Bearer " + TestJwt.admin()).contentType("application/json").content("{}")).andExpect(status().isOk());
            verify(reads).securityPrices("FPT",0,100,"1d");verify(reads).indexPrices("VNINDEX",0,1);
            verify(reads).indices(0,50);verify(reads).memberships("VNINDEX",0,50);
            verify(metrics).calculate(any());verify(quality).reviewLegacy("FPT");
        }
    }
}

